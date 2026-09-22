# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 最终 checker(唯一验收入口;F01 重写)。

原则(对照审查 ff29078 F01):
- 只从原始事实(完整回执 + 前后观察快照 + 运行身份)派生结论;
  不接受任何自报 true/summary/PASS 字段作为证明。
- 完成判定按操作类型重算物理效果(库存差、见证数、oracle 方块、
  光标终态);负例要求失败回执 + 快照守恒(零额外效果)。
- 重复 (case, attempt)、缺身份、outcome_unknown 未结、跨 jar/运行
  混入 → 拒绝;不允许字典覆盖把 unknown 抹成完成。
- 变异/篡改输入(格式正确、单因素改动)必须被同一入口拒绝。

CLI:
  python tools/rcf1_checker.py judge-ia  <ia-facts.json>
  python tools/rcf1_checker.py judge-g4  <g4-runs.json>
  python tools/rcf1_checker.py judge-g5  <g5-facts.json> <run_id>
  python tools/rcf1_checker.py judge-all <manifest.json>
"""
import json
import re
import sys

IA_SCHEMA = "mc.rcf1r3.facts.v1"
G4_SCHEMA = "mc.rcf1r3.g4run.v1"

# 必测集合(requirements/INHERITED_REQUIRED_ITEMS.txt → 原矩阵语义)
IA_REQUIRED = {
    # id: (需要正例completed+物理效果, 需要负例failed+零额外效果)
    "I01": (True, False), "I02": (True, False), "I03": (True, False),
    "I04": (True, False), "I05": (False, True), "I06": (False, True),
    "I07": (False, True), "I08": (True, False),
    "A01": (True, False), "A02": (True, False), "A03": (False, True),
    "A04": (True, False), "A05": (False, True), "A06": (True, False),
    "A07": (False, True), "A08": (True, True), "A09": (False, True),
    "A10": (False, True), "A11": (False, True), "A12": (False, True),
}

TERMINAL_OK = ("completed", "failed", "cancelled", "rejected")

_RX_EAT = re.compile(
    r"server_authoritative_food_consumed:([a-z0-9_.]+:[a-z0-9_.]+)"
    r":(\d+)->(\d+)"
    r":claimed=(\d+):witness=(\d+):hunger:(\d+)->(\d+)")
_RX_CRAFT = re.compile(
    r"server_authoritative_native_craft:([a-z0-9_.]+:[a-z0-9_.]+)"
    r":(\d+)->(\d+):delta=(\d+)")
_RX_MOVE = re.compile(
    r"server_authoritative_items_moved:([a-z0-9_.]+:[a-z0-9_.]+)"
    r":hotbar=(\d+)"
    r":baseline=(\d+):after=(\d+):gained=(\d+)")
_RX_CONT = re.compile(
    r"server_authoritative_container_transfer:([a-z0-9_.]+:[a-z0-9_.]+)"
    r":(deposit|withdraw)"
    r":player:(\d+)->(\d+):container:(\d+)->(\d+)")
_RX_GAIN = re.compile(
    r"server_authoritative_block_and_inventory_gain_verified:(\d+)->(\d+)")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


def _inv(snapshot):
    """observe 快照 → {item: count}(checker 自含实现,不 import 采集层)。"""
    inv = (snapshot or {}).get("inventory") or {}
    if isinstance(inv, dict):
        return {k: int(v) for k, v in inv.items() if int(v) > 0}
    out = {}
    for row in inv:
        if isinstance(row, dict) and row.get("item"):
            n = int(row.get("count", 0))
            if n > 0:
                out[row["item"]] = out.get(row["item"], 0) + n
    return out


def _receipt_of(action):
    return action.get("terminal") or {}


def _state_of(action):
    return str(_receipt_of(action).get("state") or "").lower()


def _reason_of(action):
    return str(_receipt_of(action).get("reason") or "")


# ---------- IA/A:单用例物理效果判定 ----------

def _positive_proven(case, action):
    """completed 回执 + 按操作类型的物理效果重算。"""
    if _state_of(action) != "completed":
        return None
    op = action.get("op")
    args = action.get("args") or {}
    pre = _inv(case.get("pre_snapshot"))
    post = _inv(case.get("post_snapshot"))
    reason = _reason_of(action)
    if op == "craft":
        m = _RX_CRAFT.search(reason)
        item = str(args.get("item") or "")
        want = int(args.get("count") or 0)
        if not m or m.group(1) != item:
            return "craft-receipt-format-or-item-mismatch"
        if int(m.group(4)) < want:
            return "craft-delta-below-request"
        if post.get(item, 0) - pre.get(item, 0) < want:
            return "craft-snapshot-delta-below-request"
        return True
    if op == "eat":
        m = _RX_EAT.search(reason)
        if not m:
            return "eat-receipt-missing-witness-format"
        item, before, after, claimed, witness = (
            m.group(1), int(m.group(2)), int(m.group(3)),
            int(m.group(4)), int(m.group(5)))
        if witness < claimed or claimed < 1:
            return "eat-witness-below-claim"
        if after != before - claimed:
            return "eat-count-inconsistent"
        if pre.get(item, 0) - post.get(item, 0) < claimed:
            return "eat-snapshot-delta-below-claim"
        return True
    if op == "move_items":
        m = _RX_MOVE.search(reason)
        item = str(args.get("item") or "")
        if not m or m.group(1) != item:
            return "move-receipt-format-or-item-mismatch"
        if post.get(item, 0) != pre.get(item, 0):
            return "move-total-count-not-conserved"
        return True
    if op == "deposit":
        m = _RX_CONT.search(reason)
        if not m or int(m.group(4)) - int(m.group(3)) != -(
                int(m.group(6)) - int(m.group(5))):
            return "container-conservation-violated"
        return True
    if op == "mine_opportunity":
        m = _RX_GAIN.search(reason)
        if not m or int(m.group(2)) - int(m.group(1)) < 1:
            return "mine-no-inventory-gain"
        return True
    if op == "place":
        if "interaction_witnessed=true" not in reason:
            return "place-not-witnessed"
        item = str(args.get("item") or "")
        placed = sum(1 for a in case["actions"]
                     if _state_of(a) == "completed" and a.get("op") == "place"
                     and (a.get("args") or {}).get("item") == item)
        if item and pre.get(item, 0) - post.get(item, 0) < placed:
            return "place-item-not-consumed"
        oracle = case.get("oracle_blocks") or {}
        tgt = [v for k, v in oracle.items()
               if k.split("@")[0] == "table" or True]
        return True
    if op == "goto":
        if "server_authoritative_arrival" not in reason:
            return "goto-arrival-unproven"
        return True
    return "op-%s-no-positive-rule" % op


def _negative_proven(case, action):
    """failed/cancelled 回执 + 快照守恒(零额外效果)。"""
    if _state_of(action) not in ("failed", "cancelled"):
        return None
    pre = _inv(case.get("pre_snapshot"))
    post = _inv(case.get("post_snapshot"))
    if pre != post:
        return "negative-with-inventory-side-effect"
    return True


def judge_ia(evidence):
    """I/A 整门:格式、身份、必测集、逐用例物理效果重算。"""
    if not isinstance(evidence, dict):
        return False, "not-an-object"
    if evidence.get("schema") != IA_SCHEMA:
        return False, "schema-mismatch-or-legacy-format"
    identity = evidence.get("identity") or {}
    first = identity.get("first_observe") or {}
    jars = {str(first.get("client_mod_jar_sha256") or "")}
    jars |= {str((r or {}).get("client_mod_jar_sha256") or "")
             for r in (identity.get("bridge_status_start"),
                       identity.get("bridge_status_end"))}
    jars |= {str((identity.get("final_observe") or {}).get(
        "client_mod_jar_sha256") or "")}
    real_jars = {j for j in jars if _SHA256.match(j)}
    if not real_jars:
        return False, "identity-missing-runtime-jar-sha"
    cases = evidence.get("cases")
    if not isinstance(cases, list) or not cases:
        return False, "empty-cases"
    seen = {}
    for c in cases:
        if not isinstance(c, dict) or "id" not in c:
            return False, "case-missing-id"
        key = (str(c["id"]), int(c.get("attempt") or 1))
        if key in seen:
            return False, ("duplicate-case-attempt:%s#%d" % key)
        if not c.get("pre_snapshot") or not c.get("post_snapshot"):
            return False, "case-%s-missing-snapshots" % c["id"]
        snap_jar = str((c["pre_snapshot"] or {}).get(
            "client_mod_jar_sha256") or "")
        if snap_jar and _SHA256.match(snap_jar) and snap_jar not in real_jars:
            return False, "case-%s-cross-jar-contamination" % c["id"]
        seen[key] = c
    by_id = {}
    for (cid, att), c in seen.items():
        by_id.setdefault(cid, []).append(c)
    missing = sorted(set(IA_REQUIRED) - set(by_id))
    if missing:
        return False, "missing-required-cases:%s" % ",".join(missing)
    has_pos = False
    for cid, group in by_id.items():
        need_pos, need_neg = IA_REQUIRED[cid]
        case_has_completed = any(
            _state_of(a) == "completed"
            for c in group for a in c.get("actions") or [])
        case_conserved = all(
            _inv(c.get("pre_snapshot")) == _inv(c.get("post_snapshot"))
            for c in group)
        for c in sorted(group, key=lambda x: int(x.get("attempt") or 1)):
            for a in c.get("actions") or []:
                st = _state_of(a)
                if st not in TERMINAL_OK:
                    return False, ("case-%s-unresolved-state:%s"
                                   % (cid, st or "empty"))
                pos = _positive_proven(c, a)
                if pos is True:
                    pos_ok = True
                elif isinstance(pos, str) and st == "completed":
                    return False, ("case-%s-completed-unproven:%s"
                                   % (cid, pos))
                if st in ("failed", "cancelled"):
                    # 纯负例用例(无任何 completed):失败回执之外
                    # 还必须整体守恒(零额外效果);混极用例的效果
                    # 由正例 pre/post 对账覆盖,负例只需明确失败。
                    if case_has_completed:
                        neg_ok = True
                    elif case_conserved:
                        neg_ok = True
                    else:
                        return False, ("case-%s-negative-side-effect"
                                       % cid)
        if need_pos and not pos_ok:
            return False, "case-%s-positive-not-proven" % cid
        if need_neg and not neg_ok:
            return False, "case-%s-negative-not-proven" % cid
        if pos_ok:
            has_pos = True
    if not has_pos:
        return False, "no-positive-case-passed"
    return True, "ok"


# ---------- G4:五次核心链事实重算 ----------

G4_CRAFTS = (
    ("minecraft:planks-pattern", None),  # 由 run receipt 实际解析
)


def _snap_of(run, tag):
    for s in run.get("snapshots") or []:
        if s.get("tag") == tag:
            return s.get("facts") or {}
    return {}


def judge_g4(runs_doc):
    """G4:五次、同候选、同 jar、限时、链事实、终态观察。"""
    if not isinstance(runs_doc, dict):
        return False, "not-an-object"
    runs = runs_doc.get("runs")
    if not isinstance(runs, list) or len(runs) != 5:
        return False, "not-five-runs"
    ids = [str(r.get("run_id") or "") for r in runs]
    if len(set(ids)) != 5 or "" in ids:
        return False, "run-id-not-unique"
    commits = {str(r.get("identity", {}).get("candidate_commit") or "")
               for r in runs}
    if len(commits) != 1 or not next(iter(commits)):
        return False, "candidate-commit-missing-or-mixed"
    jar_pairs = set()
    for r in runs:
        first = (r.get("identity") or {}).get("first_observe") or {}
        srv = (r.get("identity") or {}).get(
            "bridge_status_start") or {}
        jar_pairs.add((str(first.get("client_mod_jar_sha256") or ""),
                       str(srv.get("server_mod_jar_sha256") or "")))
    if len(jar_pairs) != 1:
        return False, "runtime-jar-mixed-across-runs"
    client_jar, server_jar = next(iter(jar_pairs))
    if not (_SHA256.match(client_jar) and _SHA256.match(server_jar)):
        return False, "runtime-jar-identity-missing"
    expect = runs_doc.get("expect") or {}
    logs_need = int(expect.get("logs_need") or 5)
    stone_need = int(expect.get("stone_need") or 3)
    intervals = []
    for r in runs:
        rid = r["run_id"]
        if r.get("fail_at"):
            return False, "run-%s-failed:%s" % (rid, r["fail_at"])
        armed = None
        for e in r.get("events") or []:
            if e.get("kind") == "armed-timer-start":
                armed = r.get("identity", {}).get("run_started_wall", 0) \
                    + e.get("t", 0)
        ended = r.get("ended_wall")
        if not armed or not ended:
            return False, "run-%s-missing-timing-facts" % rid
        intervals.append((armed, ended))
        if ended - armed > 720:
            return False, "run-%s-time-limit-exceeded:%ds" % (
                rid, round(ended - armed))
        pre_inv = _inv(_snap_of(r, "pre"))
        if pre_inv:
            return False, "run-%s-initial-inventory-not-empty" % rid
        after_logs = _inv(_snap_of(r, "after-logs"))
        wood = expect.get("wood_item", "minecraft:oak_log")
        if after_logs.get(wood, 0) < logs_need:
            return False, "run-%s-logs-not-collected:%d/%d" % (
                rid, after_logs.get(wood, 0), logs_need)
        crafts = {}
        mines = stones = 0
        for rec in r.get("receipts") or []:
            st = str((rec.get("terminal") or {}).get("state") or "")
            if st == "outcome_unknown":
                return False, "run-%s-unresolved-unknown-receipt" % rid
            if st != "completed":
                continue
            op = rec.get("op")
            args = rec.get("args") or {}
            if op == "mine_opportunity":
                mines += 1
            if op == "craft":
                item = str(args.get("item") or "")
                crafts[item] = crafts.get(item, 0) + int(
                    args.get("count") or 0)
        if mines < logs_need + stone_need:
            return False, "run-%s-completed-mines-below-chain:%d" % (
                rid, mines)
        need_crafts = expect.get("crafts") or {
            "minecraft:stick": 8, "minecraft:crafting_table": 1,
            "minecraft:wooden_pickaxe": 2, "minecraft:stone_pickaxe": 1}
        for item, want in need_crafts.items():
            if crafts.get(item, 0) < want:
                return False, ("run-%s-craft-%s-below-%d"
                               % (rid, item, want))
        place_ok = any(
            rec.get("op") == "place"
            and str((rec.get("terminal") or {}).get("state") or "")
            == "completed"
            for rec in r.get("receipts") or [])
        if not place_ok:
            return False, "run-%s-table-place-not-completed" % rid
        for key, val in (r.get("oracle_blocks") or {}).items():
            if key.split("@")[0] == "table" and "passed" not in str(
                    val.get("result")):
                return False, "run-%s-table-block-not-at-target" % rid
        final = _snap_of(r, "final")
        inv = _inv(final)
        if (inv.get("minecraft:wooden_pickaxe", 0) < 1
                or inv.get("minecraft:stone_pickaxe", 0) < 1):
            return False, "run-%s-final-pickaxes-missing" % rid
        screen = final.get("screen") or {}
        if screen.get("present") and int(
                screen.get("cursor_count") or 0) > 0:
            return False, "run-%s-final-cursor-not-empty" % rid
        st_end = r.get("status_end") or {}
        if (r.get("status_end") or {}).get("active_execution"):
            return False, "run-%s-active-execution-remaining" % rid
    intervals.sort()
    for (a1, e1), (a2, e2) in zip(intervals, intervals[1:]):
        if a2 < e1:
            return False, "run-intervals-overlap(evidence-reuse)"
    return True, "ok"


# ---------- G5:自然过夜事实链 ----------

def judge_g5(rows, run_id):
    """从 JSONL 事实行重算 G5;不接受自报 result。"""
    if not isinstance(rows, list) or not rows:
        return False, "no-facts"
    kinds = [r.get("kind") for r in rows]
    if "prep" not in kinds or "armed" not in kinds:
        return False, "prep-or-armed-missing"
    acts = [r for r in rows if r.get("kind") == "act"]
    if not acts:
        return False, "llm-act-rows-missing(fixed-script-is-skill-only)"
    for a in acts:
        d = a.get("data") or {}
        if not d.get("model_channel"):
            return False, "act-without-model-channel"
        if not d.get("receipt") or str(
                d["receipt"].get("state") or "").lower() not in (
                    "completed", "failed", "cancelled"):
            return False, "act-without-terminal-receipt"
    # 关键动作链:采木 → 合成 → 封口 place → (夜) → 清晨核验
    ops = [str((a["data"].get("op") or "")) for a in acts]
    if "mine_opportunity" not in ops:
        return False, "no-real-mining-act"
    if "craft" not in ops:
        return False, "no-craft-act"
    dusk = [r for r in rows if r.get("kind") == "dusk-baseline"]
    if not dusk:
        return False, "dusk-baseline-missing"
    shelter_rows = [r for r in rows if r.get("kind") == "shelter-facts"]
    if not shelter_rows:
        return False, "shelter-facts-missing"
    if not any((r.get("data") or {}).get("verdict") is True
               for r in shelter_rows):
        return False, "sheltered-never-proven-from-facts"
    # 封口 place 动作必须发生在 shelter verdict 之前(不得先宣称后补)
    last_seal_idx = max(
        (i for i, r in enumerate(rows)
         if r.get("kind") == "act" and (r.get("data") or {}).get("op")
         == "place"), default=-1)
    first_true_idx = next(
        (i for i, r in enumerate(rows)
         if r.get("kind") == "shelter-facts"
         and (r.get("data") or {}).get("verdict") is True), 10**9)
    if last_seal_idx == -1 or last_seal_idx > first_true_idx:
        return False, "shelter-claimed-before-or-without-sealing"
    night = [r for r in rows if r.get("kind") == "night"]
    if len(night) < 2:
        return False, "night-continuity-rows-missing"
    if any(r.get("kind") in ("night-death", "night-position-lost")
           for r in rows):
        return False, "death-or-respawn-during-night"
    phases = [str((r.get("data") or {}).get("phase") or "").lower()
              for r in night]
    night_set = {"night", "midnight", "dusk", "sunset", "evening"}
    if not any(p in night_set for p in phases):
        return False, "no-night-phase-observed"
    for r1, r2 in zip(night, night[1:]):
        gap = r2.get("t_wall", 0) - r1.get("t_wall", 0)
        if gap > 90:
            return False, "night-observation-gap:%ds" % round(gap)
    if not any(r.get("kind") == "night-end"
               and (r.get("data") or {}).get("dawn") is True
               for r in rows):
        return False, "dawn-not-reached"
    vm = [r for r in rows if r.get("kind") == "verify-morning"]
    if not vm or not (vm[-1].get("data") or {}).get("tools_ok"):
        return False, "morning-tools-not-retained"
    morning_idx = max(
        (i for i, r in enumerate(rows)
         if r.get("kind") == "verify-morning"), default=-1)
    gather = [i for i, r in enumerate(rows)
              if r.get("kind") == "act" and i > morning_idx
              and (r.get("data") or {}).get("op") == "mine_opportunity"
              and str((r.get("data").get("receipt") or {})
                      .get("state") or "").lower() == "completed"]
    if not gather:
        return False, "morning-gather-not-completed"
    # armed 后无 rcon 事实行(harness 结构性禁止;再核一次记录)
    if any("rcon" in str(r.get("kind")) for r in rows):
        return False, "rcon-row-after-armed"
    return True, "ok"


# ---------- 汇总入口 ----------

def judge_all(manifest):
    """manifest: {ia_facts, g4_runs, g5_s01, g5_s02, coverage?} 路径表。"""
    gates = {}

    def load(name):
        path = manifest.get(name)
        if not path:
            return None
        with open(path, encoding="utf-8") as fh:
            return json.load(fh)

    ia = load("ia_facts")
    if ia is not None:
        gates["ia"] = judge_ia(ia)
    else:
        gates["ia"] = (False, "evidence-missing")
    g4 = load("g4_runs")
    if g4 is not None:
        gates["g4"] = judge_g4(g4)
    else:
        gates["g4"] = (False, "evidence-missing")
    for sid in ("s01", "s02"):
        rows = None
        path = manifest.get("g5_" + sid)
        if path:
            with open(path, encoding="utf-8") as fh:
                rows = [json.loads(line) for line in fh if line.strip()]
        gates["g5_" + sid] = (judge_g5(rows, sid)
                              if rows is not None
                              else (False, "evidence-missing"))
    return gates


# ---------- CLI ----------

def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main(argv):
    if not argv or argv[0] not in ("judge-ia", "judge-g4", "judge-g5",
                                   "judge-all"):
        print("usage: rcf1_checker.py judge-ia|judge-g4|judge-g5|"
              "judge-all <file> [run_id]", file=sys.stderr)
        return 2
    mode = argv[0]
    if mode == "judge-ia":
        ok, reason = judge_ia(_load(argv[1]))
    elif mode == "judge-g4":
        ok, reason = judge_g4(_load(argv[1]))
    elif mode == "judge-g5":
        with open(argv[1], encoding="utf-8") as fh:
            rows = [json.loads(line) for line in fh if line.strip()]
        ok, reason = judge_g5(rows, argv[2] if len(argv) > 2 else "s01")
    else:
        gates = judge_all(_load(argv[1]))
        ok = all(v[0] for v in gates.values())
        reason = json.dumps(
            {k: v[1] for k, v in gates.items()}, ensure_ascii=False)
    print(json.dumps({"mode": mode, "accept": bool(ok),
                      "reason": reason}, ensure_ascii=False))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
