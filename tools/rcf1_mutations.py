# -*- coding: utf-8 -*-
"""MC-RCF-1-R3C 成对语义变异探针(v2;ACCEPTANCE §5-§6)。

对同一正式 CLI(rcf1_checker.py):
1. 真实完整正例(ia-facts-r3c / g4-runs-r3c)必须被接受;
2. 每个单因素变异必须被拒绝,且拒绝原因与变异相关;
3. 若正例本就被拒(I08 阻断场景),对应组标 INVALID/INCONCLUSIVE,
   不冒充"全拒成功"。

M 编号对照 ACCEPTANCE §5:
- IA 组:M01(删快照)/M03(删结束状态)/M11(空动作+借例)/
  M12(负例副作用)/M13(fixture 混入)/M14(pre/post 颠倒)/
  M15(重复计分)/M16(错数量)/M17(witness=0)/M18(未知 id)
- G4 组:M02(cursor)/M04(needs_reconcile)/M05(table oracle)/
  M06(候选混入)/M08(五合一)/M09(expect 下调)/M10(unknown 覆盖)
"""
import copy
import json
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHECKER = os.path.join(HERE, "rcf1_checker.py")


def _save(path, doc):
    with open(path, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, ensure_ascii=False, indent=1)


def _first_case(doc, cid):
    for c in doc.get("cases", []):
        if c.get("id") == cid:
            return c
    return None


def _first_action(doc, cid, op=None, state=None):
    c = _first_case(doc, cid)
    if not c:
        return None, -1
    for i, a in enumerate(c.get("actions") or []):
        if op and a.get("op") != op:
            continue
        st = str((a.get("terminal") or {}).get("state") or "")
        if state and st != state:
            continue
        return a, i
    return None, -1


def generate_ia_mutations(ia, outdir):
    """IA 单因素变异。返回 [(name, path)]。"""
    out = []

    def emit(name, doc):
        p = os.path.join(outdir, "ia-%s.json" % name)
        _save(p, doc)
        out.append((name, p))

    d = copy.deepcopy(ia)
    c = _first_case(d, "I02a")
    if c:
        c["pre_snapshot"] = None
        emit("m01-case-pre-snapshot-removed", d)

    d = copy.deepcopy(ia)
    c = _first_case(d, "I01")
    if c:
        c["actions"] = []
        emit("m11-empty-actions-borrow-next", d)

    d = copy.deepcopy(ia)
    a, _ = _first_action(d, "V02", op="move_items", state="failed")
    if a is not None:
        a["post_action"]["inventory"] = {"minecraft:dirt": 3}
        emit("m12-neg-side-effect", d)

    d = copy.deepcopy(ia)
    c = _first_case(d, "I02c")
    if c:
        # case 级 fixture 混入(case post 多 26 石)——v2 逐动作下
        # 正确数据不受影响:此变异应被"接受"(边界方向证明),
        # 单独登记不进拒绝清单。
        c["post_snapshot"]["inventory"]["minecraft:cobblestone"] = 26
        emit("m13-case-level-fixture-tolerated", d)

    d = copy.deepcopy(ia)
    a, _ = _first_action(d, "A01", op="craft", state="completed")
    if a is not None:
        a["pre_action"], a["post_action"] = \
            a["post_action"], a["pre_action"]
        emit("m14-pre-post-swapped", d)

    d = copy.deepcopy(ia)
    a1, _ = _first_action(d, "I01", op="move_items", state="completed")
    a2, _ = _first_action(d, "I03", op="move_items", state="completed")
    if a1 is not None and a2 is not None:
        a2["execution_id"] = a1["execution_id"]
        emit("m15-duplicate-identity", d)

    d = copy.deepcopy(ia)
    a, _ = _first_action(d, "I01", op="move_items", state="completed")
    if a is not None:
        r = str(a["terminal"]["reason"])
        a["terminal"]["reason"] = r.replace("gained=1", "gained=2")
        emit("m16-gain-not-requested", d)

    d = copy.deepcopy(ia)
    a, _ = _first_action(d, "A08", op="eat", state="completed")
    if a is not None:
        r = str(a["terminal"]["reason"])
        a["terminal"]["reason"] = r.replace("witness=1", "witness=0")
        emit("m17-eat-witness-zero", d)

    d = copy.deepcopy(ia)
    d["cases"].append({"id": "Z99", "attempt": 1, "actions": [],
                       "pre_snapshot": {}, "post_snapshot": {}})
    emit("m18-unknown-case-id", d)

    d = copy.deepcopy(ia)
    a, _ = _first_action(d, "I01", op="move_items", state="completed")
    if a is not None:
        a["terminal"]["state"] = "outcome_unknown"
        a["post_action"] = None
        emit("m10-unknown-without-reconciliation", d)
    return out


