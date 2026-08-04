from __future__ import annotations

import unittest

from knowledge.hybrid_retrieval import (
    BM25KeywordRetriever,
    KnowledgeDocument,
    RetrievalCandidate,
    rerank,
    weighted_score_fusion,
)


DOCUMENTS = [
    KnowledgeDocument(
        id=1,
        category="place_alias",
        title="北京南站",
        content="推荐上车点为北广场网约车上客区。",
    ),
    KnowledgeDocument(
        id=2,
        category="vehicle",
        title="轻享车型",
        content="最多乘坐四人，适合日常通勤。",
    ),
    KnowledgeDocument(
        id=3,
        category="invoice",
        title="行程发票",
        content="已完成的行程可以申请电子发票。",
    ),
]


class FakeReranker:
    def __init__(self, scores: list[float]) -> None:
        self.scores = scores

    def predict(
        self,
        sentences: list[tuple[str, str]],
        *,
        show_progress_bar: bool = False,
    ) -> list[float]:
        self.sentences = sentences
        return self.scores


class HybridRetrievalTest(unittest.TestCase):
    def test_bm25_recalls_exact_place_name(self) -> None:
        results = BM25KeywordRetriever(DOCUMENTS).search("北京南站怎么上车", limit=2)

        self.assertTrue(results)
        self.assertEqual(1, results[0].document.id)

    def test_bm25_applies_category_filter(self) -> None:
        results = BM25KeywordRetriever(DOCUMENTS).search(
            "行程发票", limit=3, category="vehicle"
        )

        self.assertEqual([], results)

    def test_weighted_fusion_normalizes_bm25_and_merges_documents(self) -> None:
        semantic = [
            RetrievalCandidate(DOCUMENTS[1], 0.91),
            RetrievalCandidate(DOCUMENTS[0], 0.80),
        ]
        keyword = [
            RetrievalCandidate(DOCUMENTS[0], 2.40),
            RetrievalCandidate(DOCUMENTS[2], 1.20),
        ]

        fused = weighted_score_fusion(semantic, keyword)

        self.assertEqual([2, 1, 3], [candidate.document.id for candidate in fused])
        self.assertAlmostEqual(0.95 * 0.80 + 0.05, fused[1].score)

    def test_rerank_blends_retrieval_and_cross_encoder_scores(self) -> None:
        candidates = [
            RetrievalCandidate(DOCUMENTS[0], 0.90),
            RetrievalCandidate(DOCUMENTS[1], 0.89),
            RetrievalCandidate(DOCUMENTS[2], 0.10),
        ]

        results = rerank(
            "如何开发票",
            candidates,
            FakeReranker([0.10, 0.90, 0.00]),
            limit=2,
            retrieval_weight=0.90,
        )

        self.assertEqual([2, 1], [candidate.document.id for candidate in results])

    def test_rerank_keeps_single_candidate_score(self) -> None:
        candidate = RetrievalCandidate(DOCUMENTS[0], 0.82)

        results = rerank(
            "北京南站在哪里上车",
            [candidate],
            FakeReranker([]),
            limit=1,
        )

        self.assertEqual(1, results[0].document.id)
        self.assertEqual(0.82, results[0].score)


if __name__ == "__main__":
    unittest.main()
