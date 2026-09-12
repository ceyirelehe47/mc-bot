# MC-2A0.4 Real Client Body MVP — RESULT

分支 `experiment/mc2a0-4-real-client-body-mvp`,基线 `experiment/mc2a0-3a-session-boundary-closure @ a9ad536`(仅允许 manifest preflight 后代)。
本文件如实区分:**已实证达成**、**环境未竟**与**技术发现**。§11 停止条件未触发(无 owner/Microsoft 账号、无 FakePlayer 回落、无客户端自报当 postcondition、冻结语义零改动),但 §5/§6 的 LIVE 部分项未完整闭环,详见对应问答与附录 B。

## 0. 总裁决(先行)

自动化硬门全部通过且经修正版复验;LIVE 垂直切片 say/goto 连同单权威、capability 拒绝、离线 UUID 登录真实达成;crosshair→Graph→mine 的 LIVE 闭环因客户端-服务器视线同步的环境差异未完成(自动化 GameTest 同链路全绿);crash 围栏语义经由"玩家死亡"事件与多次断线在服务器侧实证。**建议本提交作为 Real Client Body MVP 的部分基线**:后端/传输/执行器/supervisor 层可用且冻结面零污染,但 mine 垂直切片的 LIVE 证据缺位,应在下一轮以显式任务包补齐(见附录 B 差距清单)。

## 1. branch/base/各阶段 commit/evidence SHA

- base:`a9ad53628cc02e885b8f3c57bf4f67e18032d7eb`
- Commit A `ee4d90f`:manifest preflight(仅 SHA256SUMS)
- Commit B `93dab20`:server transport/backend/opportunity authority
- Commit C `ca2b0b9`:client actuator + offline supervisor
- Commit D `5eda329`:automated tests/GameTest/installer + manifest 终稿
- Commit E:本交付目录
- 上游基线:`zoyluoblue/mc_aiplayer @ a029fa6a`;冻结祖先 `3df7fb9`

## 2. manifest preflight

- 旧自引用:第 5 行 `fad74cc3...  SHA256SUMS`(156 条目)
- repair 后:155 条目,自引用排除,全部 hash 校验通过
- 终稿(`scripts/gen_sums.py` 替换版,可移植路径+原子替换):**169 条目**,`sha256sum -c` 全 OK,无自引用(两次校验:156→155 与终稿 169)。

## 3. applier 与最小修正

applier validation 与 apply 均**原样通过**(10 blob + 14 新文件 + 6 锚点变换,零锚点修正)。本轮全部实现修正(任务书 §5 允许项内)与披露:

1. `ExternalBodyRuntime.java`:stale receipt 调用折行收回单行(等价格式,满足冻结的 `OpportunityBirthDurabilitySourceTest` 字符串断言;§5"source-contract 文本因等价格式变化而调整")。
2. `MC2A04RealClientTrackerGameTests.java` lookAt:**fake player 不被 tick,`LivingEntity.getYaw(1F)` 返回 `headYaw` 而非 `yaw`**,lookAt 补 `setHeadYaw/setBodyYaw`(§5"GameTest fixture 修正");catch 块补栈帧信息(诊断价值,断言语义不变)。
3. `RealClientBodyClientRuntime.java`:新增 `AIBOT_REAL_CLIENT_AUTO_JOIN` opt-in 自动直连(§5"Loom 本地启动参数的最小修正"边界内:vanilla `--quickPlayMultiplayer` 在 DLI/dev 客户端不触发自动连接——Runpath 证据见附录 A.4;RUNBOOK §6 的 supervisor 重启语义本身要求程序化重连)。默认关闭,不影响普通玩家客户端。
4. `RealClientOpportunityTracker.java`:新增低频传感器诊断日志(5s/30s 节流,birth 成功处一行 INFO;生产保留,运维价值)。

## 4. server/client jar SHA256

