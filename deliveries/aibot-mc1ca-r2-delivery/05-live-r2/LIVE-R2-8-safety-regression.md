# LIVE-R2-8｜R1 SAFETY 回归 —— PASS（GameTest 回归）+ 实机触发 NOT_RUN（工具限制，如实记录）

## 判据处理
任务要求"重复 critical hunger + food / critical hunger + no food；真人 provenance 可 NOT_RUN"。

## 已有证据
1. **600/600 GameTest 全过**（build/test-results/gametest/TEST-aibot-gametest.xml，已归档 03-gametest/）——其中包含 R1 轮 P0-A 修复的 SAFETY EatTask 语义（critical_hunger 保留 EatTask、无食物发 survival_alert 不自主狩猎）的确定性测试
2. **R2 未触及 SAFETY 代码**：apply_r2_to_mc_bot.py 的全部 patch 面为 MiningController/BlockMiner/FarmTask/GatherQuotaTask/PerceptionCollector/Backend/Kernel/plugin——无一涉及 EatTask/SAFETY 调度
3. 上一轮（MC-1CA，同代码基线 R1+MC1CA）实机 survival_alert critical_hunger_no_food → observe_and_replan 链条曾完整实证（见上轮 RESULT）

## 实机触发 NOT_RUN 原因（工具性限制）
- `effect give Bob minecraft:hunger 120 3`（饥饿 IV）对 Fake ServerPlayer 无效：food 恒 20（饥饿效果不驱动 fake player 的 foodManager）
- `data modify entity Bob foodLevel set value 4` → "Unable to modify player data"（player NBT 不可写）
- 自然饥饿需小时级活动量（上轮为多小时会话累计），本轮预算内不可达
