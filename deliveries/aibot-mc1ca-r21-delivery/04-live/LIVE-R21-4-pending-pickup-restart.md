# LIVE-R21-4｜MINED_PENDING_PICKUP 持久化 + restart recovery —— PASS

环境同 LIVE-R21-1（复验轮日志 `server-r21-live-rerun.log`）。

## 步骤与结果

1. **ACTIONABLE ore**：fixture（石柱内嵌 iron_ore@(52,116,6)）被 perception 记录为
   `ore_54b7d886a09038af93f225a2403bf314 … ACTIONABLE`（铁镐在手）。
2. **真实挖掉**：`mc_mine_opportunity` 启动真实挖掘（无 teleport/setblock 协助），
   ore 变为 air（`execute if block 52 116 6 air` 命中）。
3. **pickup 未完成 → pending**：execution `eb436008` →
   **failed / `known_resource_pickup_pending_recovery`**；
   registry 变为 **`MINED_PENDING_PICKUP` / `pickup_recovery_pending`**，
   并记录 `pickup_baseline`（本次 0）。
   R2 语义下此路径会 `markOpportunityConsumed` 直接销账（资源丢失），R2.1 保留恢复义务。
4. **持久化 + stop/restart**：直接读 `world_r2/aibot/external-semantics-bob.json` 确认
   `("MINED_PENDING_PICKUP", "pickup_recovery_pending", pickup_baseline=0)`；
   完整停服 → 重启（bridge 重新绑定 8765）→ 复核同 id **仍为 MINED_PENDING_PICKUP**
   （跨 restart 存活，未被升级 ACTIONABLE，也未被淘汰）。
5. **recovery 成功销账（验收关键门 R21-B5）**：
   - 构造"拾取发生在 recovery 启动之前"的真实时序：在矿石原位上方
     `summon item minecraft:raw_iron`（**测试 fixture 制造掉落物**，非自然产出），
     让 Bob 走到该处由 vanilla 自动拾取完成（背包 raw_iron 0 → 3），此刻机会仍是 pending；
   - 调 `mc_mine_opportunity(id)` → execution `cc52989c` →
     **completed**，机会销账（observe 列表消失）；
   - 原始日志证据：
     `ACTION event=known_resource_collected bot=Bob {mode=pickup_recovery, opportunity=ore_54b7…, count=3}`；
   - **未重新挖掘**：该恢复 execution（`cc52989c`，1 tick 完成）内
     **0 次 `mine_start` / `mine_complete`**，ore 位保持 air。
     （说明：同一场次稍后为另一颗 coal ore 走的 APPROACH 探索会产生独立的 mine 事件，
     与本恢复路径无关；逐 execution 核对。）
6. **不重挖 + 保守失败语义（负面路径）**：另一颗 coal ore 在 revalidation 后真实挖掉、
   掉落物因远离超时 → 同样进入 MINED_PENDING_PICKUP；随后掉落物自然消失（背包 coal=0 且
   无掉落实体）→ recovery 报 typed `known_resource_pickup_lost_drop_despawned_or_taken`，
   **不伪装 collected**。

## 验收修复（本轮独立验收发现的缺陷）

首轮恢复实现把 inventory 基线取在 `onStart`（恢复启动时刻），
而 vanilla 自动拾取常在"pending 判定 → recovery 启动"之间完成——
该基线下 delta 被吞掉，资源已入包却被误报 `lost_drop` 并错误终态化。
**修复**：pending 状态持久化 `pickup_baseline`（矿石被破坏时刻的 accepted 计数），
恢复模式以该基线计算 delta；并把 drop-absence 判定窗口放宽到 200t、搜索半径 16 格
（`DROP_SEARCH_RADIUS`），另加"走向可见掉落物"的接近步骤。
GameTest 回归 `r21PickupRecoveryHonoursDropCollectedBeforeRecoveryStart` 锁定该场景。

## 状态机（生产代码）

`ACTIONABLE | BLOCKED | UNREACHABLE | MINED_PENDING_PICKUP`
（blocked_reason: `insufficient_tool` / `no_reachable_work_pose` / `pickup_recovery_pending`）；
消费仅由 inventory delta 成立或 stale 终态触发；pending 条目不会被容量淘汰选中。

## 独立复核后的加固（跟进轮 `server-r21-live-followup.log`）

第二轮独立复核指出原实现的两个过保守/越权点，已修复并实测：

1. **接近路线越权**：原"走向掉落物"用裸 `startPathTo(dropPos)`（可挖可搭、无终点校验），
   改为既有保守实现 `HarvestCore.chaseDropAnyOf`（exact surface movement，
   不挖不搭、只接近物理支撑的掉落物）。
2. **"不可观察"≠"已消失"**：strict LOS 判定下，被地形遮挡的掉落物仍存在（vanilla ~6000t 才消失）。
   现改为：不可观察 200t 后先返回**可重试**的 `known_resource_pickup_recovery_drop_not_found`
   （pending 保留）；仅当 pending 已存在超过 6000t 才终态化为 lost。
3. **未知基线不再误报**：legacy registry（无 `pickup_baseline` 字段）加载为 `-1`（未知哨兵），
   恢复时回退到当前计数——只会少认、绝不虚报成功；
   回归测试 `r21LegacyPendingWithoutBaselineNeverOverClaims` 锁定。

跟进轮实测（本目录 `server-r21-live-followup.log`）：
pending 持久化 `pickup_baseline=4` → restart → summon 掉落物（raw_iron 3）→
`mc_mine_opportunity` → **completed**，原始行
`ACTION event=known_resource_collected bot=Bob {opportunity=ore_54b7…, count=2, mode=pickup_recovery}`，
该轮 `mine_start` 计数 0（未重挖）、机会销账。

## 证据

- executions：`eb436008`（pending failed）、`cc52989c`（首轮 recovery completed）、
  `43515206`（跟进轮 recovery completed）
- 日志：`server-r21-live-rerun.log`（首轮）、`server-r21-live-followup.log`（跟进轮）
- registry：`registry-final-state.json`（跟进轮结束时快照）