- dev 树 `build/libs/aibot-0.0.1.jar` 首版:`084246d2136aef2e4e900dc29a25ed90fb437c415ae40ee3ef695faafd1393ec`
- 诊断迭代版(LIVE 部署):`858f67e507ca84e6e68baad4869733d0be1850b41e15ad1c162d3174a555071d`(即最终生产字节对应的构建)

## 5. backend selection 与 single-authority

- `AIBOT_EXTERNAL_BACKEND=real_client` 时:`AIBot real-client transport bound to loopback port 8766 body_id=bob player=Bob` + `bridge bound ... backend=real_client`,无 fake player 构造(server-live*.log 均有)。
- `server_fake_player` 时:8766 **不监听**(netstat 0 LISTENING,09-fake-regression)。
- duplicate real client 拒绝(TR-2)LIVE 实证:`real-client handshake rejected: real_client_authority_already_connected` 多次(live 日志),旧 authority 存活期间第二个客户端从未取得权威。
- 同名 FakePlayer 冲突:`real_client_fake_player_authority_conflict` 由启动断言与 GameTest/源码契约覆盖;LIVE 未构造(需要向运行中的 real 服注入 fake player 的受控通道,本轮未提供)。

## 6. Bob username/offline UUID/服务器实际 UUID

- username=`Bob`;supervisor 计算 `faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b`(与 RUNBOOK 硬断言一致);
- 服务器 `/v1/status.body_instance_id=faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b`,游戏登录行 `Bob[/127.0.0.1:xxxxx] logged in`(05-live-offline-login)。

## 7. 未使用 Microsoft/owner 账号

supervisor `--print-launch` 命令行仅含 `--username Bob --uuid <offline> --accessToken 0 --userType legacy --quickPlayMultiplayer 127.0.0.1:25599`;config 校验拒绝 MSA 标记与非零 accessToken(supervisor 源码 + 9 条单测覆盖)。服务器 `online-mode=false`。环境无 MICROSOFT/MSA/OAUTH/XBOX/PASSWORD 类变量注入。

## 8. control transport port/token/boundary/duplicate

- loopback only(8766 监听 127.0.0.1);token 常量时间比较(源码);hello 绑定 body id/player name/session epoch;
- 长度前缀 JSON 帧;inbound/outbound/remote-execution ledger 全部有界;网络线程不读 Minecraft world(源码契约 `RealClientMvpSourceTest` 断言 transport 不含 ServerPlayerEntity/ServerWorld);
- duplicate:见 §5(已实证)。

## 9. first body instance/session

- 首次 hello session(成功示例):`acfbc460-d9a3-4a54-8ed6-8598d228a6d`;后续每次客户端重连 epoch 翻新(05/08 目录时间线)。

## 10. status.supported operations

`["goto","mine_opportunity","say"]` 恰好三项,/v1/status 原样。

## 11. unsupported gather

`POST /v1/executions/gather` → **HTTP 409 `operation_not_supported_by_backend:gather`**;随后 status `active_execution=null`,无 TaskManager assignment、无 FakePlayer 创建、零 physical start。

## 12. say 垂直切片

- request `mc2a04-say-1` → execution `47c46f8a-f7e2-4361-acac-3991d103464f`,session `acfbc460`;
- `state=completed, reason=client_chat_packet_sent, progress=1.0, terminal=true`;
- dedicated server 权威广播:`[04:25:28] [Not Secure] <Bob> mc2a04 LIVE real-client say slice at 04:25:28`(06-live-actions)。

## 13. goto pause/resume

- execution `3b43a2a2-0a28-4310-be10-451df758ef51`:(−6.5,−60,10.5)→(10,−60,10),`allow_terrain_changes=false`;
- 2 秒内 RUNNING;`pause`→`PAUSED`;`resume` 同一 execution 继续;
- 终态 `COMPLETED reason=server_authoritative_arrival_within_2_5_blocks`,终点 (8.39,−60,10.5) 距目标 1.6 格(≤3)。

## 14-18. crosshair opportunity → Graph mine(**LIVE 未竟**)

