# R1 验收结论（AIBot + DSH M0 修复轮）

- 日期：2026-09-09 / 2026-09-10（夜间自主执行）
- 基线：`ceyirelehe47/mc-bot@f7dc7ba`（R1 补丁应用后本轮提交）
- 上游：`mc_aiplayer@a029fa6`（干净 checkout 重新应用 R1 安装器后重建）
- DSH：`deepseek-harness@5dda764`
- 结论标记仅使用 VERIFIED / FAILED / NOT_RUN。

## 0. R1 应用与附带修复

| 项 | 结论 | 证据 |
|---|---|---|
| R1 五文件补丁应用（installer/ExternalBodyAccess/ExternalBodyRuntime/events.mjs/events.test.mjs） | VERIFIED | `git show` 本提交；apply_r1 dry-run 全绿；`verify_r1_source.py` PASS |
| **附带修复：M0 提交的 installer 语法错误**（`''+'ACCESS'+''` 三引号拼接导致 `py_compile` 失败，M0 安装器在 CPython 下从未可 import） | VERIFIED | 修复为 `'''+ACCESS+'''`，`py_compile` 通过；生成 Java 与上游实际运行补丁逐字一致（ChatCaptureListener `ExternalBodyAccess.reserved(bot)` 全限定名） |
| **附带修复：`test_installers.py` 陈旧断言 `len(CHANGES)==10`**（实际 12，M0 期间从未在本机真实执行） | VERIFIED | 断言对齐 12，测试通过 |
| SHA256SUMS 重建（LF 行尾，121→N 项全量校验通过） | VERIFIED | `sha256sum -c` 0 失败 |

## 1. 离线套件

| 项 | 结论 | 证据 |
|---|---|---|
| R1 补丁单测（R1 包自带 5 项） | VERIFIED | `evidence/r1-patch-units.txt`（5/5） |
| 安装器单测 | VERIFIED | `evidence/installer-tests-r1.txt`（11/11 OK） |
| Java 桥核心测试（JDK21） | VERIFIED | `evidence/java-core-tests-r1.txt`（57 checks passed） |
| dsh-plugin node 测试（含 FakeBridgeServer HTTP 集成） | VERIFIED | `evidence/node-tests-r1.txt`（34/34 pass） |

## 2. 干净上游重建

| 项 | 结论 | 证据 |
|---|---|---|
| a029fa6 精确重置（`git status` 0 脏项）→ R1 安装器 dry-run（12 blobs）→ apply（12 改 9 新） | VERIFIED | `evidence/aibot-upstream-r1.patch`（unified diff 1346 行） |
| `./gradlew compileJava test`（JDK21） | VERIFIED | `evidence/gradle-r1-console.txt`（BUILD SUCCESSFUL 1m04s）；JUnit XML ×65 套件 0 失败 → `evidence/gradle-r1-junit-xml/` |
| `./gradlew runGameTest` | VERIFIED | `evidence/gametest-r1-console.txt`（`All 587 required tests passed`）；`evidence/gametest-r1.xml`（time=189.4s） |

## 3. 实机验收

环境：`mc-server-aibot` start.bat（AIBOT_EXTERNAL_BOT=Bob，桥 8765），DSH web + aibot-body 插件（R1 events.mjs 已同步），DSH 会话 `evidence/dsh-session-r1/session-final.v3.jsonl`（772 行，survival_alert×45、mc_connect×16）。

### A. critical hunger + 有食物 → SAFETY 抢占进食 — VERIFIED

- food 压到 6：`TASK event=task_assigned {name=eat, origin=SAFETY, origin_reason=critical_hunger}` + `DANGER event=hunger_eat_started {critical=true, food=6}`，多个周期（`evidence/server-r1-external-console.log` 23:53/23:54/23:59/00:00；`r1-acceptance-A-timeline.txt`）。
- Bob 完成吃饭：`task_completed {name=eat, elapsed_ticks=34}`，food 6→14/20 循环恢复；极端 amp255 压 0 时连续 ~15 个进食周期服务器不崩。
- 非紧急区间（food 14→7，低于 hungerEatThreshold=14 但 >critical 6）零 EatTask：普通 SYSTEM_BACKGROUND 进食未抢占。
- 外部任务被抢占与恢复：桥 `active_execution = {op:goto, state:paused, reason:safety_preempted, progress:0.15}`；SAFETY 结束后 `task_resumed`，goto 回到 running（0.17→0.24→0.30 直至完成）——`evidence/r1-acceptance-A2/A3/A4-timeline.txt`。
- 额外：spider 致伤后 `origin_reason=low_health_heal` 的 heal-eat 同样放行（urgent 双路径均生效）。

### B. critical hunger + 无食物 → 不自主狩猎、发告警 — VERIFIED

- 清空全部食物（含腐肉）后 food=0 持续 ~70s：**零 task_assigned**（无 HuntTask / ResupplyTask / 任何自主任务）——`evidence/r1-acceptance-B-timeline.txt`。
- `survival_alert` 事件流按 200 tick 去重持续发布：`/v1/events` seq 293-308，payload `{reason: critical_hunger_no_food, food:0, health:9.13, action: observe_and_replan}`。
- DSH 被唤醒：会话 UI 多次 `上下文注入 aibot-body Minecraft: survival_alert`，agent observe 后明确 "HP is stable at 9.13, food 0 … opting to replan"（idle→followup 语义）。
- 恢复食物后 `origin_reason=low_health_heal` SAFETY 进食立即介入救回（hp 9.1→回升）。

