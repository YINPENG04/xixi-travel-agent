from __future__ import annotations

import json
import unittest
from pathlib import Path

from knowledge.evaluate_retrieval import (
    EvaluationCase,
    calculate_metrics,
    load_cases,
)


PROJECT_ROOT = Path(__file__).parents[2]
DATASET_PATH = PROJECT_ROOT / "knowledge" / "evaluation" / "xixi_eval.jsonl"
LARGE_DATASET_PATH = (
    PROJECT_ROOT / "knowledge" / "evaluation" / "xixi_eval_2100.jsonl"
)
SURFACE_VARIANT_DATASET_PATH = (
    PROJECT_ROOT
    / "knowledge"
    / "evaluation"
    / "xixi_eval_holdout_2100.jsonl"
)
KNOWLEDGE_PATH = PROJECT_ROOT / "knowledge" / "data" / "xixi_knowledge.jsonl"


class EvaluateRetrievalTest(unittest.TestCase):
    def test_calculate_metrics(self) -> None:
        cases = [
            EvaluationCase("query-1", frozenset({1}), "policy"),
            EvaluationCase("query-2", frozenset({2, 3}), "vehicle"),
        ]

        metrics = calculate_metrics(
            cases,
            rankings=[[4, 1, 5], [3, 4, 5]],
            latencies_ms=[10.0, 30.0],
            k=3,
        )

        self.assertEqual(2, metrics["queries"])
        self.assertEqual(0.5, metrics["top1_accuracy"])
        self.assertEqual(1.0, metrics["hit_at_3"])
        self.assertEqual(0.75, metrics["recall_at_3"])
        self.assertEqual(0.75, metrics["mrr_at_3"])
        self.assertAlmostEqual(0.622038, metrics["ndcg_at_3"], places=6)
        self.assertEqual(20.0, metrics["average_latency_ms"])
        self.assertEqual(10.0, metrics["p50_latency_ms"])
        self.assertEqual(30.0, metrics["p95_latency_ms"])

    def test_evaluation_dataset_matches_knowledge_documents(self) -> None:
        cases = load_cases(DATASET_PATH)
        with KNOWLEDGE_PATH.open("r", encoding="utf-8") as source:
            documents = {
                int(raw["id"]): raw
                for raw in (json.loads(line) for line in source if line.strip())
            }

        self.assertEqual(35, len(cases))
        for case in cases:
            for document_id in case.relevant_ids:
                self.assertIn(document_id, documents)
                self.assertEqual(case.category, documents[document_id]["category"])

    def test_large_benchmark_has_2100_unique_stratified_queries(self) -> None:
        cases = load_cases(LARGE_DATASET_PATH)
        difficulty_counts = {
            difficulty: sum(case.difficulty == difficulty for case in cases)
            for difficulty in {case.difficulty for case in cases}
        }

        self.assertEqual(2100, len(cases))
        self.assertEqual(2100, len({case.query for case in cases}))
        self.assertEqual(
            {"exact": 630, "semantic": 840, "noisy": 630},
            difficulty_counts,
        )

    def test_surface_variant_set_only_has_disjoint_query_strings(self) -> None:
        benchmark = load_cases(LARGE_DATASET_PATH)
        surface_variants = load_cases(SURFACE_VARIANT_DATASET_PATH)
        benchmark_queries = {case.query for case in benchmark}
        surface_variant_queries = {case.query for case in surface_variants}

        self.assertEqual(2100, len(surface_variants))
        self.assertEqual(2100, len(surface_variant_queries))
        self.assertTrue(benchmark_queries.isdisjoint(surface_variant_queries))
        self.assertEqual(
            {"exact": 630, "semantic": 840, "noisy": 630},
            {
                difficulty: sum(
                    case.difficulty == difficulty for case in surface_variants
                )
                for difficulty in {case.difficulty for case in surface_variants}
            },
        )


if __name__ == "__main__":
    unittest.main()
