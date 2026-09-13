# RESULT — MC-2A0.5 Background Client Runtime + Native Screen Vertical Slice（2026-09-13）

裁决：**PASS**。桌面共存输入隔离、final-facing/一帧一观测硬化、wire v3 原生 Screen 读模型、vanilla barrel deposit 垂直切片、Screen 会话崩溃围栏全部以原始证据关闭。TASKBOOK §9 三十四问逐答如下。

## 1. 身份与修正

| # | 问题 | 答案 |
|---|---|---|
| 1 | base/branch/production/evidence SHAs | base `4c0bf8e176fc4052b834a9850814860ad2dd6583`；branch `experiment/mc2a0-5-background-native-screen`；production/evidence SHA 见 `00-meta/final-sha.txt`（Commit A 生产 / Commit B 交付终稿） |
| 2 | 每个 mapping/API 修正 | **Yarn 映射修正 0 项**（供给实现一次编译通过）。其余修正 3 项（applier 锚点消歧 / 源码契约空格锚 / installer mixins.json 合并），逐项见 `02-automated/TEST-COUNTS.md` 修正清单 |

## 2. 桌面输入隔离（INPUT-1..5、FOCUS-1）

| # | 问题 | 答案 |
|---|---|---|
| 3 | 默认窗口模式 | **background**（supervisor 不传 window_mode 实测；客户端日志 `runtime enabled … window_mode=background`；INPUT-1） |
| 4 | mixin 在第一次 cursor lock 前已激活 | mixin 由 `RealClientInputIsolation`（纯 env 推导）守卫，类可早加载；debug 日志 `Mixing client.RealClientMouseMixin into net.minecraft.client.Mouse`（+KeyboardMixin）于客户端启动即注入；`denyCursorLock` 取消 lockCursor 全程（INPUT-2） |
| 5 | 背景启动前台 PID 时间线 | probe 3408 样本/713s：**0–428s 前台从未是 Bob JVM**（启动/auto-join/服务器重启/冷启动/初始 goto 全程）；428s 起为物理输入注入测试的**故意**激活段（03-live-input-isolation/mc2a05-liveA-probe-summary.json；FOCUS-1——supervisor 以 CREATE_NEW_PROCESS_GROUP+STARTF_USESHOWWINDOW/SW_SHOWMINNOACTIVE 启动，从不请求激活） |
| 6 | 光标可见性与 clip 矩形 | cursor invisible 样本 **0**；clip 恒 `(0,0,2048,1280)` 全屏（从不被裁剪到 Bob 窗口）；minimized 轮 728 样本同样全可见/全屏/前台 0（INPUT-2） |
| 7 | 物理鼠标不改 Bob yaw/pitch | Bob 置前台 + 40 次 SetCursorPos 大幅扫动 → 服务器 `Rotation` 前后 `[0.0f,0.0f]` 完全不变（mc2a05-liveA-input-final.json；INPUT-3） |
| 8 | 物理键盘不动 Bob/不改选槽 | 前台注入 A/D/W/S/5/Q/Esc（keydown+keyup）→ 位置完全静止、库存无丢弃（同上文件；早先"漂移"经排查为出生洞穴水流环境推动，tp 至安全平台后零位移，详见 input-probe 系列） |
| 9 | minimized 结果 | 728 样本：前台 0、光标全可见、clip 全屏；窗口保持不激活（IsIconic 由 GLFW 自建窗口覆盖属平台行为，核心 lock/focus 检查全过） |
| 10 | interactive 逃逸口 | 显式 `window_mode=interactive` → 相对鼠标移动真实驱动视角（Rotation `0/0`→`22.5/30.0`，liveA-interactive-smoke.json；INPUT-5）；**测试后已恢复默认 background** |

## 3. final-facing 与一帧一观测（FACE-1、SENSOR-1）

