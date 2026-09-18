# LIVE-2A0-1｜stable scene_hash（实机 PASS）

环境：隔离服 mc-server-mc1ca（world_r2，桥 8765，Bob 持久化恢复），
冻结 `doDaylightCycle=false`、`doMobSpawning=false`、`time set day`，Bob 静止。

## 三段证据（mc2a0_live.py view3 / viewjson，原始输出）

### 1) 冻结场景连续 3 次 view（间隔 1s）
```text
view#1 tick=595  hash=sha256:41ee5b0d3dc3e328e94a0a3f4a84a193301e02593b24a3eb0265d0c936e80850 bytes=10284
view#2 tick=615  hash=sha256:41ee5b0d3dc3e328e94a0a3f4a84a193301e02593b24a3eb0265d0c936e80850 bytes=10284
view#3 tick=636  hash=sha256:41ee5b0d3dc3e328e94a0a3f4a84a193301e02593b24a3eb0265d0c936e80850 bytes=10284
ticks_move=True hash_stable=True
```
`meta.generated_server_tick` 变化（595→636），`scene_hash` 与 `encoded_bytes` 完全一致。

### 2) RCON 明确改变 inventory 后 hash 必变
```text
give Bob minecraft:oak_log 5
→ meta: {"generated_server_tick": 688, ..., "scene_hash": "sha256:9301a9f275149f7f26a9f8444c747096c4a6ea068609f409429ef2e865dcfc9f", "encoded_bytes": 10306}
→ scene.self.inventory: oak_log=5（此前 0）
```

### 3) 新场景再次稳定
```text
view#1 tick=953  hash=sha256:9301a9f275149f7f26a9f8444c747096c4a6ea068609f409429ef2e865dcfc9f bytes=10306
view#2 tick=973  hash=sha256:9301a9f275149f7f26a9f8444c747096c4a6ea068609f409429ef2e865dcfc9f bytes=10306
view#3 tick=993  hash=sha256:9301a9f275149f7f26a9f8444c747096c4a6ea068609f409429ef2e865dcfc9f bytes=10306
ticks_move=True hash_stable=True
```

结论：时钟/游标噪声（server tick、game time）不改 hash；inventory 语义变化立即改 hash
（10-tick 快照窗口内反映，本例 RCON give 后 2s 稳定出新值）；之后稳定在新 hash。
（GameTest 侧 mc2a0StableSceneHashIgnoresClockOnlyChanges 在并行批共享 registry 的环境下
以冻结 semantic 快照 + 剔除游走实体计数的等价形式覆盖同一契约，与本实机验收互补。）