### C. 玩家 provenance（三渠道） — NOT_RUN

- 服务器在线玩家仅 Bob（fake player）；无人类客户端。`ChatCaptureListener` 挂在 Fabric `ServerMessageEvents.CHAT_MESSAGE`（仅真实客户端聊天管线触发）；AIBot panel chat 需要 mod 客户端面板。三个入口（chat:plain / chat:@bot / network:panel_chat）本轮均无法真实触发。
- 负面断言（可验证部分）：R1 运行时事件流（seq≥218）中 `authorized_player_control` 出现 0 次；服务器日志 0 次。旧标签仅存在于 M0 时代 journal 重放（seq 8-111，历史数据，非 R1 行为）。
- 静态断言：`verify_r1_source.py` PASS（sender_uuid/authorized_control/channel 字段均在）。
- 待真人玩家（skyline_cc）下次开服时补跑。

### D. active Mission restore — VERIFIED

- 时序（`evidence/server-r1-restore2-console.log` 00:29:54-57）：
  1. `bot_restored`（注入的 active Mission `have_item[cobblestone×16]` 快照恢复，无 `external_body_reserved_use_dsh_bridge` 异常，全日志该串出现 0 次）；
  2. `server_runtime_ready`；
  3. 恢复的 Mission 实际重新执行：`task_assigned {origin=MISSION, name=dig_down}`、`goal_result {goal=HaveItem[cobblestone,16], matched=8/16}`；
  4. startup fence 清 legacy：`goal_result status=CANCELLED reason=external_mode_enter` + `task_cancelled {dig_down, cancelled:external_mode_enter}` + `intent_cancelled {origin=SYSTEM, reason=external_mode_enter, scope=ALL}`；
  5. 之后 `external-body bridge bound to port 8765`（HTTP endpoint 开放在 fence 之后）。
- restore 后旧脑零活动（后续唯一 task_assigned 无；`goal=none`），DSH 自动重连后 `needs_reconcile=false`、observe 干净（hp20/food17、无 active execution）。
- Mission 快照说明：内部脑 LLM（commandcode relay）真实驱动 goal→任务后，Mission 队列仍未产生 ActivePlan 快照，故按 persistence schema（`MissionRuntimeRecord/MissionRecord/MissionSpec`）手工注入合法 active Mission 快照以确定性覆盖 restore→assign() 路径（`evidence/runtime-before-mission-inject.json` 为注入前备份）。这是对真实内部存档的结构等价模拟，非改代码。

### E. pause/resume/cancel/stale 隔离 — VERIFIED

- `execute A → pause → resume → cancel`：
  - A（gather cherry_log，id `4ebebe42…`）秒级完成 → 对 A 的 pause/resume 均返回 `already_terminal`（幂等拒绝，不复活终态执行）。
  - B（gather dirt，id `594e8504…`）：running 中 `mc_pause` 命中 → 桥 `state=paused progress=0.75`、服务器 `mission_user_paused {why=external…}` + `intent_paused`；保持 ~4 分钟；`mc_resume` → `task_resumed {origin=LLM_TOOL}` + `intent_resumed`，0.75→0.88 继续推进；随后任务自身 pickup_timeout 终态 failed（玩法失败，非控制失败）；`mc_cancel` 对终态幂等。—— `evidence/r1-acceptance-E5/E6-timeline.txt`。
- 迟到命令隔离：B 运行期间对已终态 A 的 `mc_status`/`mc_resume` 只返回终态信息，B 不受影响继续推进（0.25→0.88）。
- 用户暂停期间 SAFETY 仍自卫：`mc_release`（释放控制=暂停普通作业，control_active=false）后召唤苦力怕 → `DANGER event=creeper_defense_started`（00:58:39）照常触发。

## 4. 未尽事项

- C 项三渠道实机 provenance：NOT_RUN，待真人玩家在线补测（预计 10 分钟内可完成）。
- D 项 Mission 快照为 schema 等价手工注入（内部脑 ActivePlan 产生路径未在无头环境跑通，LLM 已配 relay 可用）。
- M0 交付中的 `evidence/aibot-external-body-m0.patch`、`aibot-dsh-m0-delivery/` 为历史快照，仍含旧 `authorized_player_control` 行为记录，未改写（历史证据不可变）；当前行为的 authority 是 `scripts/apply_to_aibot.py` + 本轮 `evidence/aibot-upstream-r1.patch`。

## 5. 基础设施改动记录（本机，不入库）

- `mc-server-aibot/config/aibot.json`：内部脑 deepseek 配置接 commandcode relay（apiKey 为本机私钥，不入库；改前备份 `evidence/aibot-config-backup.json`）。
- 新增 `mc-server-aibot/start-internal.bat`（无桥环境变量启动，D 项用）。
- 验收后已停服并清空 25565/8765/3080 端口。
