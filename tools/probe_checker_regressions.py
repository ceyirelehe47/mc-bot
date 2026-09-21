# -*- coding: utf-8 -*-
"""MC-RCF-1-R2 checker 历史反例探针(对照 tools/rcf1_checker.py 主入口)。

包内 reference/review_8626013 的四组反例在这里对最终判定器运行:
全部必须被拒绝。注意:通过本探针只是四类历史负例检出,不构成整轮通过。
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
if HERE not in sys.path:
    sys.path.insert(0, HERE)
import rcf1_checker as CK  # noqa: E402

COUNTEREXAMPLES = [
    {
        "test": "missing_expected_cases_and_all_raw_facts",
        "entry": "judge_ia",
        "input": {"cases": [{"id": "A01", "results": [
            {"state": "completed", "reason": "server_authoritative"}]}]},
    },
    {
        "test": "contradictory_delta",
        "entry": "judge_ia",
        "input": {"cases": [{"id": "V01", "results": [
            {"state": "completed",
             "reason": "server_authoritative_native_craft:x:0->8:delta=32"}]}]},
    },
    {
        "test": "positive_case_only_failed",
        "entry": "judge_ia",
        "input": {"cases": [{"id": "A01", "results": [
            {"state": "failed", "reason": "action_did_not_run"}]}]},
    },
    {
        "test": "five_copies_of_one_run_no_chain_evidence",
        "entry": "judge_g4",
        "input": [
            {"run": 1, "candidate": "C1", "result": "PASS",
             "final_inventory": {"minecraft:wooden_pickaxe": 1,
                                 "minecraft:stone_pickaxe": 1},
             "cursor_empty": True}
            for _ in range(5)
        ],
    },
]


def main():
    rejected = 0
    for cx in COUNTEREXAMPLES:
        fn = CK.judge_ia if cx["entry"] == "judge_ia" else CK.judge_g4
        ok, reason = fn(cx["input"])
        line = {"test": cx["test"], "entry": cx["entry"],
                "expected_accept": False, "actual_accept": ok,
                "reason": reason}
        print(json.dumps(line, ensure_ascii=False))
        if not ok:
            rejected += 1
    print("SUMMARY %d/%d rejected" % (rejected, len(COUNTEREXAMPLES)))
    return 0 if rejected == len(COUNTEREXAMPLES) else 1


if __name__ == "__main__":
    sys.exit(main())
