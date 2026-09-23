# -*- coding: utf-8 -*-
"""MC-RCF-1-R3 成对语义变异探针(F01)。

从本轮完整有效真实证据生成格式正确的单因素变异;变异必须被
tools/rcf1_checker.py 同一最终入口明确拒绝(returncode 1 且
accept=false),原例必须通过。异常/超时/用法错误 ≠ 明确拒绝。

用法:
  python tools/rcf1_mutations.py generate <ia-facts.json> <g4-runs.json> <outdir>
  python tools/rcf1_mutations.py run <outdir>   # 原例+全部变异
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


def _eat_action(ia):
    for c in ia.get("cases", []):
        for i, a in enumerate(c.get("actions") or []):
            if a.get("op") == "eat" and str(
                    (a.get("terminal") or {}).get("state")) == "completed":
                return c, i
    return None, -1


def _neg_case(ia):
    for c in ia.get("cases", []):
        for a in c.get("actions") or []:
            if str((a.get("terminal") or {}).get("state") or "") in (
                    "failed", "cancelled"):
                return c
    return None


def generate_ia_mutations(ia, outdir):
    """单因素变异(ia)。返回 [(name, path)]。"""
    out = []

    def add(name, doc):
        p = os.path.join(outdir, "ia-%s.json" % name)
        _save(p, doc)
        out.append((name, p))

    c, i = _eat_action(ia)
    if c is not None and i >= 0:
        m = copy.deepcopy(ia)
        reason = m["cases"][m["cases"].index(c)]["actions"][i]["terminal"]["reason"]
        m["cases"][m["cases"].index(c)]["actions"][i]["terminal"]["reason"] = \
            reason.replace("witness=1", "witness=0")
        add("eat-witness-zero", m)
    neg = _neg_case(ia)
    if neg is not None:
        m = copy.deepcopy(ia)
        case = m["cases"][m["cases"].index(neg)]
        case["post_snapshot"] = dict(case["post_snapshot"])
        inv = dict(case["post_snapshot"].get("inventory") or {})
        inv["minecraft:dirt"] = inv.get("minecraft:dirt", 0) + 1
        case["post_snapshot"]["inventory"] = inv
        add("negative-inventory-side-effect", m)
    m = copy.deepcopy(ia)
    m["cases"].append(copy.deepcopy(ia["cases"][0]))
    add("duplicate-case-attempt", m)
    m = copy.deepcopy(ia)
    m["identity"] = dict(m["identity"])
    m["identity"]["first_observe"] = dict(
        m["identity"].get("first_observe") or {})
    m["identity"]["first_observe"]["client_mod_jar_sha256"] = None
    st = dict(m["identity"].get("bridge_status_start") or {})
    st["server_mod_jar_sha256"] = None
    m["identity"]["bridge_status_start"] = st
    add("identity-stripped", m)
    for c in ia.get("cases", []):
        for a in c.get("actions") or []:
            if a.get("op") == "craft" and str(
                    (a.get("terminal") or {}).get("state")) == "failed":
                m = copy.deepcopy(ia)
                cc = [x for x in m["cases"] if x["id"] == c["id"]][0]
                for aa, bb in zip(cc["actions"], c["actions"]):
                    if aa is not None and bb.get("op") == "craft" and str(
                            bb.get("terminal", {}).get("state")) == "failed":
                        aa["terminal"]["state"] = "completed"
                        break
                add("failed-craft-flipped-completed", m)
                break
        else:
            continue
        break
    return out


def generate_g4_mutations(runs, outdir):
    out = []

    def add(name, doc):
        p = os.path.join(outdir, "g4-%s.json" % name)
        _save(p, doc)
        out.append((name, p))

    m = copy.deepcopy(runs)
    first = m["runs"][0]
    m["runs"] = [dict(first, run_id="x%d" % i) for i in range(5)]
    add("five-runs-from-one", m)
    m = copy.deepcopy(runs)
    for s in m["runs"][0].get("snapshots", []):
        if s.get("tag") == "pre":
            s["facts"] = dict(s["facts"])
            s["facts"]["inventory"] = {"minecraft:dirt": 1}
    add("initial-inventory-nonempty", m)
    m = copy.deepcopy(runs)
    for s in m["runs"][0].get("snapshots", []):
        if s.get("tag") == "final":
            s["facts"] = dict(s["facts"])
            s["facts"]["screen"] = {"present": True, "cursor_count": 3,
                                    "cursor_item": "minecraft:dirt"}
    add("final-cursor-occupied", m)
    m = copy.deepcopy(runs)
    for s in m["runs"][0].get("snapshots", []):
        if s.get("tag") == "after-logs":
            m["runs"][0]["snapshots"].remove(s)
    add("after-logs-snapshot-removed", m)
    m = copy.deepcopy(runs)
    m["runs"][1]["identity"]["first_observe"] = dict(
        m["runs"][1]["identity"]["first_observe"])
    m["runs"][1]["identity"]["first_observe"][
        "client_mod_jar_sha256"] = "f" * 64
    add("runtime-jar-mixed", m)
    m = copy.deepcopy(runs)
    for rec in m["runs"][0].get("receipts", []):
        if rec.get("op") == "mine_opportunity":
            rec["terminal"] = dict(rec["terminal"])
            rec["terminal"]["state"] = "outcome_unknown"
            break
    add("unresolved-unknown-receipt", m)
    return out


def _judge(path, mode, extra=()):
    p = subprocess.run(
        [sys.executable, CHECKER, mode, path, *extra],
        capture_output=True, text=True, timeout=120)
    try:
        verdict = json.loads(p.stdout)
    except ValueError:
        return p.returncode, None, p.stderr[-300:]
    return p.returncode, verdict, p.stderr[-300:]


def cmd_generate(ia_path, g4_path, outdir):
    os.makedirs(outdir, exist_ok=True)
    ia = _load(ia_path)
    runs = _load(g4_path)
    pairs = generate_ia_mutations(ia, outdir)
    pairs += generate_g4_mutations(runs, outdir)
    _save(os.path.join(outdir, "manifest.json"), {
        "ia_original": os.path.abspath(ia_path),
        "g4_original": os.path.abspath(g4_path),
        "ia_mutations": [n for n, _ in pairs[:5]],
        "g4_mutations": [n for n, _ in pairs[5:]],
    })
    print(json.dumps({"generated": len(pairs),
                      "outdir": outdir}, ensure_ascii=False))
    return 0


def cmd_run(outdir):
    man = _load(os.path.join(outdir, "manifest.json"))
    results = []
    ok_all = True
    ia_path = man["ia_original"]
    rc, v, err = _judge(ia_path, "judge-ia")
    good = rc == 0 and v and v.get("accept") is True
    results.append(("original-ia-accepted", good))
    ok_all &= good
    g4_path = man["g4_original"]
    rc, v, err = _judge(g4_path, "judge-g4")
    good = rc == 0 and v and v.get("accept") is True
    results.append(("original-g4-accepted", good))
    ok_all &= good
    for name in man.get("ia_mutations", []):
        p = os.path.join(outdir, "ia-%s.json" % name)
        rc, v, err = _judge(p, "judge-ia")
        good = (rc == 1 and v is not None and v.get("accept") is False)
        results.append((name, good))
        ok_all &= good
    for name in man.get("g4_mutations", []):
        p = os.path.join(outdir, "g4-%s.json" % name)
        rc, v, err = _judge(p, "judge-g4")
        good = (rc == 1 and v is not None and v.get("accept") is False)
        results.append((name, good))
        ok_all &= good
    for name, good in results:
        print(json.dumps({"probe": name,
                          "pass": bool(good)}, ensure_ascii=False))
    print("MUTATIONS %s" % ("ALL-REJECTED" if ok_all else "PROBE-FAILED"))
    return 0 if ok_all else 1


def _load(path):
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def main(argv):
    if len(argv) >= 2 and argv[0] == "generate":
        return cmd_generate(argv[1], argv[2], argv[3])
    if len(argv) >= 2 and argv[0] == "run":
        return cmd_run(argv[1])
    print("usage: generate <ia> <g4> <outdir> | run <outdir>",
          file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
