"""生成 2100 条可复现的 RAG 合成回归查询。

这些查询由 70 个核心问题拼接表述前后缀得到，适合做参数消融和回归检查，
不构成与开发集语义独立的留出测试集。
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path


DEFAULT_OUTPUT = Path(__file__).parent / "xixi_eval_2100.jsonl"

BENCHMARK_PREFIXES = (
    "请帮我确认一下，",
    "我第一次使用嘻嘻出行，",
    "我正在规划接下来的行程，",
    "请按照平台规则说明，",
    "我想在下单之前了解，",
    "麻烦简单回答，",
    "我有一个出行问题：",
    "为了避免操作错误，想问一下，",
    "家里人让我提前确认，",
    "现在情况有点着急，请告诉我，",
)

BENCHMARK_SUFFIXES = (
    "请直接告诉我结果。",
    "我想提前做好准备。",
    "请不要推荐无关的信息。",
)

SURFACE_VARIANT_PREFIXES = (
    "帮我查一下：",
    "想确认个问题，",
    "麻烦按实际规则回答：",
    "计划用车前我需要知道，",
    "请给我一个明确答复：",
    "我不太熟悉平台，想问，",
    "现在准备叫车，请说明，",
    "能否帮忙解释一下，",
    "我替家人咨询，",
    "为了不耽误行程，请问，",
)

SURFACE_VARIANT_SUFFIXES = (
    "只回答和问题有关的内容。",
    "谢谢，请说明清楚。",
    "我需要据此决定下一步。",
)

DOCUMENT_CASES = {
    1: {
        "category": "place_alias",
        "queries": (
            "北京南站的网约车上车点在哪里？",
            "南站是不是指北京南站？",
            "北京高铁南站还有哪些别名？",
            "到北京南站后应该去哪个广场打车？",
            "坐高铁到北京南站以后在哪里叫车比较方便？",
            "火车到站后想约车，推荐的乘车区域在哪边？",
            "司机说去南站北广场，我应该去哪个上客区？",
            "高铁下来找不到打车位置该往哪里走？",
            "大家口中的南站具体是哪一个车站？",
            "在北京南边的高铁站打网约车应该在哪里等？",
        ),
    },
    2: {
        "category": "vehicle",
        "queries": (
            "轻享车型最多可以坐几个人？",
            "轻享车适合什么出行场景？",
            "想省钱应该选择哪种车型？",
            "一到三个人日常通勤选哪种车更合适？",
            "人数不多并且预算有限应该怎么选车？",
            "普通上班通勤没有大件行李需要什么车？",
            "三个人想选便宜一些的四座车，有什么建议？",
            "不追求豪华体验只想经济出行应该选哪个档位？",
            "两个人短途打车怎么选会比较划算？",
            "日常代步希望价格低一点，平台有什么车型？",
        ),
    },
    3: {
        "category": "vehicle",
        "queries": (
            "舒适车型最多可以坐几个人？",
            "商务出行适合选择舒适车型吗？",
            "携带少量行李应该选择什么车型？",
            "想要空间和乘坐体验更好应该选哪种车？",
            "四个人出差希望坐得舒服一些怎么选？",
            "接待客户时想比经济车型舒适一点应该选择什么？",
            "有几个小行李箱又在意乘坐感受应该叫哪类车？",
            "不需要六座但希望车内宽敞一些有什么车型？",
            "商务用车且人数不超过四人应该选哪个档位？",
            "长一点的路程想坐得舒服但只有三个人怎么选？",
        ),
    },
    4: {
        "category": "vehicle",
        "queries": (
            "六座车型最多可以坐几个人？",
            "多人同行应该选择什么车型？",
            "携带多件行李适合叫哪种车？",
            "五六个人一起出行怎么选车？",
            "同行人数多并且有不少行李需要什么车型？",
            "一家人集体出门，普通四座车坐不下怎么办？",
            "机场出行带着多个箱子应该选择哪个车型？",
            "六个人准备一起打车，平台有合适的车吗？",
            "乘客和行李都比较多时选什么车空间够用？",
            "团队出行不想分成两辆车应该选择哪一类？",
        ),
    },
    5: {
        "category": "policy",
        "queries": (
            "预估报价的有效期是多久？",
            "报价超过五分钟还能下单吗？",
            "旧报价失效后是否需要重新询价？",
            "为什么下单时提示报价已经过期？",
            "从询价到确认订单最多可以间隔多长时间？",
            "刚才看到的价格过了一会还能继续使用吗？",
            "拖了比较久才确认行程，是否要重新获取价格？",
            "系统不接受之前的预估价格应该怎么处理？",
            "询价结果会一直保留还是有时间限制？",
            "我拿到价格后没有立刻下单，多久会作废？",
        ),
    },
    6: {
        "category": "safety",
        "queries": (
            "创建订单前必须获得用户确认吗？",
            "取消订单是否需要用户明确同意？",
            "哪些操作不能由 Agent 直接执行？",
            "智能助手可以不询问就帮我下单吗？",
            "为什么创建和取消订单之前要再次确认？",
            "涉及订单变更时智能助手需要遵守什么规则？",
            "我只是咨询价格，系统会不会自动生成订单？",
            "没有明确答应的情况下助手能取消我的行程吗？",
            "怎样避免聊天机器人误操作我的订单？",
            "出行助手执行重要操作前需要做什么？",
        ),
    },
    7: {
        "category": "invoice",
        "queries": (
            "已完成的行程可以申请电子发票吗？",
            "发票金额按照最终结算金额计算吗？",
            "在哪里申请行程发票？",
            "订单还没有完成能开发票吗？",
            "电子发票需要等行程结束后才能申请吗？",
            "打车结束以后怎样取得报销凭证？",
            "预估价和实际支付金额不同时发票按哪个金额？",
            "正在进行中的行程为什么不能申请票据？",
            "完成订单后想报销应该去哪个页面操作？",
            "这次打车已经结束，能否开具电子票据？",
        ),
    },
}


def difficulty(query_index: int) -> str:
    if query_index < 3:
        return "exact"
    if query_index < 7:
        return "semantic"
    return "noisy"


def generate_cases(
    prefixes: tuple[str, ...] = BENCHMARK_PREFIXES,
    suffixes: tuple[str, ...] = BENCHMARK_SUFFIXES,
    case_prefix: str = "eval",
) -> list[dict[str, object]]:
    cases = []
    case_number = 1
    for document_id, specification in DOCUMENT_CASES.items():
        for query_index, core_query in enumerate(specification["queries"]):
            for prefix in prefixes:
                for suffix in suffixes:
                    cases.append(
                        {
                            "case_id": f"{case_prefix}-{case_number:04d}",
                            "query": f"{prefix}{core_query}{suffix}",
                            "relevant_ids": [document_id],
                            "category": specification["category"],
                            "difficulty": difficulty(query_index),
                        }
                    )
                    case_number += 1
    return cases


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--variant",
        choices=("benchmark", "surface-variant", "holdout"),
        default="benchmark",
        help="holdout 是 surface-variant 的兼容别名；两者都只更换前后缀",
    )
    args = parser.parse_args()

    if args.variant in {"surface-variant", "holdout"}:
        cases = generate_cases(
            SURFACE_VARIANT_PREFIXES,
            SURFACE_VARIANT_SUFFIXES,
            case_prefix="surface",
        )
    else:
        cases = generate_cases()
    if len(cases) != 2100:
        raise RuntimeError(f"评测集数量异常：{len(cases)}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8", newline="\n") as target:
        for case in cases:
            target.write(json.dumps(case, ensure_ascii=False) + "\n")
    print(f"已生成 {len(cases)} 条评测查询：{args.output}")
    print("注意：该数据集共享 70 个核心问题，只用于回归对照，不代表独立泛化能力。")


if __name__ == "__main__":
    main()
