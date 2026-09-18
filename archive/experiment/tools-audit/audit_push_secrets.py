# -*- coding: utf-8 -*-
"""对已推送的远端分支快照做真实密钥值泄露审计。

从不打印任何密钥明文:只加载真实值,在推送内容里做子串匹配,输出命中文件与位置。
"""
import subprocess
import sys
import pathlib

REPO = r"D:\code\mc-bot"
COMMITS = [
    ("mc2a02 push (09-11 22:44)", "origin/experiment/mc2a02-tree-harvest-reliability"),
    ("graph-core push (09-12 01:16)", "origin/experiment/mc2a-graph-core-foundation"),
]

# 本机已知真实密钥值(值不打印)
VALUES = {}
env = pathlib.Path(r"D:\code\mc-experiment\dsh-web-env.sh")
if env.exists():
    for line in env.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("export ") and "=" in line:
            k, _, v = line[7:].partition("=")
            v = v.strip().strip('"').strip("'")
            if v and "cat " not in v and len(v) >= 12:
                VALUES.setdefault(v, "dsh-web-env.sh:" + k)
env2 = pathlib.Path(r"D:\code\mc-experiment\dsh-web-env-r2.sh")
if env2.exists():
    for line in env2.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if line.startswith("export ") and "=" in line:
            k, _, v = line[7:].partition("=")
            v = v.strip().strip('"').strip("'")
            if v and "cat " not in v and len(v) >= 12:
                VALUES.setdefault(v, "dsh-web-env-r2.sh:" + k)
for name in ("mc-server-aibot", "mc-server-mc1ca"):
    t = pathlib.Path(r"D:\code\mc-experiment", name, "bridge-token.txt")
    if t.exists():
        v = t.read_text(encoding="utf-8", errors="replace").strip()
        if len(v) >= 12:
            VALUES.setdefault(v, name + ":bridge-token")
# rcon 密码(出现在历史工具脚本里)
VALUES.setdefault("aibot-p0c", "rcon-password")

# 附加模式:可疑密钥形状(仅统计,不算实锤)
PATTERNS = [
    ("sk_live_", r"sk_live_[A-Za-z0-9]{16,}"),
    ("sk-ant", r"sk-ant-[A-Za-z0-9_-]{16,}"),
    ("openai_sk", r"sk-[A-Za-z0-9]{32,}"),
    ("github_token", r"gh[pousr]_[A-Za-z0-9]{30,}"),
    ("bearer_long", r"Bearer\s+[A-Za-z0-9._-]{32,}"),
    ("aws", r"AKIA[0-9A-Z]{16}"),
]

def git(*args):
    return subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True,
                          encoding="utf-8", errors="replace")

total_hits = 0
for label, ref in COMMITS:
    print("=" * 70)
    print("PUSH:", label, "->", ref)
    files = git("ls-tree", "-r", "--name-only", ref).stdout.splitlines()
    print("files:", len(files))
    hits = 0
    for f in files:
        # 二进制/压缩文件单独处理
        blob = git("show", f"{ref}:{f}").stdout
        if blob is None:
            continue
        for val, src in VALUES.items():
            if val in blob:
                hits += 1
                print(f"  REAL-VALUE HIT [{src}] in {f}")
    # zstd 会话解压扫描
    for f in files:
        if f.endswith(".zstd"):
            raw = subprocess.run(["git", "-C", REPO, "show", f"{ref}:{f}"],
                                 capture_output=True).stdout
            try:
                import zstandard, io
                txt = zstandard.ZstdDecompressor().stream_reader(io.BytesIO(raw), read_across_frames=True).read()
                for val, src in VALUES.items():
                    if val.encode() in txt:
                        hits += 1
                        print(f"  REAL-VALUE HIT [{src}] in {f} (decompressed)")
            except ImportError:
                print("  (zstandard 不可用,跳过压缩文件内容)", f)
    import re
    pat_hits = {}
    for f in files:
        blob = git("show", f"{ref}:{f}").stdout
        if not blob:
            continue
        for name, pat in PATTERNS:
            for m in re.finditer(pat, blob):
                pat_hits.setdefault((name, f), 0)
                pat_hits[(name, f)] += 1
    if pat_hits:
        print("  pattern candidates (shape-only, need manual eye):")
        for (name, f), n in sorted(pat_hits.items()):
            print(f"    [{name}] x{n} in {f}")
    print("REAL-VALUE HITS:", hits)
    total_hits += hits

print("=" * 70)
print("TOTAL REAL-VALUE HITS:", total_hits)
sys.exit(1 if total_hits else 0)
