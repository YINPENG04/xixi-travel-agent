"""嘻嘻出行 Milvus 语义检索服务。

该服务只负责检索并返回结构化知识片段。最终回答由 LibreChat 中的
大模型结合这些片段生成，从而形成 tool-based RAG 链路。
"""

from __future__ import annotations

import os
from contextlib import asynccontextmanager
from enum import Enum

from fastapi import FastAPI, HTTPException, Request
from pydantic import BaseModel, Field
from pymilvus import Collection, connections, utility
from sentence_transformers import SentenceTransformer


COLLECTION_NAME = os.getenv("XIXI_MILVUS_COLLECTION", "xixi_travel_knowledge")
MILVUS_HOST = os.getenv("MILVUS_HOST", "localhost")
MILVUS_PORT = os.getenv("MILVUS_PORT", "19530")
MODEL_NAME = os.getenv(
    "XIXI_EMBEDDING_MODEL",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
MIN_SCORE = float(os.getenv("XIXI_RAG_MIN_SCORE", "0.30"))


class KnowledgeCategory(str, Enum):
    PLACE_ALIAS = "place_alias"
    VEHICLE = "vehicle"
    POLICY = "policy"
    SAFETY = "safety"
    INVOICE = "invoice"


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=500)
    limit: int = Field(default=3, ge=1, le=5)
    category: KnowledgeCategory | None = None


class KnowledgeHit(BaseModel):
    id: int
    category: str
    title: str
    content: str
    score: float


class SearchResponse(BaseModel):
    query: str
    collection: str
    hits: list[KnowledgeHit]


@asynccontextmanager
async def lifespan(app: FastAPI):
    connections.connect(alias="default", host=MILVUS_HOST, port=MILVUS_PORT)
    if not utility.has_collection(COLLECTION_NAME):
        raise RuntimeError(
            f"Milvus 集合 {COLLECTION_NAME!r} 不存在，请先运行 seed_milvus.py"
        )

    collection = Collection(COLLECTION_NAME)
    collection.load()
    app.state.collection = collection
    app.state.embedding_model = SentenceTransformer(MODEL_NAME)
    yield
    collection.release()
    connections.disconnect("default")


app = FastAPI(
    title="Xixi Travel RAG Service",
    version="0.1.0",
    lifespan=lifespan,
)


@app.get("/health")
def health(request: Request) -> dict[str, str | int]:
    collection: Collection = request.app.state.collection
    return {
        "status": "UP",
        "collection": COLLECTION_NAME,
        "entities": collection.num_entities,
    }


@app.post("/api/v1/search", response_model=SearchResponse)
def search_knowledge(payload: SearchRequest, request: Request) -> SearchResponse:
    collection: Collection = request.app.state.collection
    model: SentenceTransformer = request.app.state.embedding_model

    try:
        vector = model.encode(
            [payload.query],
            normalize_embeddings=True,
        )[0].tolist()
        expression = (
            f'category == "{payload.category.value}"' if payload.category else None
        )
        results = collection.search(
            data=[vector],
            anns_field="embedding",
            param={"metric_type": "COSINE", "params": {"ef": 64}},
            limit=payload.limit,
            expr=expression,
            output_fields=["category", "title", "content"],
        )
    except Exception as error:
        raise HTTPException(status_code=503, detail="知识库检索暂不可用") from error

    hits = []
    for hit in results[0]:
        score = float(hit.distance)
        if score < MIN_SCORE:
            continue
        hits.append(
            KnowledgeHit(
                id=int(hit.id),
                category=str(hit.entity.get("category")),
                title=str(hit.entity.get("title")),
                content=str(hit.entity.get("content")),
                score=round(score, 6),
            )
        )

    return SearchResponse(
        query=payload.query,
        collection=COLLECTION_NAME,
        hits=hits,
    )
