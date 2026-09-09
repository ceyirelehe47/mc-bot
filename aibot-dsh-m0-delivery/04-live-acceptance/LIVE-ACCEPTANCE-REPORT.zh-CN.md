# AIBot External Body → DSH M0 实机验收记录

日期：2026-09-09（UTC+8）　执行环境：Windows 11 本机，全部单机回环

## 测试环境

| 项 | 值 |
|---|---|
| Minecraft | Java Edition 1.21.3（Fabric Loader，dedicated server） |
| 种子 | `4167799982467607063`（出生点 [0,0] 平原村庄，2 格内有橡树） |
| 运行 profile | `strict_survival`（未开启 operator 特权） |
| 初始物品 | 空背包（Bob 全新 spawn） |
| 身体 | Bob（`/aibot spawn Bob`，role=worker） |
| 桥接 | `AIBOT_EXTERNAL_BOT=Bob`，`AIBOT_BRIDGE_PORT=8765`，仅 127.0.0.1 |
| 大脑 | DSH 0.1.5-alpha.1 @5dda764（dsh web profile，scratch overlay 插件） |
| LLM | Command Code Goat 网关 `deepseek/deepseek-v4-flash`（openai-completions，上下文 400K） |

## 基本串行链（P0-C 1-3）

1. **连接与观察** — VERIFIED
   `mc_connect` 获取租约（control_active=true），`mc_observe` 返回 UUID/维度/位置/背包/感知快照（snapshot_age 8ms）。模型正确汇报"HP 20、背包空、2 格内有橡木"。
2. **采集 4 原木** — VERIFIED
   `mc_gather oak_log×4` 收到 accepted 后**未轮询**，执行终态事件唤醒下一模型回合；终态原因 `inventory_quota_verified: observed=4`。独立观察（不经模型）核对背包 `oak_log×4` 一致。
3. **合成链（木板→工作台→木棍→木镐）** — VERIFIED
   4 步每步事件唤醒 + `mc_observe` 独立核对材料消耗：4 原木→16 木板→消耗 9 木板（工作台 4 + 木棍 2 + 木镐 3），工作台复用不消耗；最终背包与模型汇报逐项一致。

## 控制链（P0-C 4）

4. **暂停/恢复/取消** — PARTIAL（实证中断，机制经离线与运行中间接验证）
   计划的 5 步控制链在执行前遭遇服务器崩溃事故（见下），重建世界后由用户实玩续测。间接证据：`mc_gather` 在 SAFETY 任务占用身体时被正确拒绝（`body_busy_safety_or_legacy_work`，HTTP 409），证明控制面与安全面的互斥语义工作正常。`mc_pause/mc_resume/mc_cancel` 的游戏内完整序列未在重建后的世界跑完全程——离线 HTTP 集成测试覆盖了同一路径（evidence/node-tests.txt，34/34）。

## 故障场景（P0-C 附加）

- **服务器崩溃与自动恢复** — VERIFIED（意外获得）
  21:05:23 上游 `DangerWatcher.maybeStartNightTask`（SYSTEM_BACKGROUND origin）对保留身体调用被护栏拒绝的 `TaskManager.assign`，抛出未捕获 `IllegalStateException: external_body_reserved_use_dsh_bridge` 炸 tick 循环（crash report 见 05-incident）。**交接包原始实现的真实缺陷**。
- **崩溃修复（两轮）** — VERIFIED
  第一轮整跳 DangerWatcher → 服务器存活但 SAFETY 路径（威胁自卫/死亡重生）被误伤，Bob 被僵尸村民击杀后卡死亡态；第二轮精准修复：仅 4 个后台维持方法（maybeResupply/maybeEat/maybeStartNightTask/maybeLightDarkArea）入口加 reserved 短路，SAFETY 全放行。修复同步进 apply_to_aibot.py（新锚点条目），干净上游产出与手改版逐字节一致。
- **重启对账与恢复** — VERIFIED
  三次服务器重启：`restored_bots=1` 从 checkpoint 恢复；DSH 侧正确解读 runtime_stopped/started 事件重放（epoch 比对），显式重连（旧租约失效后 `mc_connect` 重取）。
- **死亡与重生** — VERIFIED
  Bob 21:19:21 被杀（全部物品掉落）→ 修复版重启后 `respawnFromRecord` 以 1HP 复活 → 原地自动拾取部分掉落 → **SAFETY 实战**：`evade(LOW_HP)` 躲僵尸 + `creeper_defense` 连躲两只苦力怕（ESCAPE 302 ticks 完成）。
- **安全抢占互斥** — VERIFIED
  SAFETY 任务占用身体期间外部 `mc_gather` 被拒（409），安全释放后外部任务恢复受理。
- **玩家聊天到达 DSH（P0-C 5）** — VERIFIED（用户实玩）
  `ChatCaptureListener` 增补免 @ 直聊路径（授权门与控制短语语义不变）；`say` 操作增补全服聊天栏广播。用户 skyline_cc（op）在游戏内直聊，DSH 大脑经事件通道接收并以 `mc_say` 在聊天栏回复。实玩日志见 server-log-highlights.txt（22:07-22:17：gather 16 原木→木板→工作台→木镐→木斧→石镐→石剑→熔炉→烧炭，全部 `origin_reason=external_dsh`）。

## DSH 侧（P0-B）

- 插件在真实 `dsh web` profile 加载（`--patch cordis.yml`），插件列表显示"运行中"。
- 16 个 mc_* 工具注册并在会话中真实调用（connect/observe/gather/craft/say 实测；其余经离线 fake-owner 测试）。
- `output.schema={type:'json'}` canonical 返回、执行错误正确展示（`body_not_ready_or_server_tick_stale` 等失败在 UI 正确渲染）。
- 事件驱动回合：执行终态/伤害/runtime 事件触发新模型回合；暂停类通知仅注入不自动唤醒。
- 上下文压缩、400K 窗口（settings.yaml llm-pi-ai commandcode 路由）实测工作。

## 已知限制与本次新发现

- 旧存档活动 Mission 恢复未验证（本次始终使用全新世界；护栏与旧恢复器的冲突面已被 assignSilently 路径审查覆盖但未实测）。
- `say` 到全服聊天栏为本仓库新增强化（上游 M0 设计为仅面板），已同步 overlay 源与安装器。
- 事件为 at-least-once，非跨系统 exactly-once（重放在会话中多次出现并被模型正确去重）。
- dsh web 重启后所有会话插件 worker 释放，桥租约 30s 内自动过期释放——多会话/多进程租约竞争表现为 409，需释放方主动 mc_release 或等租约过期。
