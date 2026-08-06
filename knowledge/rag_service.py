"""嘻嘻出行混合检索与精排服务。

该服务只负责检索并返回结构化知识片段。最终回答由 LibreChat 中的
大模型结合这些片段生成，从而形成 tool-based RAG 链路。
"""

from __future__ import annotations

import json
import os
from contextlib import asynccontextmanager
from enum import Enum
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from pymilvus import Collection, connections, utility
from sentence_transformers import CrossEncoder, SentenceTransformer
from torch import nn

from hybrid_retrieval import (
    BM25KeywordRetriever,
    KnowledgeDocument,
    RetrievalCandidate,
    rerank,
    retrieval_feedback,
    weighted_score_fusion,
)


COLLECTION_NAME = os.getenv("XIXI_MILVUS_COLLECTION", "xixi_travel_knowledge")
USER_MEMORY_COLLECTION_NAME = os.getenv(
    "XIXI_USER_MEMORY_COLLECTION", "xixi_user_memories"
)
MILVUS_HOST = os.getenv("MILVUS_HOST", "localhost")
MILVUS_PORT = os.getenv("MILVUS_PORT", "19530")
MODEL_NAME = os.getenv(
    "XIXI_EMBEDDING_MODEL",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
RERANKER_MODEL_NAME = os.getenv(
    "XIXI_RERANKER_MODEL",
    "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1",
)
SEMANTIC_MIN_SCORE = float(os.getenv("XIXI_RAG_MIN_SCORE", "0.30"))
MEMORY_MIN_SCORE = float(os.getenv("XIXI_MEMORY_MIN_SCORE", "0.35"))
CANDIDATE_MULTIPLIER = max(
    1, int(os.getenv("XIXI_RAG_CANDIDATE_MULTIPLIER", "4"))
)
SEMANTIC_WEIGHT = float(os.getenv("XIXI_RAG_SEMANTIC_WEIGHT", "0.95"))
KEYWORD_WEIGHT = float(os.getenv("XIXI_RAG_KEYWORD_WEIGHT", "0.05"))
RERANK_RETRIEVAL_WEIGHT = float(
    os.getenv("XIXI_RERANK_RETRIEVAL_WEIGHT", "0.90")
)
RERANK_CANDIDATE_MULTIPLIER = max(
    1, int(os.getenv("XIXI_RERANK_CANDIDATE_MULTIPLIER", "1"))
)
FEEDBACK_MIN_SCORE = float(os.getenv("XIXI_RAG_FEEDBACK_MIN_SCORE", "0.40"))
FEEDBACK_MIN_SCORE_GAP = float(
    os.getenv("XIXI_RAG_FEEDBACK_MIN_SCORE_GAP", "0.03")
)
DATA_PATH = Path(__file__).parent / "data" / "xixi_knowledge.jsonl"


class KnowledgeCategory(str, Enum):
    PLACE_ALIAS = "place_alias"
    VEHICLE = "vehicle"
    POLICY = "policy"
    SAFETY = "safety"
    INVOICE = "invoice"


class RetrievalMode(str, Enum):
    SEMANTIC = "semantic"
    KEYWORD = "keyword"
    HYBRID = "hybrid"
    HYBRID_RERANK = "hybrid_rerank"


class RetrievalStatus(str, Enum):
    EVIDENCE_FOUND = "EVIDENCE_FOUND"
    LOW_SCORE = "LOW_SCORE"
    AMBIGUOUS = "AMBIGUOUS"
    EMPTY = "EMPTY"


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=500)
    limit: int = Field(default=3, ge=1, le=5)
    category: KnowledgeCategory | None = None
    mode: RetrievalMode = RetrievalMode.HYBRID_RERANK


class KnowledgeHit(BaseModel):
    id: int
    category: str
    title: str
    content: str
    score: float


class SearchResponse(BaseModel):
    query: str
    collection: str
    retrievalStatus: RetrievalStatus
    recommendedNextAction: str
    topScore: float
    scoreGap: float
    observationReason: str
    hits: list[KnowledgeHit]


class UserMemoryUpsertRequest(BaseModel):
    memoryId: str = Field(min_length=1, max_length=36)
    userId: str = Field(min_length=1, max_length=128)
    category: str = Field(min_length=1, max_length=32)
    memoryKey: str = Field(min_length=1, max_length=64)
    memoryValue: str = Field(min_length=1, max_length=1000)
    memoryVersion: int = Field(ge=1)
    updatedAt: int = Field(ge=0)