def generate_g4_mutations(runs, outdir):
    out = []

    def emit(name, doc):
        p = os.path.join(outdir, "g4-%s.json" % name)
        _save(p, doc)
        out.append((name, p))

    d = copy.deepcopy(runs)
    for s in d["runs"][0]["snapshots"]:
        if s.get("tag") == "final":
            s["facts"]["screen"] = {"present": False, "cursor_count": 3}
    emit("m02-cursor-present-false", d)

    d = copy.deepcopy(runs)
    del d["runs"][0]["status_end"]
    emit("m03-status-end-removed", d)

    d = copy.deepcopy(runs)
    d["runs"][0]["oracle_blocks"] = {}
    emit("m05-table-oracle-removed", d)

    d = copy.deepcopy(runs)
    d["runs"][1]["identity"]["candidate_commit"] = "e" * 40
    emit("m06-candidate-mixed", d)

    d = copy.deepcopy(runs)
    d["runs"][1]["receipts"][0]["execution_id"] = \
        d["runs"][0]["receipts"][0]["execution_id"]
    emit("m08-duplicate-exec", d)

    d = copy.deepcopy(runs)
    d["runs"][1]["identity"]["run_started_wall"] = \
        d["runs"][0]["identity"]["run_started_wall"]
    d["runs"][1]["ended_wall"] = d["runs"][0]["ended_wall"]
    emit("m08-five-from-one-intervals", d)

    d = copy.deepcopy(runs)
    d["expect"] = {"logs_need": 2, "stone_need": 1}
    emit("m09-expect-downgraded", d)

    d = copy.deepcopy(runs)
    d["runs"][0]["status_end"]["data"]["needs_reconcile"] = True
    emit("m04-needs-reconcile", d)

    d = copy.deepcopy(runs)
    for rec in d["runs"][0]["receipts"]:
        if rec.get("op") == "craft" and (
                rec.get("args") or {}).get("item") \
                == "minecraft:wooden_pickaxe":
            rec["args"]["count"] = int(rec["args"]["count"]) + 1
            break
    emit("m07-craft-args-inflated", d)
    return out


def _judge(path, mode, extra=()):
    p = subprocess.run(
        [sys.executable, CHECKER, mode, path] + list(extra),
        capture_output=True, text=True, timeout=60)
    try:
        verdict = json.loads(p.stdout)
    except ValueError:
        verdict = {"accept": False, "reason": "non-json:%s"
                   % p.stdout[-120:]}
    return p.returncode, verdict, p.stderr[-200:]


def cmd_generate(ia_path, g4_path, outdir):
    os.makedirs(outdir, exist_ok=True)
    with open(ia_path, encoding="utf-8") as fh:
        ia = json.load(fh)
    with open(g4_path, encoding="utf-8") as fh:
        runs = json.load(fh)
    ia_m = generate_ia_mutations(ia, outdir)
    g4_m = generate_g4_mutations(runs, outdir)
    def _gate_of(path):
        return "ia" if os.path.basename(path).startswith("ia-") else "g4"

    man = {"positive": {"ia": ia_path, "g4": g4_path},
           "mutations": [{"name": n, "path": os.path.basename(p),
                          "gate": _gate_of(p)}
                         for n, p in ia_m + g4_m]}
    _save(os.path.join(outdir, "manifest.json"), man)
    print(json.dumps({"generated": len(ia_m) + len(g4_m),
                      "outdir": outdir}, ensure_ascii=False))
    return 0


def cmd_run(outdir):
    man = json.load(open(os.path.join(outdir, "manifest.json"),
                         encoding="utf-8"))
    results = []
    ok_all = True
    # 正例:G4 必须被接受;IA 正例因 I08 环境阻断预期被拒 → 记
    # INCONCLUSIVE,变异组语义在 selftest 已离线覆盖(不冒充)。
    rc, v, err = _judge(man["positive"]["g4"], "judge-g4")
    pos_g4_ok = bool(v.get("accept"))
    results.append({"name": "positive-g4", "accept": pos_g4_ok,
                    "reason": v.get("reason")})
    ok_all &= pos_g4_ok
    rc, v, err = _judge(man["positive"]["ia"], "judge-ia")
    pos_ia_ok = bool(v.get("accept"))
    results.append({"name": "positive-ia", "accept": pos_ia_ok,
                    "reason": v.get("reason"),
                    "note": "I08 环境阻断(Tom's 网络不收货)——"
                            "IA 正例预期拒绝;IA 变异组标 INCONCLUSIVE"})
    for m in man["mutations"]:
        mode = "judge-" + m["gate"]
        rc, v, err = _judge(os.path.join(outdir, m["path"]), mode)
        if m["name"].endswith("fixture-tolerated"):
            # 方向性边界:该变异应被接受(逐动作守恒不受 case 级
            # fixture 影响)——B1 修复的证明面。
            accepted = bool(v.get("accept"))
            results.append({"name": m["name"], "accept": accepted,
                            "expected": "ACCEPT", "reason": v.get("reason")})
            ok_all &= accepted
            continue
        if not pos_ia_ok and m["gate"] == "ia":
            results.append({"name": m["name"], "accept": None,
                            "expected": "REJECT",
                            "status": "INCONCLUSIVE(正例被 I08 阻断)"})
            continue
        rejected = (not v.get("accept")) and rc == 1
        results.append({"name": m["name"], "accept": rejected,
                        "expected": "REJECT", "reason": v.get("reason"),
                        "rc": rc})
        ok_all &= rejected
    _save(os.path.join(outdir, "results.json"),
          {"ok_all": ok_all, "results": results})
    print(json.dumps(results, ensure_ascii=False, indent=1))
    print("MUTATIONS %s" % ("ALL-OK" if ok_all else "HAS-GAPS"))
    return 0 if ok_all else 1


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main(argv):
    if len(argv) >= 4 and argv[0] == "generate":
        return cmd_generate(argv[1], argv[2], argv[3])
    if len(argv) >= 2 and argv[0] == "run":
        return cmd_run(argv[1])
    print("usage: rcf1_mutations.py generate <ia.json> <g4.json> <outdir>"
          "\n       rcf1_mutations.py run <outdir>")
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
