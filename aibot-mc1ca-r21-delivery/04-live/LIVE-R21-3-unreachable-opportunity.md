# LIVE-R21-3｜Zombie opportunity 降级 + revalidation —— PASS

环境同 LIVE-R21-1。fixture：开阔地立 6 格石柱，内嵌 coal_ore@(12,116,6)，
Bob 站在 (12.5,113,12.5)，镐在手（机会 ACTIONABLE）。

## 步骤与结果

1. **resident perception 记录机会**：observe 列出
   `ore_863ff8a2003930e7b174c0e1cd027b2d … ACTIONABLE`（非 BLOCKED）。
2. **`mc_mine_opportunity(id)`** → execution `f3d9ba0c` →
   **failed / `known_resource_unreachable:no_reachable_work_pose`**
   （typed，R2 时是 `known_resource_visible_but_not_reachable` 且 registry 不动）。
3. **registry 状态降级**：observe 复核 `status=UNREACHABLE`、
   `blocked_reason=no_reachable_work_pose`；持久化文件同样记录（跨 observe 稳定）。
4. **重复调用拒绝（无僵尸）**：立即再调同一 id → 409 语义
   `resource_opportunity_unreachable:no_reachable_work_pose:revalidation_requires_geometry_change_or_cooldown`
   —— 不再反复诱导同一失败。
5. **无事件风暴**：连续 3 次 observe + 事件流水检查，UNREACHABLE 稳定；
   期间未产生新的 `resource_opportunity_actionable`（计数与位置过滤核对）。
6. **几何改变后回 ACTIONABLE（bounded revalidation）**：
   - 在 (12,115,5) 放置工作位石块并把 Bob 移到 (12.5,116,5.5)（与判定位置距离 > 6）；
   - `mc_mine_opportunity` 同 id → **completed**：bot 真实破坏 coal_ore、
     拾取 coal×1 入包；(12,116,6) 已为 air；机会销账（observe 列表消失）。
7. **未重挖验证**：整个过程只破坏目标矿石一次；`mining_idle` 恢复后无二次采矿动作。

## 语义要点

- UNREACHABLE 非终态：移动 > 6 格或 2400 game-tick 冷却后允许 revalidation；
  重估仍走 `ToolTier` 能力检查，恢复 ACTIONABLE 时**只**推送一次 actionable 事件。
- 复用观察的 last_seen 仍在刷新，但状态重估受 revalidation gate 节流，不 wake DSH。

## 证据

- executions：`f3d9ba0c`（unreachable failed）、`75c55f82`（revalidation completed）
- 服务器日志：server-r21-live.log
- 现场探测：`execute if block 12 116 6 air` 命中；observe inventory coal=1