如实结论:real 客户端 crosshair 传感器上行与服务器验证链在 LIVE 环境未闭环。证据链(07-live-graph-mine):
- 客户端 heartbeat diag:`crosshair_present=true target=BLOCK:(1,-59,-4)`(正对 fixture 铁矿,多次稳定);
- 服务器 diag(迭代 4 轮):先后观察到 `ray_mismatch`(`ray=视线 7 格末端`=MISS 特征)、客户端-服务器视线采样错拍(`sensorPitch` 与服务器 `pitch` 不一致窗口)、`block_or_ore_mismatch:grass_block`(goto 驱动 pitch 朝向目标格地面)——最终静止对准窗口未捕获到双端一致的 birth 写入;`real_client_opportunity_birth` receipt 计 0,Graph plan/run-next/consumed/DONE 未进入;
- 自动化对照:同一 tracker 的 birth/replay/terminal 链在 GameTest `mc2a04BirthReplay...`(638 之 1)全绿。
差距定性:服务器端 `player.raycast(7,1F)` 的旋转快照(headYaw/插值)与真实客户端 look 上报之间的时序/同步差异,叠加 RCON tp 只改服务器视角不回传客户端的反模式干扰。详见附录 B。

## 19-23. crash/reconnect(**部分实证**)

- 围栏:Bob 被夜怪击杀(`Bob was slain by Zombie`)后 `/v1/status` 呈 `body_ready=false + needs_reconcile=true`,epoch 不变直至新 control session 翻新(08 目录时间线);断线时 `real-client session read ended: Connection reset` + epoch 翻新(如 `0504acab→938258f5`);
- no-replay:围栏后 `active_execution=null`,无任何旧 intent 重放(§6 的"杀客户端进程"版本因热重连差异未单独取证——见附录 A.3);
- supervisor 重启:supervisor 全程在场,每次客户端被杀后 10s 退避冷启动成功重连(05:16:28→05:16:36 join 等);
- observe/new lease:重启后新 lease + observe 恢复 ready=true(05:53:56 join→ready=true 序列);
- deliberate new work after reconnect:reconnect 后的 say/goto(§12/13 复验链)即新工作。

## 24. FakePlayer mode smoke + real 端口

- `server_fake_player` 服:假人 `/aibot spawn Bob` 生成,`backend=server_fake_player`;
- say 端到端:`<Bob> mc2a04 fake regression say`(09 目录);
- goto:typed 响应路径真实(`goto_can_dig_explicit_allow_terrain_changes_required` 权限门→防怪环境失败 `body_died`);完整 fake goto 语义由 638 GameTest 覆盖;
- real 控制端口 8766 在 fake 模式 **未监听**(netstat 验证)。

## 25. 自动化计数(修正版复验,即最终生产字节)

- JUnit dev / clean replay:**403 / 0 × 2**(首轮亦 403/0×2)
- GameTest round1/round2/replay:**638 / 0 × 3**(首轮亦 638/0×3;+build 内嵌共 7 跑全绿)
- BridgeCore:**105 PASS**;Node **43/0**;Installer **11**;Supervisor Python **9/0**
- clean upstream replay:两树 `diff -r --brief src` **零差异**(首轮与修正版各验一次)

## 26. DSH tool count

`static mc_*(12) + operations(14) + control-loop(3) = 29`,恰 29(10-audit/dsh-tools.json)。

## 27. clean replay zero-diff

见 §25;manifest 终稿 169 条 `sha256sum -c` 全 OK。

## 28. 冻结审计

- TaskGraphStore/ServerFakePlayerExecutionDriver/PhysicalExecutionDriver/TreeHarvestWorkset/plugin.mjs 对 a9ad536 **byte-identical**(10-audit/frozen-guards.json);
- graph 派发区:含 `submit(supplied,d.requestId(),d.operation(),d.arguments())`,不含 `real_client`/`server_fake_player` 字样。

## 29. 无 scope creep