class UserMemoryDeleteRequest(BaseModel):
    userId: str = Field(min_length=1, max_length=128)
    memoryId: str = Field(min_length=1, max_length=36)


class UserMemorySearchRequest(BaseModel):
    userId: str = Field(min_length=1, max_length=128)
    query: str = Field(min_length=1, max_length=500)
    limit: int = Field(default=5, ge=1, le=5)


class UserMemoryHit(BaseModel):
    memoryId: str
    memoryVersion: int
    score: float


class UserMemorySearchResponse(BaseModel):
    query: str
    collection: str
    hits: list[UserMemoryHit]


def load_documents() -> list[KnowledgeDocument]:
    with DATA_PATH.open("r", encoding="utf-8") as source:
        return [
            KnowledgeDocument(**json.loads(line))
            for line in source
            if line.strip()
        ]


@asynccontextmanager
async def lifespan(app: FastAPI):
    connections.connect(alias="default", host=MILVUS_HOST, port=MILVUS_PORT)
    if not utility.has_collection(COLLECTION_NAME):
        raise RuntimeError(
            f"Milvus 集合 {COLLECTION_NAME!r} 不存在，请先运行 seed_milvus.py"
        )
    if not utility.has_collection(USER_MEMORY_COLLECTION_NAME):
        raise RuntimeError(
            f"Milvus 集合 {USER_MEMORY_COLLECTION_NAME!r} 不存在，"
            "请先运行 seed_milvus.py"
        )

    collection = Collection(COLLECTION_NAME)
    memory_collection = Collection(USER_MEMORY_COLLECTION_NAME)
    collection.load()
    memory_collection.load()
    app.state.collection = collection
    app.state.memory_collection = memory_collection
    app.state.embedding_model = SentenceTransformer(MODEL_NAME)
    app.state.keyword_retriever = BM25KeywordRetriever(load_documents())
    app.state.reranker = CrossEncoder(
        RERANKER_MODEL_NAME,
        activation_fn=nn.Sigmoid(),
    )
    yield
    collection.release()
    memory_collection.release()
    connections.disconnect("default")


