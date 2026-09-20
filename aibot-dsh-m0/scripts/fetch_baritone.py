#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""MC-RCF-1 G2:下载并校验 Baritone 官方 release 资产到本地依赖缓存。

锁定(2026-09-20,经 gh 认证通道独立复核与 release 元数据一致):
  asset   : baritone-api-fabric-1.12.0.jar
  release : https://github.com/cabaletta/baritone/releases/tag/v1.12.0
  tag ref : deae0f3094b145f2afd55ff4e4b573993ae2e5bb
  sha256  : b3b36aa3d74c4df053d147ee9254b70c15f4d1e5e11a2766141a146eea3bd60b
  size    : 4810436
摘要不一致即删除并退出非零;绝不"刷新摘要让测试绿"。
"""
from __future__ import annotations

import hashlib
import pathlib
import sys
import urllib.request

URL = ("https://github.com/cabaletta/baritone/releases/download/v1.12.0/"
       "baritone-api-fabric-1.12.0.jar")
SHA256 = "b3b36aa3d74c4df053d147ee9254b70c15f4d1e5e11a2766141a146eea3bd60b"
SIZE = 4810436
DEFAULT_DIR = pathlib.Path(r"D:\mc-rcf1-raw\deps")


def main() -> int:
    target = DEFAULT_DIR / "baritone-api-fabric-1.12.0.jar"
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists():
        raw = target.read_bytes()
        digest = hashlib.sha256(raw).hexdigest()
        if digest == SHA256 and len(raw) == SIZE:
            print("CACHED_OK %s" % target)
            return 0
        print("EXISTING_ASSET_MISMATCH: deleting and re-downloading", file=sys.stderr)
        target.unlink()
    print("downloading %s" % URL)
    with urllib.request.urlopen(URL, timeout=300) as r, open(target, "wb") as f:
        f.write(r.read())
    raw = target.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != SHA256 or len(raw) != SIZE:
        target.unlink()
        print("DOWNLOAD_MISMATCH: got sha256=%s size=%d; asset deleted"
              % (digest, len(raw)), file=sys.stderr)
        return 2
    print("VERIFIED %s" % target)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