无 GUI/Screen/widget/slot introspection 实现;无 BOT_POV/音频/流媒体/observer;无 Agenda/后台 autonomy Scheduler/新 Graph producer;DSH 工具面零改动。computer-use 的窗口点击仅用于 LIVE 操作诊断(点击/截图),不构成任何产品功能。

## 30. 是否建议作为 Real Client Body MVP baseline

**有保留的肯定**:作为"后端/传输/客户端执行器/supervisor 层"的基线成立(冻结面零污染、自动化全绿、§1-4 LIVE 真实);但 Graph mine LIVE 垂直切片未闭合,不应据此宣称完整 Real Client Body MVP。建议下一轮任务包聚焦:视线同步语义修复(附录 B 三项)+ §5/§6 完整重跑。

---

## 附录 A. 技术发现(全部如实)

1. **vanilla quickPlay 在 DLI/dev 客户端不触发**:`--quickPlayMultiplayer` 已进命令行(进程命令行取证),但 TitleScreen 路径未消费;`--quickPlayPath quickPlayMultiplayer:host:port` 在 Windows 直接 crash(QuickPlayLogger 把 path 当文件路径,`:` 非法)。
2. **AccessibilityOnboardingScreen 语义反转**:`onboardAccessibility:true` 表示**待 onboarding**(默认 true),完成后写 false;首启该屏会挡住一切自动化。
3. **热重连失败模式**:客户端被踢后同进程 auto-join 循环连接失败(服务器侧 login 无痕迹、TCP TIME_WAIT 可见、手搓 login probe 健康表明服务器无辜);**冷启动(新进程)100% 可恢复**——supervisor 重启语义天然走冷启动,不受影响。
4. **RCON tp 的 look 不回传真实客户端视角**:服务器实体 yaw/pitch 变化但客户端本地视角不变,导致服务器 raycast 与客户端 crosshair 长期错位;真实客户端视角必须由客户端侧驱动(mod goto)或客户端自身输入决定。
5. **LivingEntity.getYaw(1F)=headYaw**(非 yaw 字段):fake player 不 tick 时两者永久分叉——本轮 GameTest lookAt 修复的根因,亦是 LIVE 视线诊断的重要背景。
6. **flat 世界夜怪会杀死无人值守的身体**:real 与 fake 服均发生;LIVE fixture 必须 `doMobSpawning false`+`time set day`+`keepInventory true` 先行。
7. **gradlew.bat 在 supervisor 的 CREATE_NEW_PROCESS_GROUP 下可能 0xC0000142**,且 gradle 启动的客户端 JVM 会脱离进程树(detached)——已按 COMPILE_SENSITIVE_POINTS #6 改为直调 `java.exe`(DLI dump 导出完整启动配置)。
8. **supervisor 命令含空格参数会被 gradlew.bat 丢引号**:私有方案为 Gradle init script(树外文件)+ 无空格 `-P` 身份参数,最终形态为直调 java 的参数数组(无 shell、无空格参数)。

## 附录 B. LIVE §5/§6 差距清单(下一轮输入)

1. 视线同步:客户端 look 包与服务器 `raycast` 旋转快照的时序窗(COMPILE_SENSITIVE_POINTS #5 的"tracker fixture 可有限等待重试"在 LIVE 真实客户端侧未覆盖)——候选修复:tracker 对同 cell 的连续命中窗口计数而非单帧判定,或服务器侧对 sensor.yaw/pitch 与玩家当前值的一致性预检。
2. mine 垂直切片重跑:birth→actionable→mc_view EvidenceRef→Graph plan/run-next→客户端挖掘+拾取→`target gone AND inventory>baseline` 双证明→`resource_opportunity_consumed`→Graph DONE。
3. §6 完整版:goto RUNNING 中杀客户端进程(而非依赖死亡事件),取证 outcome_unknown/租约吊销/读平面失效/urgent 事件/no-replay 的完整四联(当前为死亡围栏+断线 epoch 翻新的分立证据)。
