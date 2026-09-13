# MC-2A0.6 Screen Ownership / Lifecycle Closure / Mod UI Adapter — RESULT（35 问）

裁决：**全部硬门通过，可收尾**。两处供给实现缺陷以最小修正修复（逐项披露于 #2）。

---

## 1. branch / base / 生产 / 证据 SHA

- base：`experiment/mc2a0-5-background-native-screen` @ `471d88a61430f28ca4f59dd3a9a60bf6adbdca2f`
- 分支：`experiment/mc2a0-6-screen-ownership-mod-ui`
- 生产 commit：见 `git log`（生产/清单/证据分开提交）
- 证据 commit：交付目录（本目录）单独提交

## 2. applier 结果与全部修正

- applier check：`CHECKED: 14 exact blobs; 11 new files`（需先修 applier 自身一处：见 ①）
- apply：`APPLIED: 25 files`
- 修正清单（每项逐条披露）：
  1. **applier CRLF 转义修正**（任务包 PATCH_VALIDATION 自述未在真实 checkout 跑过）：`RealClientWireSessionTest` 两条 transform 规则内嵌 `\\r\\n` 源码转义（解析为字面 4 字符），与 LF 工作树失配 REFUSED。修正=该段 27 处转义改为 `\n`（语义零变化），AST 校验后通过。
  2. **源码契约排版修正 ×5**（TASKBOOK §3 允许的 semantically-identical formatting）：供给实现把 5 处被源码契约断言引用的连续串拆行（`bindGameSession(gameSessionEpoch,gameSessionSeq)` / `active.compareAndSet(prior,replacement)` / `fromPlayer>0&&fromPlayer==intoContainer` / `screen.screenSeq()>baselineScreenSeq` / 字符串拼接拆分 `real_client_deposit_mvp_requires_barrel_crosshair`）——还原单行/单字符串。
  3. **源码契约断言错位修正 ×1**：OwnershipSourceTest 断言 ActionController 含 `"phase","commit"`，该字面量只在 Driver（构造方）；ActionController 是解析方（`"commit".equals(`）。断言改为匹配真实判定形态。
  4. **fixture mixin 包布局修正**（真实缺陷）：mixins.json 把整个 `client` 包声明为 mixin 包，非 mixin 类（入口/Screen）同包导致 `IllegalClassLoadError` 崩溃客户端。修正=mixin 类移入 `client.mixin` 子包+mixins.json package 同步（语义零变化）。
  5. **服务器漂移检查放行客户端已完成态**（真实缺陷，最小语义修复+自曝）：客户端 complete 必然先关屏再上报，服务器 commit 后漂移检查（`!screen.present()` 判 ownership_lost）在完成判定之外抢先 fail，使 runbook PROOF-1 要求的 `client_completed_awaiting_server_container_inventory_proof` running 态不可达。修正=remote 已 completed 时不判漂移，落入等待证明分支（runbook F④ 语义）；真实所有权丢失（remote 仍 running 时屏关/换）仍 fail closed。
  6. Yarn 映射修正：**零**（影响审查 subagent 用 mappings jar+javap 预验 8 项签名，一次编译通过）。
- LIVE 侧装置修正（非生产）：commit 代理 v1 的 rewrite 分支写回顺序 bug（改字段后未写回 arguments_json）——修正后 F3 拒绝伪造元组实证。

## 3. 冷启动控制/游戏时间线（LIVE-A）

`03-live-lifecycle/timeline.txt` 全文。关键窗口：gate（25598 本地 JOIN 门控）拒绝 auto-join 170+ 秒（attempt 1..34），期间 control transport 稳定连接（20:27:06 起 0 断开）、zero 游戏态帧；放行后 JOIN、kick 断连（control 保持）、冷重启重连三段。

## 4. 禁忌 pre-JOIN 协议错误计数

冷启动+断连重连全程：`real_client_screen_missing_game_incarnation: 0`、`real_client_heartbeat_missing_game_incarnation: 0`、`real_client_execution_missing_game_incarnation: 0`（timeline.txt 三段计数均 0；0.5 的重连风暴在本轮服务器日志零出现）。

## 5. DISCONNECT 队列/绑定清理证明

kick（20:39:09）后 control 会话无断开日志（transport 不随 MC 断连）；重连后 game incarnation 换代（e2d12a57→808fbf2c），旧 Screen 快照不暴露（observe screen present=null）。

## 6. 协议 v1/v2/v3 拒绝

socket 探针直连 8766 发 hello 1/2/3：服务器日志三条 `real_client_protocol_mismatch` + 连接立即关闭（`08-regression/h-batch2.json` + 服务器日志）。

## 7. moving final-facing 执行 id 与状态轨迹

