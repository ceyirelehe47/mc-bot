# -*- coding: utf-8 -*-
"""LIVE-6 transport forensics v2: multi-frame zstd via read_across_frames."""
import io
import json
import sys

import zstandard

PATH = (r"C:/Users/15027/.dsh/sessions/--D-code-mc-bot--/"
        r"session-23315a0d-48b7-4c10-9b38-718ea70752b3/session.v3.jsonl.zstd")
PATH = (r"C:/Users/15027/.dsh/sessions/--D-code-mc-bot--/"
        r"session-23315a0d-48b7-4c10-9b38-718ea70772b3/session.v3.jsonl.zstd")

raw = open(PATH, "rb").read()
text = zstandard.ZstdDecompressor(max_window_size=1 << 31).stream_reader(
    io.BytesIO(raw), read_across_frames=True).read().decode("utf-8", errors="replace")
lines = [l for l in text.split("\n") if l.strip()]
print(f"decoded lines: {len(lines)}")

targets = {}
recs = []
for line in lines:
    try:
        rec = json.loads(line)
    except json.JSONDecodeError:
        continue
    recs.append(rec)
    tgt = rec.get("target") or (rec.get("meta") or {}).get("target") or (rec.get("delivery") or {}).get("target")
    if tgt:
        targets[str(tgt)] = targets.get(str(tgt), 0) + 1

print("record types:", sorted({str(r.get('type') or r.get('kind')) for r in recs})[:20])
print("targets histogram:", targets)

followup_hits = [l for l in lines if "followup" in l.lower()]
print("lines mentioning followup:", len(followup_hits))
for l in followup_hits[:6]:
    print("  ", l[:240])

aibot = [r for r in recs if "aibot" in json.dumps(r, ensure_ascii=False).lower()]
print("aibot-related records:", len(aibot))
for r in aibot[-6:]:
    s = json.dumps(r, ensure_ascii=False)
    print("  *", s[:260])

for l in lines:
    if "0daed956" in l:
        rec = json.loads(l)
        keep = {k: rec.get(k) for k in ("type", "kind", "target", "channel", "source", "ts") if k in rec}
        print("0daed956:", keep, json.dumps(rec, ensure_ascii=False)[:280])
