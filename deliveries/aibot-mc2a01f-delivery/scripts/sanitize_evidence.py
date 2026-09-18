#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""MC-2A0.1 evidence 脱敏(P1-5/SEC-3):进入 git 前对交付树做 deterministic redaction。

用法: python sanitize_evidence.py <目录>
对目录下所有文本文件做幂等替换(已 REDACTED 的不再变化),并跳过脚本自身。
已知安全值(允许保留)不替换: REDACTED 标记、测试套件使用的假 token 常量。
"""
import os
import re
import sys

REPLACEMENTS = [
    (re.compile(r'\?token=[^\s\'"<>&]+'), '?token=<REDACTED>'),
    (re.compile(r'\btoken=[A-Za-z0-9_-]{16,}'), 'token=<REDACTED>'),
    (re.compile(r'Authorization:\s*Bearer\s+[A-Za-z0-9._-]+'), 'Authorization: Bearer <REDACTED>'),
    (re.compile(r'AIBOT_BRIDGE_TOKEN=[^\s\'"<>&]+'), 'AIBOT_BRIDGE_TOKEN=<REDACTED>'),
    (re.compile(r'\bapi_key=[^\s\'"<>&]+'), 'api_key=<REDACTED>'),
    (re.compile(r'cookie:\s*[^\r\n]+', re.IGNORECASE), 'cookie: <REDACTED>'),
    (re.compile(r'mc1ca-isolated-token-[A-Za-z0-9]+'), 'mc1ca-isolated-token-<REDACTED>'),
    (re.compile(r'\buser_2y[A-Za-z0-9]{8,}\b'), 'user_<REDACTED>'),
]


def sanitize_text(text):
    hits = 0
    for pattern, replacement in REPLACEMENTS:
        def sub(match, replacement=replacement):
            nonlocal hits
            hits += 1
            return replacement
        text = pattern.sub(sub, text)
    return text, hits


def is_probably_binary(path):
    try:
        with open(path, 'rb') as handle:
            chunk = handle.read(4096)
        return b'\0' in chunk
    except OSError:
        return True


def main():
    self_path = os.path.abspath(__file__)
    root = sys.argv[1] if len(sys.argv) > 1 else '.'
    total_hits = 0
    for base, dirs, names in os.walk(root):
        dirs[:] = [d for d in dirs if d not in ('.git', 'node_modules', '__pycache__', '.build')]
        for name in names:
            path = os.path.join(base, name)
            if os.path.abspath(path) == self_path:
                continue  # 脱敏脚本自身的正则源码不参与替换
            if is_probably_binary(path):
                continue
            with open(path, 'rb') as handle:
                raw = handle.read()
            try:
                text = raw.decode('utf-8')
            except UnicodeDecodeError:
                continue
            sanitized, hits = sanitize_text(text)
            total_hits += hits
            if sanitized != text:
                with open(path, 'wb') as handle:
                    handle.write(sanitized.encode('utf-8'))
                print('sanitized %s (%d hits)' % (os.path.relpath(path, root), hits))
    print('total redactions: %d' % total_hits)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
