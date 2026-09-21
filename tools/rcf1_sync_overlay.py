# -*- coding: utf-8 -*-
"""MC-RCF-1-R2: 同步权威 overlay 源码 → 构建树(单一权威源,不在副本间手工改)。

权威源:仓库 aibot-dsh-m0/aibot-overlay/src
构建树:D:/code/mc-experiment/rcf1-rebuild-base/src(Gradle 构建输入)

用法: python tools/rcf1_sync_overlay.py [--check]
  --check 只校验一致性,不复制。
"""
import filecmp
import pathlib
import shutil
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
OVERLAY = REPO / "aibot-dsh-m0" / "aibot-overlay" / "src"
BUILD = pathlib.Path(r"D:\code\mc-experiment\rcf1-rebuild-base\src")


def overlay_files():
    return sorted(p for p in OVERLAY.rglob("*") if p.is_file())


def main():
    check_only = "--check" in sys.argv
    if not OVERLAY.is_dir():
        print("overlay missing: %s" % OVERLAY)
        return 2
    if not BUILD.is_dir():
        print("build tree missing: %s" % BUILD)
        return 2
    changed = []
    for src in overlay_files():
        dst = BUILD / src.relative_to(OVERLAY)
        if not dst.exists() or not filecmp.cmp(src, dst, shallow=False):
            changed.append((src, dst))
    if check_only:
        for src, dst in changed:
            print("DIFF %s" % src.relative_to(OVERLAY))
        print("%d file(s) differ" % len(changed))
        return 0 if not changed else 1
    for src, dst in changed:
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)
        print("SYNC %s" % src.relative_to(OVERLAY))
    # 构建树中 overlay 不存在的同名 java 是上游文件,不删除。
    print("synced %d file(s)" % len(changed))
    return 0


if __name__ == "__main__":
    sys.exit(main())
