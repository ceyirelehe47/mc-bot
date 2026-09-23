# Functions extracted verbatim from tools/rcf1_tests_v.py at
# 8626013b4a829900bc001f00349e3984130f7d21 (GitHub fetch turn175file0).
# Not a Minecraft/Windows test; no network or process operations.

def judge_ia(evidence):
    """I/A 判定器:每个用例须有 results 非空、每项有 state+reason,
    completed 必须含 server_authoritative_* 证据串;数量断言由
    reason 中的 before->after/gained 可重算。"""
    if not isinstance(evidence, dict):
        return False, "not-an-object"
    cases = evidence.get("cases")
    if not isinstance(cases, list) or not cases:
        return False, "empty-results"
    for c in cases:
        if not isinstance(c, dict) or "id" not in c:
            return False, "missing-id"
        r = c.get("results")
        if r is None:
            r = [c] if "state" in c else None
        if not r:
            return False, "case-%s-empty" % c.get("id")
        for item in r:
            st = (item.get("state") or "").lower()
            # NOT_RUN/空 state 不得混入计分(H5)
            if st not in ("completed", "failed", "cancelled"):
                return False, "case-%s-state-not-scoreable:%s" % (
                    c.get("id"), st or "empty")
            if st == "completed" and "server_authoritative" not in (
                    item.get("reason") or ""):
                return False, "case-%s-completed-without-proof" % c.get("id")
    return True, "ok"


def judge_g4(runs):
    """G4:五次同候选完整连续;缺一次/换候选即拒。"""
    if not isinstance(runs, list) or len(runs) != 5:
        return False, "not-five-runs"
    candidates = {r.get("candidate") for r in runs}
    if len(candidates) != 1 or None in candidates:
        return False, "candidate-changed"
    for r in runs:
        if r.get("result") != "PASS":
            return False, "run-%s-not-pass" % r.get("run")
        inv = r.get("final_inventory") or {}
        if inv.get("minecraft:wooden_pickaxe", 0) < 1 \
                or inv.get("minecraft:stone_pickaxe", 0) < 1:
            return False, "run-%s-missing-pickaxes" % r.get("run")
        if not r.get("cursor_empty"):
            return False, "run-%s-cursor-not-empty" % r.get("run")
    return True, "ok"


def _delta_consistent(reason):
    """reason 内 before->after 与 delta=N 必须一致(A->B 则 delta=B-A)。"""
    import re
    m = re.search(r":(\d+)->(\d+):delta=(\d+)", reason)
    if not m:
        return False
    b, a, d = map(int, m.groups())
    return a - b == d
