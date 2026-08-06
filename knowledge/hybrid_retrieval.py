"""嘻嘻出行知识库的关键词召回、分数融合与精排逻辑。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol, Sequence

import jieba
from rank_bm25 import BM25Okapi


@dataclass(frozen=True)
class KnowledgeDocument:
    id: int
    category: str
    title: str
    content: str

    @property
    def text(self) -> str:
        return f"{self.title}。{self.content}"


@dataclass(frozen=True)
class RetrievalCandidate:
    document: KnowledgeDocument
    score: float


@dataclass(frozen=True)
class RerankedCandidate:
    document: KnowledgeDocument
    score: float


@dataclass(frozen=True)
class RetrievalFeedback:
    status: str
    next_action: str
    top_score: float
    score_gap: float
    reason: str


class Reranker(Protocol):
    def predict(
        self,
        sentences: list[tuple[str, str]],
        *,
        show_progress_bar: bool = False,
    ) -> Sequence[float]: ...


def retrieval_feedback(
    candidates: Sequence[RetrievalCandidate],
    min_score: float,
    min_score_gap: float = 0.03,
) -> RetrievalFeedback:
    """将召回结果转为供 Agent 下一轮决策使用的结构化反馈。"""
    if not candidates:
        return RetrievalFeedback(
            "EMPTY",
            "REFORMULATE_AND_RETRY_ONCE",
            0.0,
            0.0,
            "没有召回满足最低过滤条件的知识片段",
        )

    top_score = float(candidates[0].score)
    second_score = float(candidates[1].score) if len(candidates) > 1 else 0.0
    score_gap = top_score - second_score if len(candidates) > 1 else top_score
    if top_score < min_score:
        return RetrievalFeedback(
            "LOW_SCORE",
            "REFORMULATE_AND_RETRY_ONCE",
            top_score,
            score_gap,
            "最高召回分数低于证据反馈阈值",
        )
    if len(candidates) > 1 and score_gap < min_score_gap:
        return RetrievalFeedback(
            "AMBIGUOUS",
            "ADD_DISAMBIGUATING_ENTITY_AND_RETRY_ONCE",
            top_score,
            score_gap,
            "前两条候选分数过于接近，需要补充实体消歧",
        )
    return RetrievalFeedback(
        "EVIDENCE_FOUND",
        "VERIFY_COVERAGE_THEN_ANSWER",
        top_score,
        score_gap,
        "召回结果达到分数和区分度要求",
    )


def tokenize(text: str) -> list[str]:
    """使用 jieba 搜索模式切分中文，同时保留英文和数字词项。"""
    return [
        token.strip().lower()
        for token in jieba.cut_for_search(text)
        if token.strip() and any(character.isalnum() for character in token)
    ]


class BM25KeywordRetriever:
    def __init__(self, documents: Sequence[KnowledgeDocument]) -> None:
        self._documents = list(documents)
        self._index = BM25Okapi([tokenize(document.text) for document in documents])

    def search(
        self,
        query: str,
        limit: int,
        category: str | None = None,
    ) -> list[RetrievalCandidate]:
        query_tokens = tokenize(query)
        if not query_tokens or limit <= 0:
            return []

        scores = self._index.get_scores(query_tokens)
        candidates = [
            RetrievalCandidate(document=document, score=float(scores[index]))
            for index, document in enumerate(self._documents)
            if float(scores[index]) > 0
            and (category is None or document.category == category)
        ]
        return sorted(
            candidates,
            key=lambda candidate: (-candidate.score, candidate.document.id),
        )[:limit]


def weighted_score_fusion(
    semantic_ranking: Sequence[RetrievalCandidate],
    keyword_ranking: Sequence[RetrievalCandidate],
    *,
    semantic_weight: float = 0.95,
    keyword_weight: float = 0.05,
) -> list[RetrievalCandidate]:
    """融合余弦相似度与归一化 BM25 分数，并按知识 ID 去重。"""
    if semantic_weight < 0 or keyword_weight < 0:
        raise ValueError("fusion weights must not be negative")
    if semantic_weight + keyword_weight <= 0:
        raise ValueError("at least one fusion weight must be positive")

    documents: dict[int, KnowledgeDocument] = {}
    fused_scores: dict[int, float] = {}
    for candidate in semantic_ranking:
        document_id = candidate.document.id
        documents[document_id] = candidate.document
        fused_scores[document_id] = semantic_weight * candidate.score

    max_keyword_score = max(
        (candidate.score for candidate in keyword_ranking),
        default=0.0,
    )
    for candidate in keyword_ranking:
        document_id = candidate.document.id
        documents[document_id] = candidate.document
        normalized_score = (
            candidate.score / max_keyword_score if max_keyword_score > 0 else 0.0
        )
        fused_scores[document_id] = fused_scores.get(document_id, 0.0) + (
            keyword_weight * normalized_score
        )

    fused = [
        RetrievalCandidate(document=documents[document_id], score=score)
        for document_id, score in fused_scores.items()
    ]
    return sorted(
        fused,
        key=lambda candidate: (-candidate.score, candidate.document.id),
    )


def rerank(
    query: str,
    candidates: Sequence[RetrievalCandidate],
    reranker: Reranker,
    limit: int,
    retrieval_weight: float = 0.90,
) -> list[RerankedCandidate]:
    """保守融合检索分数与跨编码器分数，返回最终 TopK。"""
    if not candidates or limit <= 0:
        return []
    if not 0 <= retrieval_weight <= 1:
        raise ValueError("retrieval_weight must be between 0 and 1")
    if len(candidates) == 1:
        candidate = candidates[0]
        return [
            RerankedCandidate(
                document=candidate.document,
                score=candidate.score,
            )
        ]

    pairs = [(query, candidate.document.text) for candidate in candidates]
    predicted_scores = reranker.predict(pairs, show_progress_bar=False)
    if len(predicted_scores) != len(candidates):
        raise ValueError("reranker returned an unexpected number of scores")

    retrieval_scores = [candidate.score for candidate in candidates]
    cross_encoder_scores = [float(score) for score in predicted_scores]

    def min_max_normalize(scores: Sequence[float]) -> list[float]:
        minimum = min(scores)
        maximum = max(scores)
        if maximum == minimum:
            return [0.0 for _ in scores]
        return [(score - minimum) / (maximum - minimum) for score in scores]

    normalized_retrieval = min_max_normalize(retrieval_scores)
    normalized_cross_encoder = min_max_normalize(cross_encoder_scores)
    reranked = []
    for index, candidate in enumerate(candidates):
        score = (
            retrieval_weight * normalized_retrieval[index]
            + (1 - retrieval_weight) * normalized_cross_encoder[index]
        )
        reranked.append(RerankedCandidate(document=candidate.document, score=score))
    return sorted(
        reranked,
        key=lambda candidate: (-candidate.score, candidate.document.id),
    )[:limit]
