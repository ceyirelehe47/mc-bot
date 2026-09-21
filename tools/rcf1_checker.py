# -*- coding: utf-8 -*-
"""MC-RCF-1-R2 最终 checker(唯一验收入口)。

judge_ia / judge_g4 是产生最终报告、COVERAGE 与 READY_FOR_REVIEW 判定的
实际入口——最终判定器与被测的是同一份代码(审查 R01)。历史四组误接受
反例(reference/review_8626013/checker_counterexamples.json)必须被本
入口拒绝;probe_checker_regressions.py 对本文件运行。

判定原则:
- server_authoritative/completed/cursor_empty 等状态与回执只是输入之一,
  不替代原始事实;reason 内的数量串会被重算,矛盾即拒绝。
- 必测集合来自 REQUIRED_ITEMS 索引对应的原验收矩阵,不由当前 TESTS 数组
  决定分母;NOT_RUN 不计分。
- 正例必须有 completed+可重算证据;负例必须有 failed/cancelled+前置
  已建立+零额外效果;全拒绝器(无正例能过)同样不合格。
- G4 五次要求唯一 run、唯一候选、独立过程证据(事件链/会话/时间线)。
"""
import json
import re
import sys

# 必测集合(requirements/REQUIRED_ITEMS.txt 路由 → 原矩阵语义)
IA_REQUIRED = {
    # id: (需要正例completed, 需要负例failed+零效果)
    "I01": (True, False),
    "I02": (True, False),
    "I03": (True, False),
    "I04": (True, False),
    "I05": (False, True),
    "I06": (False, True),
    "I07": (False, True),
    "I08": (True, False),
    "A01": (True, False),
    "A02": (True, False),
    "A03": (False, True),
    "A04": (True, False),
    "A05": (False, True),
    "A06": (True, False),
    "A07": (False, True),
    "A08": (True, True),   # 主包食物吃到(正) + 无食物/不饥饿拒绝(负)
    "A09": (False, True),
    "A10": (False, True),
    "A11": (False, True),
    "A12": (False, True),
}

SCOREABLE = ("completed", "failed", "cancelled")

_NUM = re.compile(r"(\d+)->(\d+):delta=(\d+)")
_GAIN = re.compile(r"baseline=(\d+):after=(\d+):gained=(\d+)")
_CONT = re.compile(r"player:(\d+)->(\d+):container:(\d+)->(\d+)")


def _reason_numbers_consistent(reason):
    """重算 reason 内数量串:0->8:delta=8 成立;0->8:delta=32 拒绝;
    容器双向 playerΔ == -containerΔ;净增 gained=after-baseline。"""
    for m in _NUM.finditer(reason or ""):
        b, a, d = map(int, m.groups())
        if a - b != d:
            return False
    for m in _GAIN.finditer(reason or ""):
        b, a, g = map(int, m.groups())
        if a - b != g:
            return False
    for m in _CONT.finditer(reason or ""):
        pb, pa, cb, ca = map(int, m.groups())
        if (pa - pb) != -(ca - cb):
            return False
    return True


def _case_results(case):
    r = case.get("results")
    if r is None and "state" in case:
        r = [case]
    return r if isinstance(r, list) else None

