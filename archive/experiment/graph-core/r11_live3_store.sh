#!/usr/bin/env bash
# LIVE-R11-3: 真依赖串 aX 截断 -> 运行时启动 fail-closed task_graph_store_invalid
# 用法: bash r11_live3_store.sh <prepare|verify-restore>
set -euo pipefail
SRV=D:/code/mc-experiment/mc-server-mc1ca
STORE=$SRV/world_play/aibot/task-graphs-bob.bin
BACKUP=$SRV/world_play/aibot/task-graphs-bob.bin.r11-backup
CLASSES=D:/code/mc-experiment/aibot/build/classes/java/main
HELPER=D:/code/mc-experiment/r11helper
JDK=D:/mc-server/jdk-21.0.12.1+1
WORLD=80980dea-4a25-46fa-ab97-eeefcc8b4b39
SRC=D:/code/mc-bot/.taskpack-mc2a-graph-core-r11-final-freeze/mc-bot-mc2a-graph-core-r11-final-freeze-taskpack-20260912/live/MakeTruncatedDag.java

case "${1:-}" in
prepare)
  mkdir -p "$HELPER"
  cp "$STORE" "$BACKUP"
  "$JDK/bin/javac" -encoding UTF-8 -cp "$CLASSES" -d "$HELPER" "$SRC"
  "$JDK/bin/java" -cp "$CLASSES;$HELPER" io.github.zoyluo.aibot.external.MakeTruncatedDag "$STORE" "$WORLD" | tee D:/code/mc-experiment/r11_live3_helper_stdout.txt
  echo "--- malformed(production path) ---"
  sha256sum "$STORE"
  python - <<'EOF'
import hashlib,pathlib
p=pathlib.Path(r'D:/code/mc-experiment/mc-server-mc1ca/world_play/aibot/task-graphs-bob.bin')
m=p.read_bytes()
v=m+b'X'   # 截断只移除了最后一个字节 'X', 逐字节重建合法文件
print('malformed_bytes=',len(m))
print('valid_bytes=',len(v))
print('valid_sha256=',hashlib.sha256(v).hexdigest())
print('malformed_sha256=',hashlib.sha256(m).hexdigest())
EOF
  ;;
verify-restore)
  cmp "$BACKUP" "$STORE" && echo "RESTORE VERIFIED: store == backup" || echo "MISMATCH"
  sha256sum "$BACKUP" "$STORE"
  ;;
*)
  echo "usage: $0 <prepare|verify-restore>"; exit 2;;
esac
