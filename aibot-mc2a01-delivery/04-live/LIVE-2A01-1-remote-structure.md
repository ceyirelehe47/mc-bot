# LIVE-2A01-1｜remote structure freshness

环境：隔离服 `mc-server-mc1ca`，world `world_play`，Bob（外部身体，owner=skyline_cc），
MC-2A0.1 jar `765de042…`。服务器日志：`server-2a01.log`（时间窗 23:43–23:5x）。

## 步骤与实测输出

1. **合法验证前置**：RCON 在 Bob(572,63,157) 处建 7x7 石平台+正对 2 格石柱，
   `mc_register_home live2a01 (radius=3, below=0, above=2)` + `mc_capture_home`
   （baseline=4 cells，registry observe 确认 snapshot_cells=4）。

2. **近处 view（VERIFIED_LIVE 基线）**：
   ```
   struct live2a01: knowledge=VERIFIED_LIVE freshness=LIVE
     summary={"baseline_cells": 4, "current_integrity":
       {"expected": 4, "knowledge": "VERIFIED_LIVE", "matched": 4, "missing": 0, "wrong": 0}}
   ```

3. **RCON 破坏一块 baseline（572,64,160 → air）后把 Bob tp 出验证包络（652,80,237，距离 ~115 格）**，
   不把 Bob 移回，调 `mc_view`：
   ```
   struct live2a01: knowledge=LAST_KNOWN freshness=RECENT
     summary={"baseline_cells": 4, "current_integrity":
       {"expected": 4, "knowledge": "LAST_KNOWN", "matched": 4, "missing": 0,
        "verified_game_time": 57874, "wrong": 0}}
   ```

## 判定

- structure still known（durable：object_id/role/baseline_cells=4 仍在）✓
- `current_integrity.knowledge != VERIFIED_LIVE`（=LAST_KNOWN）✓
- `freshness != LIVE`（=RECENT，按上次合法验证时刻老化）✓
- **远程刚修改的 missing 没有泄漏**：显示的 missing=0 是上次合法验证的旧值
  （真实当前 missing=1，见第 4 步），verified_game_time 明确标注数字来源时刻 ✓
- 判定路径未读取任何远程 cell（BOUND-2：粗筛外直接走缓存/UNKNOWN 分支，
  由 source-contract `cognitiveInspectIsLazyAndStaysInsideEvidenceScope` 与
  GameTest `mc2a01RemoteStructureNeverClaimsLiveIntegrity` 锁定）

4. **Bob 回到合法可观察区域（tp 572,63,157），等待验证缓存窗（20t）过期后 `mc_view`**：
   ```
   struct live2a01: knowledge=VERIFIED_LIVE freshness=LIVE
     summary={"baseline_cells": 4, "current_integrity":
       {"expected": 4, "knowledge": "VERIFIED_LIVE", "matched": 3, "missing": 1, "wrong": 0}}
   ```
   远程期间的破坏在回到包络后作为 missing=1 合法进入 LIVE ✓（STR-3）

## 结论：PASS
