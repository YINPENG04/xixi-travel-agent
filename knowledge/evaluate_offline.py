"""不依赖 Milvus 服务，使用相同模型和精确余弦相似度批量评测 RAG。"""

from __future__ import annotations

import argparse
import json
import os
import time
from pathlib import Path

import numpy as np
from sentence_transformers import CrossEncoder, SentenceTransformer
from torch import nn

from evaluate_retrieval import (
    DEFAULT_DATASET,
    EvaluationCase,
    calculate_ranking_metrics,
    load_cases,
)
from hybrid_retrieval import (
    BM25KeywordRetriever,
    KnowledgeDocument,
    RetrievalCandidate,
    weighted_score_fusion,
)


DATA_PATH = Path(__file__).parent / "data" / "xixi_knowledge.jsonl"
EMBEDDING_MODEL = os.getenv(
    "XIXI_EMBEDDING_MODEL",
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
)
RERANKER_MODEL = os.getenv(
    "XIXI_RERANKER_MODEL",
    "cross-encoder/mmarco-mMiniLMv2-L12-H384-v1",
)


def load_documents(path: Path) -> list[KnowledgeDocument]:
    with path.open("r", encoding="utf-8") as source:
        return [
            KnowledgeDocument(**json.loads(line))
            for line in source
            if line.strip()
        ]


def add_runtime(
    metrics: dict[str, float | int], runtime_seconds: float
) -> dict[str, float | int]:
    queries = int(metrics["queries"])
    return metrics | {
        "runtime_seconds": round(runtime_seconds, 3),
        "queries_per_second": round(queries / runtime_seconds, 3),
    }


