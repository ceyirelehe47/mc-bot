"""Independent negative-input audit. Does not modify or run game code."""
import copy
import json
from pathlib import Path
from checker_functions_verbatim import judge_ia, judge_g4, _delta_consistent

bad_ia = [
    ("missing_expected_cases_and_all_raw_facts", {"cases": [
        {"id": "A01", "results": [{"state": "completed", "reason": "server_authoritative"}]}]}),
    ("contradictory_delta", {"cases": [
        {"id": "V01", "results": [{"state": "completed", "reason":
            "server_authoritative_native_craft:x:0->8:delta=32"}]}]}),
    ("positive_case_only_failed", {"cases": [
        {"id": "A01", "results": [{"state": "failed", "reason": "action_did_not_run"}]}]}),
]
records = []
for name, payload in bad_ia:
    accepted, reason = judge_ia(payload)
    records.append({"test": name, "checker": "judge_ia", "expected_accept": False,
                    "actual_accept": accepted, "reason": reason, "input": payload})

one = {"run": 1, "candidate": "C1", "result": "PASS", "final_inventory": {
    "minecraft:wooden_pickaxe": 1, "minecraft:stone_pickaxe": 1}, "cursor_empty": True}
payload = [copy.deepcopy(one) for _ in range(5)]
accepted, reason = judge_g4(payload)
records.append({"test": "five_copies_of_one_run_no_chain_evidence", "checker": "judge_g4",
                "expected_accept": False, "actual_accept": accepted, "reason": reason,
                "input": payload})
report = {"source_commit": "8626013b4a829900bc001f00349e3984130f7d21",
          "source_path": "tools/rcf1_tests_v.py",
          "method": "Execute verbatim extracted judge_ia/judge_g4/_delta_consistent functions with invalid evidence, offline Linux Python; not Minecraft LIVE.",
          "delta_helper_rejects_inconsistent_text": not _delta_consistent(
              "server_authoritative_native_craft:x:0->8:delta=32"),
          "false_accept_count": sum(r["actual_accept"] for r in records),
          "negative_case_count": len(records), "cases": records}
out = Path(__file__).parent / "checker_counterexamples.json"
out.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
print(json.dumps({k:v for k,v in report.items() if k!="cases"}, indent=2, ensure_ascii=False))
for r in records:
    print(r["test"], "ACCEPTED=" + str(r["actual_accept"]), "reason=" + r["reason"])
