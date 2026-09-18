# -*- coding: utf-8 -*-
"""Secret scan over the mc2a02 delivery tree + production sources.
Detects REAL credential VALUES (not field names) being committed."""
import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(r"D:\code\mc-bot")
TARGETS = [
    ROOT / "aibot-mc2a02-delivery",
    ROOT / "aibot-dsh-m0" / "aibot-overlay",
    ROOT / "aibot-dsh-m0" / "scripts" / "apply_to_aibot.py",
    ROOT / "aibot-dsh-m0" / "bridge-tests",
]

# real secret values known to this machine (values only; never printed)
real_values = {}
env = pathlib.Path(r"D:\code\mc-experiment\dsh-web-env.sh")
if env.exists():
    for line in env.read_text(encoding="utf-8", errors="replace").splitlines():
        m = re.match(r'export COMMANDCODE_API_KEY="([^"]+)"', line.strip())
        if m:
            real_values["commandcode_api_key"] = m.group(1)
for name in ("bridge-token.txt",):
    for sub in ("mc-server-mc1ca", "mc-server-aibot"):
        p = pathlib.Path(r"D:\code\mc-experiment") / sub / name
        if p.exists():
            v = p.read_text(encoding="utf-8", errors="replace").strip()
            if v:
                real_values[f"{sub}_{name}"] = v

print("known real secret values loaded:", sorted(real_values))

patterns = {
    "urlsafe_token_43ish": re.compile(r"\b[A-Za-z0-9_-]{40,60}\b"),
    "sk_like": re.compile(r"\bsk-[A-Za-z0-9]{16,}\b"),
    "user_key": re.compile(r"\buser_[A-Za-z0-9]{20,}\b"),
}

hits = 0
scanned = 0
for base in TARGETS:
    it = [base] if base.is_file() else ([*base.rglob("*")] if base.exists() else [])
    for path in it:
        if not path.is_file() or "__pycache__" in path.parts or path.suffix in (".zstd", ".class"):
            continue
        scanned += 1
        try:
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for label, value in real_values.items():
            if value in text:
                hits += 1
                print(f"REAL VALUE HIT [{label}]: {path.relative_to(ROOT)}")
        for pname, pat in patterns.items():
            for m in pat.finditer(text):
                frag = m.group(0)
                if any(v == frag for v in real_values.values()):
                    continue  # already reported as real
                # plausible random token in evidence: flag for manual look
                print(f"candidate[{pname}] {path.relative_to(ROOT)}: {frag[:12]}...")
print(f"scanned_files={scanned}")
print(f"REAL_SECRET_VALUE_HITS={hits}")
sys.exit(1 if hits else 0)