| # | 问题 | 答案 |
|---|---|---|
| 11 | final-facing execution 与 pre-ack running 区间 | goto(face) 走位版轨迹（liveB-goto-face4.json）：walking→**1.02s 起 `awaiting_client_final_facing_ack`（trail 第二采样 1.14s） 持续 running**→5s 后 `client_final_facing_timeout`（面向不可达目标时正确失败）；站桩版 0.81s 完成 |
| 12 | final-facing 终态 reason 与 sensor 目标 | `server_authoritative_arrival_and_facing_verified`（服务器到达 + 客户端 facing ack + **服务器侧 fresh sensor crosshair==face 格**三合一，driver 代码校验）——face6/face_barrel 均此 reason |
| 13 | 一帧一观测 birth 证明 | 服务器全程 `diag birth` 总数=**1**（liveF-regression.json births_total=1；多次 facing/重复服务器 tick/心跳不新增 durable birth）；journal 全量 birth/actionable/consumed 各恰 1 条 |

## 4. wire v3 与 Screen 读模型（WIRE-1、SCREEN-1/2/3）

| # | 问题 | 答案 |
|---|---|---|
| 14 | v1/v2 拒绝 | LIVE 探针（liveF-authority-wire-probe.json）：protocol=1 与 protocol=2 的 hello 均被服务器关闭连接（`real_client_protocol_mismatch`）；v3 hello（含新必填 `window_mode`）welcome ✓ |
| 15 | Screen 快照会话/序列绑定 | 快照绑定游戏 incarnation（`ScreenSnapshot.gameSession`），incarnation 前进时 transport 清空；`screen_seq` 单调去重（SCREEN-1/2 由 RealClientScreenWireTest JUnit 覆盖） |
| 16 | Screen slot 数/截断与 mc_view UI | observe 实测：present=true、`GenericContainerScreen`/`GenericContainerScreenHandler`、title="Barrel"、sync_id、slot_count=63、truncated=false；mc_view 全文（liveC-view-full2-raw.json）：`"ui": {"handler_class":…, "present": true, "screen_class":…, "slot_count": 63, "slots": [{…,"inventory_kind": "container"/"player",…}]}`——63≤64（view 导出）≤128（传输上限）（SCREEN-3） |

## 5. barrel deposit 垂直切片（GUI-1..4）

| # | 问题 | 答案 |
|---|---|---|
| 17 | deposit execution id | 首次成功轮 request_id `mc2a05-liveD-deposit2`（transfer verified:8，见 liveD-deposit2.json；轮询原始输出未单独落盘 execution_id，验收已核此瑕疵）；崩溃后 fresh 轮 request_id `mc2a05-liveF-fresh-deposit`（verified:12）；两者均以 request_id 链接 bridge 日志可回溯 |
| 18 | 目标桶与服务器验证 | barrel `(200,-60,196)`；服务器验证 crosshair→vanilla barrel（`validatedBarrelTarget`）后才派发（GUI-1） |
| 19 | 玩家库存 baseline/current | 铁镐×1 + 圆石×8 → 转移后仅铁镐×1（deposit 当轮）；后续各轮同型 |
| 20 | 桶库存 baseline/current | 空 → 圆石×8；fresh deposit 轮 12 items 等——**玩家减 N == 桶增 N > 0** 才 `server_authoritative_container_transfer_verified:N`（GUI-3） |
| 21 | 选定工具保留 | 铁镐 slot 0（selected）全程保留（DepositAction 排除 selected hotbar；多轮后 inv 仅铁镐） |
| 22 | QUICK_MOVE/Screen 证据 | 客户端 DepositAction：interactBlock→handled Screen→`clickSlot(handler.syncId, slot.id, 0, SlotActionType.QUICK_MOVE, player)`→closeHandledScreen；源码唯一 GUI 变更点（08-audit：ActionController clickSlot 计数=1、ScreenController 无 clickSlot/interactBlock）；LIVE 期间 view/observe 的 HandledScreen 快照佐证 Screen 真实开启（GUI-2） |
| 23 | 负面探针 | ①crosshair 指石头→`failed real_client_container_crosshair_required`（变异前拒绝）②指 chest→`failed real_client_deposit_mvp_requires_barrel_crosshair`（typed MVP 拒绝）③空背包（client 报 completed 无服务器证明）→不完成（该轮人工 cancel 终态 cancelled；"保持 running 等待 `client_completed_awaiting_server_container_inventory_proof`"的运行期采样未落盘——语义由 driver 源码 RealClientExecutionDriver 的 reason 分支+满桶轮 running `client_container_quick_move` 采样佐证，验收已核此瑕疵） ④满桶（QUICK_MOVE 无效物理不变）→不成功（GUI-4） |