`liveB-moving-face-final`（f9269cde-ba7c-42c1-b831-7bcefe3a4f0d），10.5 格走位同一执行完整轨迹：
`walking_to_target → client_arrival_settling → awaiting_client_final_facing_ack → completed(server_authoritative_arrival_and_facing_verified)`。
（0.5 遗留问题 C 正面关闭。前期失败轮为装置几何：goto 目标距 face 4 格+到达半径 2.5 使停止点距桶面 6.1 格 >4.5 格交互射程——lookAt 三级诊断证明转向逻辑正确后换装置，证据 04-live-facing/moving8b-rotation.json 等。）

## 8. final-facing 终态 reason 与 fresh 传感器元组

`completed / server_authoritative_arrival_and_facing_verified`；服务器校验=同 game_session+fresh crosshair==face 格（blocked-face2 负向：整列玻璃遮挡视线 → `client_final_facing_timeout` fail closed）。

## 9. Screen 会话/epoch/seq/sync/adapter 元组

`06-live-mod-fixture/observe-screen.json`：game_session=b6868d5f…、screen_epoch=2c9b2e53…、screen_seq=182、sync_id=4、adapter_id=mc2a_fixture_storage_v1。

## 10. 预存错误 Screen 失败与零突变

`05-live-screen-ownership/preexisting-proxy.json`：hold_cancel 装置下（服务器已 terminal、客户端屏仍开），第二 deposit typed fail `real_client_screen_already_open`；桶内物品与背包零变化（invA unchanged=True）。

## 11. open-before-commit 区间与零库存变化

`liveD/two-phase-window3.json`：hold_commit 下 running+`client_target_screen_open_waiting_authorization`，屏元组（seq=131/epoch=c261512c/sync=2/adapter=mc2a_fixture_storage_v1）已上报、桶空+背包两堆零变化；放行后同一执行完成（见 #15）。

## 12. 服务器 target Inventory 归属证明

Driver `handlerOwnsTargetInventory`（currentScreenHandler.slots 存在 slot.inventory==barrelInventory）+ `slot.inventory==targetInventory` 源码契约（OwnershipSourceTest）；commit 只在该证明后发出（授权前置条件链 05-live-screen-ownership 全链日志）。

## 13. 发给客户端的确切 commit 元组

`{"phase":"commit","screen_epoch":…,"screen_seq":…,"sync_id":…,"adapter_id":…}`（arguments_json 内层；代理日志 mc2a06-proxy-log.jsonl 的 commit 帧捕获佐证）。

## 14. 客户端确切所有权校验证明

授权后每 tick 六元组校验（current 屏 epoch/adapter/syncId/seq/handler.syncId/supportsDeposit 全等才允许 QUICK_MOVE）；F3 伪造元组（epoch=dead-beef/seq=1/sync=99/adapter=unrecognized 经代理改写实测送达）→ `failed client_deposit_authorization_invalid`+零突变（f3-forged-fixed.json；客户端诊断日志记录收到的伪造元组原文）。

## 15. 正向 vanilla barrel 转移增量

`completed server_authoritative_owned_screen_container_transfer_verified:1`：player -1（slot1 的 1 圆石 QUICK_MOVE；selected 槽 64 保留=工具/选中槽保留语义）== barrel +1。

## 16. completed 无服务器证明的采样 running 态

F4（c1676254）：空背包（仅 selected 一堆）deposit → 采样到 running `client_completed_awaiting_server_container_inventory_proof` 保持非终态（修正 #2⑤ 后可达；超时/取消才终态）。

## 17. 屏关闭/替换/伪造 commit 负向

- 关闭：破 barrel（setblock air）→ `failed real_client_deposit_target_changed`、零转移（F1）。
- 替换：barrel→chest → `failed real_client_deposit_target_changed`、零转移、chest 空（F2）。
- 伪造：见 #14（F3）。
- 无证明保持 running：见 #16（F4）。

## 18. fixture mod JAR SHA256 与加载证明

- 生产 remap jar：`d6654743252734dc31d7891f76c4ed6413224697884fa899434c35b9ffe80afa`
- dev（named）jar（Bob dev 客户端实际加载）：`3347258f3bba02ef8cfe263fb1456dd974b9b695749206faa8ec17a3338ed468`
- 加载证明：客户端日志 `MC-2A UI fixture enabled`（20:27:05）+ mod 列表含 `mc2a-ui-fixture 0.0.1`；服务器/owner 客户端零加载（mods 目录仅 Bob 客户端 classpath 追加）。

## 19. fixture 自定义 Screen 类与 adapter id

`io.github.zoyluo.mc2aui.fixture.client.FixtureGenericContainerScreen` → `mc2a_fixture_storage_v1`（observe-screen.json screen_class/adapter_id 实证；handler 保持 vanilla `net.minecraft.screen.GenericContainerScreenHandler`）。