app = FastAPI(
    title="Xixi Travel RAG Service",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
def health(request: Request) -> dict[str, str | int]:
    collection: Collection = request.app.state.collection
    memory_collection: Collection = request.app.state.memory_collection
    return {
        "status": "UP",
        "collection": COLLECTION_NAME,
        "entities": collection.num_entities,
        "memory_collection": USER_MEMORY_COLLECTION_NAME,
        "memory_entities": memory_collection.num_entities,
    }


@app.post("/api/v1/search", response_model=SearchResponse)
def search_knowledge(payload: SearchRequest, request: Request) -> SearchResponse:
    collection: Collection = request.app.state.collection
    model: SentenceTransformer = request.app.state.embedding_model
    keyword_retriever: BM25KeywordRetriever = request.app.state.keyword_retriever
    reranker: CrossEncoder = request.app.state.reranker
    category = payload.category.value if payload.category else None
    candidate_limit = payload.limit * CANDIDATE_MULTIPLIER

    try:
        semantic_candidates = []
        if payload.mode != RetrievalMode.KEYWORD:
            vector = model.encode(
                [payload.query],
                normalize_embeddings=True,
            )[0].tolist()
            expression = f'category == "{category}"' if category else None
            results = collection.search(
                data=[vector],
                anns_field="embedding",
                param={"metric_type": "COSINE", "params": {"ef": 64}},
                limit=candidate_limit,
                expr=expression,
                output_fields=["category", "title", "content"],
            )
            for hit in results[0]:
                semantic_score = float(hit.distance)
                if semantic_score < SEMANTIC_MIN_SCORE:
                    continue
                semantic_candidates.append(
                    RetrievalCandidate(
                        document=KnowledgeDocument(
                            id=int(hit.id),
                            category=str(hit.entity.get("category")),
                            title=str(hit.entity.get("title")),
                            content=str(hit.entity.get("content")),
                        ),
                        score=semantic_score,
                    )
                )

        keyword_candidates = []
        if payload.mode != RetrievalMode.SEMANTIC:
            keyword_candidates = keyword_retriever.search(
                payload.query,
                limit=candidate_limit,
                category=category,
            )

        if payload.mode == RetrievalMode.SEMANTIC:
            final_candidates = semantic_candidates[: payload.limit]
            feedback_candidates = semantic_candidates
        elif payload.mode == RetrievalMode.KEYWORD:
            final_candidates = keyword_candidates[: payload.limit]
            feedback_candidates = final_candidates
        else:
            fused_candidates = weighted_score_fusion(
                semantic_candidates,
                keyword_candidates,
                semantic_weight=SEMANTIC_WEIGHT,
                keyword_weight=KEYWORD_WEIGHT,
            )
            feedback_candidates = fused_candidates
            if payload.mode == RetrievalMode.HYBRID:
                final_candidates = fused_candidates[: payload.limit]
            else:
                rerank_limit = payload.limit * RERANK_CANDIDATE_MULTIPLIER
                final_candidates = rerank(
                    payload.query,
                    fused_candidates[:rerank_limit],
                    reranker,
                    limit=payload.limit,
                    retrieval_weight=RERANK_RETRIEVAL_WEIGHT,
                )
    except Exception as error:
        raise HTTPException(status_code=503, detail="知识库检索暂不可用") from error

    hits = [
        KnowledgeHit(
            id=candidate.document.id,
            category=candidate.document.category,
            title=candidate.document.title,
            content=candidate.document.content,
            score=round(candidate.score, 6),
        )
        for candidate in final_candidates
    ]

    feedback = retrieval_feedback(
        feedback_candidates,
        FEEDBACK_MIN_SCORE,
        FEEDBACK_MIN_SCORE_GAP,
    )
    retrieval_status = RetrievalStatus(feedback.status)

    return SearchResponse(
        query=payload.query,
        collection=COLLECTION_NAME,
        retrievalStatus=retrieval_status,
        recommendedNextAction=feedback.next_action,
        topScore=round(feedback.top_score, 6),
        scoreGap=round(feedback.score_gap, 6),
        observationReason=feedback.reason,
        hits=hits,
    )


@app.post("/api/v1/memories/upsert", status_code=204)
def upsert_user_memory(payload: UserMemoryUpsertRequest, request: Request) -> None:
    collection: Collection = request.app.state.memory_collection
    model: SentenceTransformer = request.app.state.embedding_model
    try:
        vector = model.encode(
            [payload.memoryValue],
            normalize_embeddings=True,
        )[0].tolist()
        collection.upsert(
            [
                [payload.memoryId],
                [payload.userId],
                [payload.category],
                [payload.memoryKey],
                [payload.memoryValue],
                [payload.memoryVersion],
                [payload.updatedAt],
                [vector],
            ]
        )
        collection.flush()
    except Exception as error:
        raise HTTPException(status_code=503, detail="长期记忆索引写入失败") from error


@app.post("/api/v1/memories/delete", status_code=204)
def delete_user_memory(payload: UserMemoryDeleteRequest, request: Request) -> None:
    collection: Collection = request.app.state.memory_collection
    expression = (
        f"user_id == {json.dumps(payload.userId)} and "
        f"memory_id == {json.dumps(payload.memoryId)}"
    )
    try:
        collection.delete(expression)
        collection.flush()
    except Exception as error:
        raise HTTPException(status_code=503, detail="长期记忆索引删除失败") from error


@app.post(
    "/api/v1/memories/search",
    response_model=UserMemorySearchResponse,
)
def search_user_memory(
    payload: UserMemorySearchRequest,
    request: Request,
) -> UserMemorySearchResponse:
    collection: Collection = request.app.state.memory_collection
    model: SentenceTransformer = request.app.state.embedding_model
    try:
        vector = model.encode(
            [payload.query],
            normalize_embeddings=True,
        )[0].tolist()
        results = collection.search(
            data=[vector],
            anns_field="embedding",
            param={"metric_type": "COSINE", "params": {"ef": 64}},
            limit=payload.limit,
            expr=f"user_id == {json.dumps(payload.userId)}",
            output_fields=["memory_version", "updated_at"],
            consistency_level="Strong",
        )
    except Exception as error:
        raise HTTPException(status_code=503, detail="长期记忆检索暂不可用") from error

    hits = [
        UserMemoryHit(
            memoryId=str(hit.id),
            memoryVersion=int(hit.entity.get("memory_version")),
            score=round(float(hit.distance), 6),
        )
        for hit in results[0]
        if float(hit.distance) >= MEMORY_MIN_SCORE
    ]
    return UserMemorySearchResponse(
        query=payload.query,
        collection=USER_MEMORY_COLLECTION_NAME,
        hits=hits,
    )
