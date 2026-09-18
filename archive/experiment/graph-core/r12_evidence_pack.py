# -*- coding: utf-8 -*-
"""R1.2: 交付证据补充生成(快照哈希对比 + journal 收据节选 + 语义状态行)。"""
import hashlib
import json
import subprocess
import sys

D = r"D:/code/mc-bot/aibot-mc2a-graph-core-r12-delivery"
SEM = r"D:/code/mc-experiment/mc-server-mc1ca/world_play/aibot/external-semantics-bob.json"
JOURNAL = r"D:/code/mc-experiment/mc-server-mc1ca/world_play/aibot/external-body-bob.journal"
st = json.load(open(r"D:/code/mc-experiment/r12_state.json", encoding="utf-8"))
A, C, LEGACY = st["opp_A"], st.get("opp_C"), st.get("opp_legacy")

def sha(p):
    return hashlib.sha256(open(p, "rb").read()).hexdigest()

def receipts(oid):
    out = subprocess.run([sys.executable, r"D:/code/mc-experiment/read_lifecycle_receipts.py", JOURNAL],
                         capture_output=True, text=True).stdout
    return [json.loads(l) for l in out.splitlines()
            if json.loads(l)["fields"].get("opportunity_id") == oid]

# 04: R12-1 快照哈希三态
with open(f"{D}/04-live-birth-crash/snapshot-hashes.txt", "w", newline="\n") as f:
    f.write(f"before-A(注入基线, 无装置格机会) sha256={sha(r'D:/code/mc-experiment/r12-semantic-before-A.json')}\n")
    f.write(f"A={A}\n")
    f.write(f"birth receipt seqs={[r['seq'] for r in receipts(A) if r['fields']['kind']=='resource_opportunity_birth']}\n")
    f.write(f"consumed receipt seqs={[r['seq'] for r in receipts(A) if r['fields']['kind']=='resource_opportunity_consumed']}\n")

# 04: A 全收据节选
with open(f"{D}/04-live-birth-crash/journal-receipts-A.txt", "w", newline="\n") as f:
    for r in receipts(A):
        f.write(json.dumps(r, ensure_ascii=False) + "\n")

# 05: C 全收据节选 + 快照哈希
with open(f"{D}/05-live-terminal-dominance/journal-receipts-C.txt", "w", newline="\n") as f:
    for r in receipts(C):
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
with open(f"{D}/05-live-terminal-dominance/snapshot-hashes.txt", "w", newline="\n") as f:
    f.write(f"C={C}\n")
    f.write(f"with-C-observed(注入的旧快照, 含 C) sha256={sha(r'D:/code/mc-experiment/r12-semantic-with-C-observed.json')}\n")
    cur = json.load(open(SEM, encoding="utf-8"))
    f.write(f"重启后注册表含 C? {any(o.get('id')==C for o in cur.get('resource_opportunities', []))} (期望 False)\n")

# 06: legacy 全收据节选
with open(f"{D}/06-live-legacy-adoption/journal-receipts-legacy.txt", "w", newline="\n") as f:
    for r in receipts(LEGACY):
        f.write(json.dumps(r, ensure_ascii=False) + "\n")
with open(f"{D}/06-live-legacy-adoption/injected-legacy-row.json", "w", newline="\n") as f:
    f.write(json.dumps({"id": LEGACY, "x": 562, "y": 68, "z": 127, "block": "minecraft:iron_ore",
                        "seen": [561, 68, 127], "status": "ACTIONABLE"}, ensure_ascii=False) + "\n")

print("evidence pack done")
