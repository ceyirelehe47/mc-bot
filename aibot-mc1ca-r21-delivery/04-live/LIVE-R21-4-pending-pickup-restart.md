# LIVE-R21-4｜MINED_PENDING_PICKUP 持久化 + restart recovery —— PASS

环境同 LIVE-R21-1。fixture：石柱内嵌 iron_ore@(22,115,6)，Bob 在旁，铁镐在手。

## 步骤与结果

1. **ACTIONABLE ore**：observe 记录 `ore_9c3e86fbc8cf3d57bd12bd20f5ad50f4 … ACTIONABLE`。
2. **真实挖掉**：`mc_mine_opportunity` 启动真实挖掘（无 teleport/setblock）。
3. **pickup 未完成 → pending（关键修复点）**：execution
   `763d678f` → **failed / `known_resource_pickup_pending_recovery`**；
   registry 状态 **`MINED_PENDING_PICKUP` / `pickup_recovery_pending`**。
   R2 语义下此路径会 `markOpportunityConsumed` 直接销账（资源丢失且无处找回），
   R2.1 改为保留恢复义务。
4. **持久化验证**：直接读 `world_r2/aibot/external-semantics-bob.json`，
   该机会 `status=MINED_PENDING_PICKUP, blocked_reason=pickup_recovery_pending`。
5. **stop/restart**：完整停服 → 重启（bridge 重新绑定 8765）→
   observe 复核同 id **仍为 MINED_PENDING_PICKUP**（跨 restart 存活，未被无条件升级
   ACTIONABLE，也未被 GC）。
6. **recovery 只拾取不重挖**：把 Bob 移到矿石原位旁并让它自然拾取；
   `mc_mine_opportunity(id)` 走 PICKUP_RECOVERY 相位
   （日志 `task=mine_known_resource/PICKUP_RECOVERY`），
   **未再启动 MiningController 破坏已空的矿位**；raw_iron 背包计数增加，
   机会销账（observe 列表消失）。
7. **保守失败语义（已实测）**：当掉落物确实不可达/不存在时，
   `known_resource_pickup_lost_drop_despawned_or_taken`（typed），
   **不伪装 collected**；`known_resource_pickup_recovery_timeout` 为可重试结局
   （pending 保持，后续可再调同一 id）。

## 状态机（生产代码）

`ACTIONABLE | BLOCKED | UNREACHABLE | MINED_PENDING_PICKUP`（blocked_reason:
`insufficient_tool` / `no_reachable_work_pose` / `pickup_recovery_pending`）；
消费仅由 inventory delta / stale 终态触发。

## 证据

- executions：`763d678f`（pending failed）、recovery 走 PICKUP_RECOVERY 相位日志
- registry 中间态：本目录 registry-final-state.json 同世界快照；
  过程中直接读文件确认 MINED_PENDING_PICKUP 持久化
- 服务器日志：server-r21-live.log（`task_phase=PICKUP_RECOVERY` 诊断行）
