# RESULT — MC-2A0.4A Real Client MVP Closure（2026-09-13）

裁决：**PASS**。四个剩余硬门（同帧 sensor 一致性、游戏连接 incarnation、真实 Graph mine 全链、RUNNING 强杀进程树围栏）全部以原始日志/journal/HTTP 响应实证关闭。以下逐项对应 TASKBOOK §12。

## 1. 身份与提交

| # | 问题 | 答案 |
|---|---|---|
| 1 | exact base / branch / final SHA | base `34fb49f779d8981c55680e83016ff9e782a2b73b`，branch `experiment/mc2a0-4a-real-client-mvp-closure`，final SHA 见 `00-meta/final-sha.txt`（本文件随最终提交写入） |
| 2 | 生产源码 commit 与 delivery-only commit | 生产源码 `a01f2e4`（wire v2+同帧重建+final-facing+测试）；LIVE 修正与交付证据 `aeaede5`（auto-join 白名单修正+全部交付证据，见 git log）；最终 manifest/RESULT 为 Commit C |
| 3 | wire protocol 是否变化、为何 | 是，v1→v2。原因与消息增量见 `01-diff/wire-protocol-v2.md`：帧身份/游戏会话绑定/同帧视线；混合二进制握手即拒（`RealClientWireSessionTest.protocolV1HelloIsRejectedFailClosed`，WIRE-1） |

## 2. 游戏会话与传感器（SESS-1/2/3、SENS-1..5）

| # | 问题 | 答案 |
|---|---|---|
| 4 | game-session epoch 如何生成/轮换 | 客户端 `ClientPlayConnectionEvents.JOIN` 时生成 UUID 并递增 `gameSessionSeq`，帧序号清零（`RealClientBodyClientRuntime.gameSessionStarted`）；心跳/执行状态统一携带；控制 TCP epoch 仅作传输层身份 |
| 5 | 同 UUID 重连是否必换 epoch | 是。LIVE 实证四代 epoch：`2cbc752e`→`661fc58f`（冷重启）→`78711a2c`（服务器重启）→`458d26a0`→`56284669`（强杀后 supervisor 冷启动），同一 offline UUID（SESS-1；03/04 目录日志） |
| 6 | 旧 session 迟到 frame/status 如何被拒绝 | 服务端 `bindGameIncarnation`：seq 回退→断连、同 seq epoch 漂移→断连、seq 前进→接受并清空 sensor/executions；frame_seq 重复/倒序→丢弃不刷新快照。JUnit `RealClientWireSessionTest` 9 项 socket 契约（staleIncarnation/epochDrift/executionMustCarryCurrentIncarnation 等） |
| 7 | sensor frame identity/freshness/位置容差 | 单调 `frame_seq`（transport 单调过滤）；`FRAME_FRESH_MS=2000`；`POSITION_TOLERANCE=2.0`（sensor 位置仅一致性提示，权威身体=服务器实体）；越界一律 fail closed（GameTest 5 项新测试） |
| 8 | server raycast 如何与同一 frame 对齐 | 从服务器权威 `getEyePos()` + 该 frame 的 `Vec3d.fromPolar(sensor.pitch(), sensor.yaw())` 重建 world raycast（OUTLINE/NONE，7 格），格级精确比对；**已删除** `player.raycast()` 跨时间拼接（源码契约负锚 `assertFalse(tracker.contains("player.raycast("))`） |

## 3. 自主朝向与真实 mine（AIM-1、MINE-1/2/3、GRAPH-1/2）