def difficulty_breakdown(
    cases: list[EvaluationCase],
    rankings: list[list[int]],
    k: int,
) -> dict[str, dict[str, float | int]]:
    output = {}
    for difficulty in sorted({case.difficulty for case in cases}):
        indexes = [
            index for index, case in enumerate(cases) if case.difficulty == difficulty
        ]
        output[difficulty] = calculate_ranking_metrics(
            [cases[index] for index in indexes],
            [rankings[index] for index in indexes],
            k,
        )
    return output


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--documents", type=Path, default=DATA_PATH)
    parser.add_argument("--k", type=int, default=3, choices=range(1, 6))
    parser.add_argument("--candidate-multiplier", type=int, default=4)
    parser.add_argument("--semantic-min-score", type=float, default=0.30)
    parser.add_argument("--semantic-weight", type=float, default=0.95)
    parser.add_argument("--keyword-weight", type=float, default=0.05)
    parser.add_argument("--rerank-retrieval-weight", type=float, default=0.90)
    parser.add_argument("--rerank-candidate-multiplier", type=int, default=1)
    parser.add_argument("--embedding-batch-size", type=int, default=64)
    parser.add_argument("--rerank-batch-size", type=int, default=64)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    cases = load_cases(args.dataset)
    documents = load_documents(args.documents)
    candidate_limit = args.k * max(1, args.candidate_multiplier)

    model_load_started = time.perf_counter()
    embedding_model = SentenceTransformer(EMBEDDING_MODEL)
    reranker = CrossEncoder(RERANKER_MODEL, activation_fn=nn.Sigmoid())
    model_load_seconds = time.perf_counter() - model_load_started

    document_vectors = embedding_model.encode(
        [document.text for document in documents],
        batch_size=args.embedding_batch_size,
        normalize_embeddings=True,
        show_progress_bar=False,
    )

    semantic_started = time.perf_counter()
    query_vectors = embedding_model.encode(
        [case.query for case in cases],
        batch_size=args.embedding_batch_size,
        normalize_embeddings=True,
        show_progress_bar=True,
    )
    similarity_matrix = np.matmul(query_vectors, document_vectors.T)
    semantic_groups = []
    for row in similarity_matrix:
        candidates = [
            RetrievalCandidate(document=document, score=float(row[index]))
            for index, document in enumerate(documents)
            if float(row[index]) >= args.semantic_min_score
        ]
        semantic_groups.append(
            sorted(candidates, key=lambda item: (-item.score, item.document.id))[
                :candidate_limit
            ]
        )
    semantic_seconds = time.perf_counter() - semantic_started

    keyword_started = time.perf_counter()
    keyword_retriever = BM25KeywordRetriever(documents)
    keyword_groups = [
        keyword_retriever.search(case.query, candidate_limit)
        for case in cases
    ]
    keyword_seconds = time.perf_counter() - keyword_started

    fusion_started = time.perf_counter()
    fused_groups = [
        weighted_score_fusion(
            semantic,
            keyword,
            semantic_weight=args.semantic_weight,
            keyword_weight=args.keyword_weight,
        )
        for semantic, keyword in zip(
            semantic_groups, keyword_groups, strict=True
        )
    ]
    fusion_seconds = time.perf_counter() - fusion_started

    rerank_started = time.perf_counter()
    rerank_candidate_limit = args.k * max(
        1, args.rerank_candidate_multiplier
    )
    rerank_candidate_groups = [
        candidates[:rerank_candidate_limit] for candidates in fused_groups
    ]
    pairs = [
        (case.query, candidate.document.text)
        for case, candidates in zip(
            cases, rerank_candidate_groups, strict=True
        )
        for candidate in candidates
    ]
    all_rerank_scores = reranker.predict(
        pairs,
        batch_size=args.rerank_batch_size,
        show_progress_bar=True,
    )
    reranked_groups = []
    offset = 0
    for candidates in rerank_candidate_groups:
        scores = all_rerank_scores[offset : offset + len(candidates)]
        offset += len(candidates)

        def min_max(values: list[float]) -> list[float]:
            if not values:
                return []
            minimum = min(values)
            maximum = max(values)
            if maximum == minimum:
                return [0.0 for _ in values]
            return [
                (value - minimum) / (maximum - minimum) for value in values
            ]

        retrieval_scores = min_max(
            [candidate.score for candidate in candidates]
        )
        cross_encoder_scores = min_max([float(score) for score in scores])
        reranked = []
        for index, candidate in enumerate(candidates):
            blended_score = (
                args.rerank_retrieval_weight * retrieval_scores[index]
                + (1 - args.rerank_retrieval_weight)
                * cross_encoder_scores[index]
            )
            reranked.append((candidate.document.id, blended_score))
        reranked_groups.append(
            [
                document_id
                for document_id, _ in sorted(
                    reranked, key=lambda item: (-item[1], item[0])
                )[: args.k]
            ]
        )
    rerank_seconds = time.perf_counter() - rerank_started

    rankings = {
        "semantic": [
            [candidate.document.id for candidate in group[: args.k]]
            for group in semantic_groups
        ],
        "keyword": [
            [candidate.document.id for candidate in group[: args.k]]
            for group in keyword_groups
        ],
        "hybrid": [
            [candidate.document.id for candidate in group[: args.k]]
            for group in fused_groups
        ],
        "hybrid_rerank": reranked_groups,
    }
    runtimes = {
        "semantic": semantic_seconds,
        "keyword": keyword_seconds,
        "hybrid": semantic_seconds + keyword_seconds + fusion_seconds,
        "hybrid_rerank": (
            semantic_seconds + keyword_seconds + fusion_seconds + rerank_seconds
        ),
    }
    results = {
        mode: add_runtime(
            calculate_ranking_metrics(cases, mode_rankings, args.k),
            runtimes[mode],
        )
        | {"by_difficulty": difficulty_breakdown(cases, mode_rankings, args.k)}
        for mode, mode_rankings in rankings.items()
    }
    report = {
        "dataset": str(args.dataset),
        "documents": len(documents),
        "queries": len(cases),
        "top_k": args.k,
        "semantic_backend": "exact_cosine",
        "configuration": {
            "semantic_min_score": args.semantic_min_score,
            "semantic_weight": args.semantic_weight,
            "keyword_weight": args.keyword_weight,
            "rerank_retrieval_weight": args.rerank_retrieval_weight,
            "rerank_candidate_multiplier": args.rerank_candidate_multiplier,
        },
        "model_load_seconds": round(model_load_seconds, 3),
        "results": results,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