## 6. Screen 会话崩溃围栏（LOSS-1）

| # | 问题 | 答案 |
|---|---|---|
| 24 | Screen 开启中崩溃终态 | pre-kill：execution running `client_container_quick_move` + observe screen present=true → `taskkill /T /F` exit 0 → **0.71s 起 `outcome_unknown:body_session_changed`** 稳定 15 采样（liveE-fence.json） |
| 25 | 旧 Screen/read-plane 失效 | 围栏后 view 503、旧 lease renew 409；inspect(ref) 404 `evidence_ref_not_in_current_view`（read plane 已失效）；新会话 observe screen present=**False**（旧快照不暴露） |
| 26 | 替换 game-session epoch | 冷启动新 incarnation `1856d888-…`（≠0c2b83ed）+ 新 PID（supervisor-state） |
| 27 | 无旧点击/转移重放 | 重连后 10s 观察窗位置恒 `(200.5,-60,200.29)` 静止 + active_execution 恒 null（liveF-no-replay-fresh-deposit.json） |
| 28 | 对账后 fresh deposit | deliberate observe→new lease→reface barrel→deposit → `completed server_authoritative_container_transfer_verified:12`，桶内 12 dirt+7 cobble、玩家仅剩铁镐（同上文件） |

## 7. 回归与审计（REG-1/2、TOOLS-1、REPLAY-1、SCOPE-1）

| # | 问题 | 答案 |
|---|---|---|
| 29 | 自动化计数 | JUnit 425/0×3、GameTest 643/0×3、BridgeCore 105、Node 43/0、Installer 11、Supervisor 11/0（02-automated/TEST-COUNTS.md） |
| 30 | clean replay 与 manifest | replay src **0 diff**；SHA256SUMS **177 条**无自引用全 OK |
| 31 | DSH 工具数 | 恰好 **29**（dsh-plugin/src 零改动） |
| 32 | 冻结 hash | TaskGraphStore/BridgeKernel/ServerFakePlayerExecutionDriver/PhysicalExecutionDriver/BodyBackend/BridgeHttpServer/BridgeJournal 与 4c0bf8e **byte-identical**（08-audit/frozen） |
| 33 | 范围外工作 | 无坐标点击 API、无公开 widget 激活、无 BOT_POV/audio/streaming/scheduler、无 OS 输入合成（SendInput/mouse_event/keybd_event 零出现）、无 hidden scan、FakePlayer 操作映射 byte-identical（08-audit/scope-audit.txt 全 True） |
| 34 | 下一轮 mod-screen introspection 就绪度 | **就绪**——读模型已验证（screen/handler 类名、title、sync_id、bounded slots、player/container 区分），DSH 侧 ui 卡片消费与 mod 容器泛化可作为下一轮输入；遗留小项：走位后立即 facing 的偶发超时（站桩 reface 模式已规避，建议下轮客户端到达瞬间清移动键位残留）与 minimized 的 GLFW 窗口态覆盖（核心检查不受影响） |

## 补充：LIVE-F 回归清单

say completed `client_chat_packet_sent`；goto pause→resume→completed `server_authoritative_arrival_within_2_5_blocks`；Graph mine 全链 birth `rcore_c81931a07cf6_3641a2ef898b4695`→plan READY→run-next→`server_authoritative_block_and_inventory_gain_verified:0->1`→graph **DONE**；gather typed 409；FakePlayer（8767）say/goto completed 且 fake 日志无 8766；real 单权威：first 连接保持时 second hello 被拒（liveF-single-authority.json）。