| # | 问题 | 答案 |
|---|---|---|
| 9 | 客户端自动朝向机制，是否零人工 | goto 新增可选 `face_x/y/z` final-facing：到达后 `lookAt(格心)`（同步 headYaw，crosshair 真实视线）等待 crosshair 压住该格（≤100 tick）。**未新增 public operation/DSH 工具**。全程零人工输入（本流程曾用键盘程序化通过客户端首启 onboarding 屏——属一次性环境 setup，已用 auto-join 白名单修正消除该需求） |
| 10 | first durable birth | `rcore_0bd08c60709b_6a31ddbddf394fe5` @ `(7,-60,2)` iron_ore，frameSeq=367，gameSession=`661fc58f`，14:24:25；journal 帧 `real_client_opportunity_birth`+`resource_opportunity_actionable`（03-live-sensor-mine/journal-final-full.txt、server-key-events.txt） |
| 11 | birth restart replay | 停服重启后新 epoch `78711a2c` 下 deliberate observe → `POST /v1/view` 重新暴露**同一** incarnation（liveC-view-replay-same-incarnation.json） |
| 12 | terminal 后同格新 incarnation | `rcore_0bd08c60709b_91c3593e05af43ca`（同 location hash、不同 incarnation，frameSeq=516）；view 中新 id 在场、旧 id 消失（liveD-view-new-incarnation.json） |
| 13 | Graph id / execution id / EvidenceRef | `graph-119777f514c1b06a26457818`（plan_key=mc2a04a-iron-1）→ run-next → execution `e0790458-0529-49f0-a628-35cdb6c30657`（admitting game session `78711a2c`）；EvidenceRef `mc://6d190bc8-…/minecraft%3Aoverworld/opportunity/rcore_0bd08c60709b_6a31ddbddf394fe5` |
| 14 | exact target before/after | before `minecraft:iron_ore`（setblock 两步放置）；after air（fill-replace 探针 `Successfully filled 1 block(s)` 证明为空气） |
| 15 | inventory baseline/current | baseline raw_iron=0（铁镐 slot0 damage=0）→ current raw_iron=1（Slot1 `minecraft:raw_iron`，镐 damage=1 物理磨损）；LIVE-F 再挖 1→2 |
| 16 | consumed receipt 与 Graph DONE | journal `resource_opportunity_consumed {baseline:0,current:1,resolution:inventory_gain_proven}`；`GET /v1/graphs/{gid}` state=**DONE**（liveC-graph-inspect-done.json）。客户端 completed 仅进度提示，成功只认服务器双证明 |

## 4. 强杀围栏与冷重启（LOSS-1..4、RECON-1）

| # | 问题 | 答案 |
|---|---|---|
| 17 | crash 时 RUNNING execution 与实际 PID | execution `2b09837f-9b07-43f2-9886-20eb049deb19`（goto，kill 前 0.4s state=running progress≈0.23，真实位移 25.6 格进行中）；PID/epoch 见 liveE-pre-kill.json（supervisor state 指向实际客户端 JVM） |
| 18 | kill 后 outcome_unknown exact reason | `taskkill /PID … /T /F` exit 0（整棵进程树；无 cancel/pause/release、非 kick/停服/死亡）→ t=0.73s 起 ex=`outcome_unknown`，reason=**`body_session_changed`**（liveE-fence-timeline.json 15 个采样点持续稳定） |
| 19 | 旧 lease/needs_reconcile/read plane/queued query | 旧 lease renew 与 submit 均 409 `control_lease_invalid`；`needs_reconcile=true`、`control_active=false`；view 503（cached cognitive 失效）；带死 lease 的 inspect-local 503（read plane 失效）——liveE-fence-extras.json |
| 20 | urgent `body_session_changed` event | 事件流 seq=92 `body_session_changed {reason:"physical_body_unavailable", previous:{body_session_epoch:"458d26a0…"}}`（事件通道=DSH/event client 同源；DSH 侧消费契约由 Node 43 测试覆盖） |
| 21 | supervisor restart / new PID / new game session | 循环模式自动拉起：新 PID、新 epoch `56284669`（≠458d26a0）；supervisor-state/日志归档于 04 目录 |
| 22 | no-replay 证明 | 重连后 12 秒无命令观察窗：位置恒 `(3.59,-60,34.80)`、`active_execution` 恒 null、旧 goto 保持 outcome_unknown（liveF-no-replay-window.json 6 采样） |
| 23 | deliberate observe / new lease / new work | observe → 新 lease → `say` completed（`client_chat_packet_sent`）→ 短 goto completed（`server_authoritative_arrival_within_2_5_blocks`）→ mine smoke completed `…inventory_gain_verified:1->2`（liveF-new-work-say-goto.json、liveF-mine-smoke-final.json） |