def judge_ia(evidence):
    """I/A 整门判定:必测集合、正例证据、数量重算、负例零效果。"""
    if not isinstance(evidence, dict):
        return False, "not-an-object"
    cases = evidence.get("cases")
    if not isinstance(cases, list) or not cases:
        return False, "empty-results"
    seen = {}
    for c in cases:
        if not isinstance(c, dict) or "id" not in c:
            return False, "missing-id"
        r = _case_results(c)
        if not r:
            return False, "case-%s-empty" % c.get("id")
        seen[c["id"]] = r
    # 1) 必测集合完整(不是"cases 非空即可")
    missing = sorted(set(IA_REQUIRED) - set(seen))
    if missing:
        return False, "missing-required-cases:%s" % ",".join(missing)
    # 1b) 运行注册表:每个 result 的 run_id 必须登记,且全部运行同一候选
    # (混入另一 run/world/session 的快照 = 跨运行污染,拒绝)
    registry = evidence.get("runs")
    if not isinstance(registry, dict) or not registry:
        return False, "runs-registry-required"
    for cid, results in seen.items():
        for item in results:
            rid = item.get("run_id")
            if rid not in registry:
                return False, "case-%s-unregistered-run:%s" % (cid, rid)
    candidates = set()
    for rid, meta in registry.items():
        if isinstance(meta, dict):
            candidates.add(meta.get("candidate"))
    if len(candidates) > 1:
        return False, "cross-candidate-contamination"
    has_any_positive_pass = False
    for cid, results in seen.items():
        need_pos, need_neg = IA_REQUIRED[cid]
        pos_ok = neg_ok = False
        for item in results:
            if not isinstance(item, dict):
                return False, "case-%s-non-object-result" % cid
            st = str(item.get("state") or "").lower()
            if st not in SCOREABLE:
                return False, ("case-%s-state-not-scoreable:%s"
                               % (cid, st or "empty"))
            reason = str(item.get("reason") or "")
            if st == "completed":
                # completed 必须有 server_authoritative 证据串且数量一致
                if "server_authoritative" not in reason:
                    return False, "case-%s-completed-without-proof" % cid
                if not _reason_numbers_consistent(reason):
                    return False, "case-%s-inconsistent-numbers:%s" % (cid, reason[:80])
                pos_ok = True
            else:
                # failed/cancelled 计负例:前置必须已建立、零额外效果
                if item.get("precondition_established") is not True:
                    continue
                if item.get("zero_extra_effect") is not True:
                    continue
                neg_ok = True
        if need_pos and not pos_ok:
            return False, "case-%s-positive-not-proven" % cid
        if need_neg and not neg_ok:
            return False, "case-%s-negative-not-proven" % cid
        if pos_ok:
            has_any_positive_pass = True
    # 2) 全拒绝器防护:整门没有任何正例通过同样不合法
    if not has_any_positive_pass:
        return False, "no-positive-case-passed"
    return True, "ok"


G4_CHAIN_REQUIRED = (
    "initial_empty_inventory",
    "mined_logs",
    "crafted_planks_sticks_table",
    "placed_table_witnessed",
    "crafted_wooden_pickaxe",
    "mined_stone_with_pickup",
    "crafted_stone_pickaxe",
    "both_pickaxes_final",
    "table_reopened",
    "cursor_empty",
    "no_unresolved_unknown",
)

_SHA40 = re.compile(r"^[0-9a-f]{40}$")


def judge_g4(runs):
    """G4 五次:唯一run、唯一候选、独立过程证据、完整链、限时。"""
    if not isinstance(runs, list) or len(runs) != 5:
        return False, "not-five-runs"
    candidates = {r.get("candidate") for r in runs}
    if len(candidates) != 1 or None in candidates:
        return False, "candidate-changed"
    if not all(_SHA40.match(str(r.get("candidate") or "")) for r in runs):
        return False, "candidate-not-full-sha"
    run_ids = [r.get("run") for r in runs]
    if len(set(map(json.dumps, map(_canon_run_id, run_ids)))) != 5:
        return False, "run-ids-not-unique"
    sessions = [r.get("session_id") for r in runs]
    if len(set(map(str, sessions))) != 5:
        return False, "sessions-not-distinct"
    chains = []
    for r in runs:
        if r.get("result") != "PASS":
            return False, "run-%s-not-pass" % r.get("run")
        chain = r.get("chain") or {}
        for key in G4_CHAIN_REQUIRED:
            if chain.get(key) is not True:
                return False, "run-%s-missing-chain:%s" % (r.get("run"), key)
        inv = r.get("final_inventory") or {}
        if inv.get("minecraft:wooden_pickaxe", 0) < 1 \
                or inv.get("minecraft:stone_pickaxe", 0) < 1:
            return False, "run-%s-missing-pickaxes" % r.get("run")
        duration = r.get("duration_s")
        if not isinstance(duration, (int, float)) or duration > 720:
            return False, "run-%s-over-12min" % r.get("run")
        events = r.get("events")
        if not isinstance(events, list) or not events:
            return False, "run-%s-no-process-events" % r.get("run")
        chains.append(json.dumps(events, sort_keys=True))
    # 过程独立性:复制同一 run(改 id/摘要)事件链相同 → 拒绝
    if len(set(chains)) != 5:
        return False, "duplicate-process-evidence"
    return True, "ok"


def _canon_run_id(v):
    return {"id": str(v)}


# ---------- CLI:最终报告的唯一入口 ----------

def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main(argv):
    if len(argv) != 2 or argv[0] not in ("judge-ia", "judge-g4"):
        print("usage: rcf1_checker.py judge-ia|judge-g4 <evidence.json>")
        return 2
    data = _load(argv[1])
    ok, reason = (judge_ia(data) if argv[0] == "judge-ia"
                  else judge_g4(data))
    print(json.dumps({"entry": argv[0], "accept": ok, "reason": reason},
                     ensure_ascii=False))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
