# LIVE-R21-3｜Zombie opportunity 降级 + revalidation —— PASS

环境同 LIVE-R21-1。fixture（复验轮）：开阔地 6 格石柱内嵌 coal_ore@(62,116,6)，
Bob 站在 (62,113,12.5)，铁镐在手（机会 ACTIONABLE）。

## 步骤与结果

1. **resident perception 记录机会**：observe 列出
   `ore_0b73bbcaf6ca3fd5bc2058dd34dcc750 … ACTIONABLE`。
2. **`mc_mine_opportunity(id)`** → execution `1ffeb7f7` →
   **failed / `known_resource_unreachable:no_reachable_work_pose`**（typed）。
3. **registry 降级**：observe 复核 `status=UNREACHABLE`、
   `blocked_reason=no_reachable_work_pose`。
4. **重复调用拒绝（无僵尸）**：立即再调同一 id → 409 语义
   `resource_opportunity_unreachable:no_reachable_work_pose:revalidation_requires_geometry_change_or_cooldown`。
5. **无事件风暴**：连续 observe 期间 UNREACHABLE 稳定，未产生新的
   `resource_opportunity_actionable`。
6. **几何改变后 revalidation → 真实挖掘**：
   - 在 (62,115,5) 放置工作位石块并把 Bob 移到 (62.5,116,5.5)（相对判定位置 > 6 格）；
   - 同 id `mc_mine_opportunity` → execution `718ca9e4`：**真实破坏 coal_ore**
     （`execute if block 62 116 6 air` 命中），机会离开 UNREACHABLE；
   - 掉落物因 Bob 站位偏远在 120t 内未入包 → 按 R2.1 语义转入 MINED_PENDING_PICKUP
     （**不早销账**）；随后掉落物自然消失，recovery 报
     `known_resource_pickup_lost_drop_despawned_or_taken`（保守失败，不伪造 collected）。
   - 该负面路径与 LIVE-R21-4 的成功路径共同覆盖 B4/B5/B6。

## 语义要点

- UNREACHABLE 非终态：移动 > 6 格或 2400 game-tick 冷却后允许 revalidation；
  重估仍走 `ToolTier` 能力检查，恢复 ACTIONABLE 时**只**推送一次 actionable 事件。
- `markOpportunityUnreachable` / `markOpportunityStale` 均写 registry（持久化）+
  typed reason；stale 另发 `resource_opportunity_stale` tombstone 事件。

## 证据

- executions：`1ffeb7f7`（unreachable failed）、`718ca9e4`（revalidation 后真实挖矿）
- 日志：`server-r21-live-rerun.log`
- 现场探测：`execute if block 62 116 6 air` 命中