## 5. 回归与审计（REG-1、TOOLS-1、AUTH-1/2、ACT-1、SEC-1、EVID-1）

| # | 问题 | 答案 |
|---|---|---|
| 24 | FakePlayer 回归与 real port 状态 | LIVE-G：backend=`server_fake_player`、say/goto completed、goto 需显式 `allow_terrain_changes=true`（与 real 互为镜像、均无 fallback）；fake 模式日志无 8766 绑定行、运行时 netstat 仅 8767 LISTENING（05-regression/） |
| 25 | 精确计数 | JUnit 415/0/0 ×2 + clean replay 415/0/0；GameTest 643/0 ×3（dev/同字节/clean replay）；BridgeCore 105；Node 43/0；Installer 11；Supervisor 9/0（02-automated/TEST-COUNTS.md） |
| 26 | DSH tools 数量 | 恰好 **29**（12 static + 14 operations + 3 control；本轮未触碰 DSH 插件） |
| 27 | clean replay 是否 0 diff | 是。冻结上游 a029fa6 重新 clone + 同一 installer 应用 → `diff -rq aibot/src aibot-replay/src` 零输出（clean-replay-zero-diff.txt） |
| 28 | final SHA256SUMS | 170 条（+1 新测试文件），无自引用，全部校验 OK（gen_sums.py 重生成） |
| 29 | frozen/scope/secret audit | 7 个冻结文件与 34fb49f byte-identical；graphRunNext 派发块无 backend 分支且经 BridgeKernel.submit；realclient 源码 0 处 teleport/setPos//setblock//give/HIDDEN_BLOCK_SCAN；无 GUI 实现/ BOT_POV/audio/streaming/scheduler（'Screen' 命中均为 auto-join 屏名白名单字符串）；repo+delivery secret scan 零命中（06-audit/） |
| 30 | 尚存缺陷与下一轮建议 | ①同进程被踢后 auto-join 循环失败（TCP 立即拒绝）仍依赖 supervisor 冷启动恢复——建议下轮客户端侧做连接退避+会话重建；②fake 模式 goto 与 real 模式的 terrain 参数语义互为镜像，DSH 工具描述可按 backend 能力分化；③LIVE 环境曾出现 doMobSpawning=false 前 spawn 的史莱姆致死 Bob（已用抗性+清场+playerdata 重建恢复），fixture 脚本建议固化"免疫先行"顺序；④事件通道的 urgent 送达已实证到事件流，DSH web 会话端到端拉取留待下一轮 Native GUI 前置检查 |

## 阶段门复述（TASKBOOK §13）

```text
真实 coherent sensor birth                PASS（#10）
真实 Graph mine+pickup+durable DONE       PASS（#13-16）
真实 RUNNING mutation process crash fence PASS（#17-20）
真实 supervisor cold restart+no replay    PASS（#21-22）
explicit observe/new lease/new work       PASS（#23）
全自动化与 frozen regression              PASS（#24-27）
clean replay zero diff                    PASS（#27）
scope/secret/manifest audit               PASS（#28-29）
```

Real Client Body MVP baseline 达成；可提议进入 Native GUI / Mod Screen introspection 阶段。

## 证据目录

```text
00-meta/        base/环境/jar SHA256（token 只记"已设置"）
01-diff/        full-production.patch / changed-files / wire v2 note / 上轮 delivery 未动证明
02-automated/   JUnit×3 + GameTest×3 console/XML + 四套件 + 0-diff + TEST-COUNTS.md + Stage A 基线差异记录
03-live-sensor-mine/  fixture 叙述/双端日志全量/journal 全量/birth→DONE 全链 HTTP+RCON 证据
04-live-crash-reconnect/  pre-kill/fence 时间线/extras/events/supervisor state/零重放窗/新工作
05-regression/  fake smoke/authority 拒绝/gather 409/端口对照
06-audit/       frozen hashes/scope 审计/DSH 29/secret scan
```
