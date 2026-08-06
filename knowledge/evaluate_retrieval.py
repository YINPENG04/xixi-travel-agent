"""离线评测嘻嘻出行知识检索的召回、排序与延迟。"""

from __future__ import annotations

import argparse
import json
import math
import statistics
import time
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


# 默认使用人工编写的冒烟集。2100 条前后缀扩增数据必须显式传入，避免把
# 合成表述变体误当作独立留出集或真实流量准确率。
DEFAULT_DATASET = Path(__file__).parent / "evaluation" / "xixi_eval.jsonl"
DEFAULT_MODES = ("semantic", "keyword", "hybrid", "hybrid_rerank")


@dataclass(frozen=True)
class EvaluationCase:
    query: str
    relevant_ids: frozenset[int]
    category: str
    difficulty: str = "unspecified"


def load_cases(path: Path) -> list[EvaluationCase]:
    cases = []
    with path.open("r", encoding="utf-8") as source:
        for line_number, line in enumerate(source, start=1):
            if not line.strip():
                continue
            raw = json.loads(line)
            relevant_ids = frozenset(int(item) for item in raw["relevant_ids"])
            if not raw["query"].strip() or not relevant_ids:
                raise ValueError(f"第 {line_number} 行缺少 query 或 relevant_ids")
            cases.append(
                EvaluationCase(
                    query=raw["query"].strip(),
                    relevant_ids=relevant_ids,
                    category=str(raw["category"]),
                    difficulty=str(raw.get("difficulty", "unspecified")),
                )
            )
    if not cases:
        raise ValueError("评测数据集不能为空")
    return cases


def calculate_metrics(
    cases: list[EvaluationCase],
    rankings: list[list[int]],
    latencies_ms: list[float],
    k: int,
) -> dict[str, float | int]:
    if len(cases) != len(rankings) or len(cases) != len(latencies_ms):
        raise ValueError("cases、rankings 和 latencies_ms 数量必须一致")

    metrics = calculate_ranking_metrics(cases, rankings, k)
    return metrics | {
        "average_latency_ms": round(statistics.fmean(latencies_ms), 3),
        "p50_latency_ms": round(percentile(latencies_ms, 0.50), 3),
        "p95_latency_ms": round(percentile(latencies_ms, 0.95), 3),
    }


def calculate_ranking_metrics(
    cases: list[EvaluationCase],
    rankings: list[list[int]],
    k: int,
) -> dict[str, float | int]:
    if not cases or len(cases) != len(rankings):
        raise ValueError("cases 和 rankings 必须非空且数量一致")

    top1_scores = []
    hit_scores = []
    recall_scores = []
    reciprocal_ranks = []
    ndcg_scores = []

    for case, ranking in zip(cases, rankings, strict=True):
        top_k = ranking[:k]
        matched = case.relevant_ids.intersection(top_k)
        top1_scores.append(float(bool(top_k and top_k[0] in case.relevant_ids)))
        hit_scores.append(float(bool(matched)))
        recall_scores.append(len(matched) / len(case.relevant_ids))

        first_relevant_rank = next(
            (
                rank
                for rank, document_id in enumerate(top_k, start=1)
                if document_id in case.relevant_ids
            ),
            None,
        )
        reciprocal_ranks.append(
            0.0 if first_relevant_rank is None else 1.0 / first_relevant_rank
        )

        dcg = sum(
            1.0 / math.log2(rank + 1)
            for rank, document_id in enumerate(top_k, start=1)
            if document_id in case.relevant_ids
        )
        ideal_hits = min(len(case.relevant_ids), k)
        ideal_dcg = sum(
            1.0 / math.log2(rank + 1) for rank in range(1, ideal_hits + 1)
        )
        ndcg_scores.append(dcg / ideal_dcg)

    return {
        "queries": len(cases),
        "top1_accuracy": round(statistics.fmean(top1_scores), 6),
        f"hit_at_{k}": round(statistics.fmean(hit_scores), 6),
        f"recall_at_{k}": round(statistics.fmean(recall_scores), 6),
        f"mrr_at_{k}": round(statistics.fmean(reciprocal_ranks), 6),
        f"ndcg_at_{k}": round(statistics.fmean(ndcg_scores), 6),
    }


def percentile(values: list[float], quantile: float) -> float:
    if not values:
        raise ValueError("values 不能为空")
    if not 0 < quantile <= 1:
        raise ValueError("quantile 必须位于 (0, 1] 区间")
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * quantile) - 1)
    return ordered[index]


def search(
    endpoint: str,
    case: EvaluationCase,
    mode: str,
    k: int,
    timeout_seconds: float,
) -> tuple[list[int], float]:
    body = json.dumps(
        {"query": case.query, "limit": k, "mode": mode}
    ).encode("utf-8")
    request = Request(
        endpoint,
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    started_at = time.perf_counter()
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            payload = json.load(response)
    except (HTTPError, URLError, TimeoutError) as error:
        raise RuntimeError(
            f"检索请求失败：mode={mode}, query={case.query!r}, error={error}"
        ) from error
    latency_ms = (time.perf_counter() - started_at) * 1000
    return [int(hit["id"]) for hit in payload["hits"]], latency_ms


def evaluate_mode(
    endpoint: str,
    cases: list[EvaluationCase],
    mode: str,
    k: int,
    timeout_seconds: float,
) -> dict[str, float | int]:
    rankings = []
    latencies_ms = []
    for case in cases:
        ranking, latency_ms = search(
            endpoint,
            case,
            mode,
            k,
            timeout_seconds,
        )
        rankings.append(ranking)
        latencies_ms.append(latency_ms)
    return calculate_metrics(cases, rankings, latencies_ms, k)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--endpoint",
        default="http://localhost:8090/api/v1/search",
        help="RAG 检索接口地址",
    )
    parser.add_argument("--dataset", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--k", type=int, default=3, choices=range(1, 6))
    parser.add_argument(
        "--modes",
        default=",".join(DEFAULT_MODES),
        help="逗号分隔的评测模式",
    )
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--output", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    modes = tuple(item.strip() for item in args.modes.split(",") if item.strip())
    unsupported_modes = set(modes).difference(DEFAULT_MODES)
    if unsupported_modes:
        raise ValueError(f"不支持的评测模式：{sorted(unsupported_modes)}")

    cases = load_cases(args.dataset)
    report = {
        "dataset": str(args.dataset),
        "top_k": args.k,
        "results": {
            mode: evaluate_mode(args.endpoint, cases, mode, args.k, args.timeout)
            for mode in modes
        },
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