## 20. fixture widget 元数据摘录

mc_view.ui：`"widgets": [{"active": true, "message": "Fixture Inspect", "visible": true, "widget_class": "net.minecraft.client.gui.widget.ButtonWidget", "widget_index": 0}]`；capabilities=`["deposit_quick_move","widget_introspection"]`、widget_count=1、slot_count=63（06-live-mod-fixture/ui-widgets-found.txt）。

## 21. fixture widget 从未被激活

客户端日志 `Fixture Inspect button clicked by a human` 计数=0（全轮）；自动化只走 ScreenHandler QUICK_MOVE，widget 仅被 children() 只读遍历（registry 无任何点击 API，scope-audit True）。

## 22. 正向 fixture 屏转移与终态

`liveD-window3`（682ff175）：fixture adapter 屏下 release commit → `completed server_authoritative_owned_screen_container_transfer_verified:1`（桶+1/背包-1）。

## 23. Screen 序号重复/乱序证明

JUnit RealClientScreenWireTest 替换版 4 测试含 seq 单调去重契约（automated 437 内）；LIVE 屏元组跨轮单调（82→131→182）。

## 24. 游戏 incarnation 清屏证明

重连/冷重启后 observe screen present=null/False（LIVE-A 第二段+G1/G2 尾段三处采样）；epoch 换代序列 808fbf2c→e0c96d36→…→15e24877 清晰。

## 25. commit 前崩溃终态/零重放

G1（048c90c0）：waiting_authorization 中 kill → `outcome_unknown:body_session_changed`+view 503+needs_reconcile=true；冷启动后旧 epoch 屏不暴露+fresh deposit completed（transfer_verified:1）。

## 26. QUICK_MOVE 中崩溃终态/零重放

G2（liveG2e）：release 后 20ms os.kill 直杀 → `outcome_unknown:body_session_changed`+503+reconcile；冷启动 fresh deposit completed。早期两轮 powershell/taskkill 链路慢于完成判定（如实披露：g2-quickmove-kill.json 记录三轮装置演进，前两轮 completed+围栏仍生效）。

## 27. kill 前入队 read query 的异常完成

G1：kill 前 view query ok=true（admission 于 kill 前）；kill 后同租约 view → HTTP 503（读平面围栏）。

## 28. 替换会话旧 Screen 缺席

见 #24（G1/G2 尾段 observe screen present=False+epoch 新代）。

## 29. 重对账后 fresh deposit

G1 尾段（liveG1-fresh）与 G2 尾段（liveG2-fresh）均 `completed server_authoritative_owned_screen_container_transfer_verified:1`。

## 30. 全部自动化计数

见 `02-automated/TEST-COUNTS.md`（437×2+replay 437 / 643×2+修正后复验 643+replay 643 / BridgeCore 105 / Node 43 / Installer 11 / Supervisor 11 / DSH 29 / fixture build 双产物）。

## 31. clean replay / manifest

replay 树重置→重新 apply→`diff -rq src` 零输出（含修正后文件）；replay 树 JUnit 437/0+GameTest 643/0。SHA256SUMS 重生成 188 条无自引用（3a7738dd08760cdb…）。

## 32. DSH 工具数

**恰好 29**（12 静态 register + 14 operations 数组 + pause/resume/cancel 循环 3）；本轮 plugin.mjs 仅 deposit 描述一行变化（任务书许可），注册结构零变。

## 33. 冻结 Graph/FakePlayer 哈希

TaskGraphStore/ServerFakePlayerExecutionDriver/PhysicalExecutionDriver/BodyBackend/BridgeKernel 与 471d88a **byte-identical**（09-audit/frozen-boundaries.txt 全 True）；Graph dispatch 段与 FakePlayer 操作映射零变化。

## 34. scope 审计

09-audit/scope-audit.txt 全 True：ScreenController 只读、唯一 clickSlot（DepositAction）、无坐标/反射/自定义 payload API、adapter 精确白名单、PROTOCOL_VERSION=4、无 BOT_POV/audio/streaming/scheduler、无 OS 输入合成、DSH 注册结构不变。

## 35. 真实第三方 mod 适配器轮就绪度

**就绪**——适配器注册表（精确类名→adapter+capabilities+只读 widget 描述）、wire v4 所有权元组、两阶段 commit 协议与服务器 Inventory 归属证明已全部实机验证。下一轮输入：第三方 mod Screen 的精确类名白名单登记+其 handler 的 slot 语义映射；遗留小项（不阻塞）：热重连循环仍需冷重启兜底（上游行为）、满桶 deposit 以 target_changed/等证明语义 fail closed（与 0.5 的长开屏窗口行为不同，装置需用 hold 制造开屏窗口）。
