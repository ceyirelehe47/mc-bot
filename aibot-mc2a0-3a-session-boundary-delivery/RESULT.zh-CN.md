# MC-2A0.3A Physical Session Boundary & Read-Plane Closure — 交付结果

任务包：mc-bot-mc2a0-3a-session-boundary-closure-taskpack-20260912.zip（SHA256SUMS 全 OK）
分支：experiment/mc2a0-3a-session-boundary-closure（自验收 HEAD 36e18be 新建）

## 1. 分支/基点/生产/证据 SHA

- 分支：`experiment/mc2a0-3a-session-boundary-closure`
- 基点（BASE_HEAD）：`36e18becbe6b57e48add070dc35df29b35764c34`（MC-2A0.3 验收 HEAD，完整证据在祖先链）
- 被评审生产源：`a767826d2d6325a884dff2478b1774c960177d71`（MC-2A0.3 生产提交）
- 本轮生产提交：`74bb4d8`（仅代码/测试/清单，10 文件 +411/−63）
- 证据提交：本目录归档提交（仅证据，不改生产文件）
- 冻结图祖先：`3df7fb9a0086f4eb0bd7249e0925f799c47ccb8e`

## 2. exact-blob applier 结果与全部修正

验证模式：`CHECKED: 7 exact accepted blobs; 1 new files`（HEAD 与 blob 校验全过）。
应用模式：`APPLIED: 8 files`。独立验收已证实 01-diff/applier-generated-review.patch 的
7 个文件段与从 a767826/HEAD 真实内容重生成的 unified diff 逐行一致（review patch 为
朴素 unified diff 格式，与 git diff 格式的 production-full.diff 为语义等价而非字节相等；
等价性由验收方独立复现证实）。

修正清单（全部在任务书 §3 允许范围内）：

| # | 修正 | 允许项 |
|---|---|---|
| 1 | `scripts/apply_to_aibot.py` 的 fabric.mod.json 锚点补丁追加注册 `MC2A03ASessionBoundaryGameTests`（否则 fabric-gametest 不发现新测试，干净重放后静默消失——R1.1 轮既有教训的重现预防） | "installer manifest adjustment only if recursive overlay copy unexpectedly misses the new GameTest" |

无其他修正：applier 的 7 blob/锚点全部原样应用，零源码改动。

## 3. 最终 body-id 归一化 API

`ExternalBodyAccess.normalizeBodyId(String botName, String configured)`（包内静态纯函数）：

- botName 空白 → 返回 `""`；
- configured 空白 → 用 botName 兜底；
- trim + `toLowerCase(Locale.ROOT)`；
- 必须匹配 `[a-z0-9][a-z0-9._:-]{0,79}`，否则抛
  `IllegalArgumentException("invalid_AIBOT_EXTERNAL_BODY_ID")`；
- 唯一生产调用方 `configureBodyId()`（读 AIBOT_EXTERNAL_BODY_ID 环境变量），
  仅被 `ExternalBodyRuntime.start()` 的受控 try 边界调用；运行期读取经
  `ExternalBodyAccess.bodyId()`（未配置且 enabled 时抛 `external_body_id_not_configured`）。

JUnit 直接单测：`BodyBackendSeamSourceTest.bodyIdNormalizationIsExplicitAndClassLoadingIsSafe`
（"Bob"+""→"bob"；"Bob"+" Bob.Client-1 "→"bob.client-1"；"Bob"+"bad body id"→抛）。

## 4. 无静态 BODY_ID 校验的证明

- 源码面：`ExternalBodyAccess.java` 不含 `public static final String BODY_ID=`
  （gate-d-audit.json `no_static_body_id_validation=true`）；类加载只读
  AIBOT_EXTERNAL_BOT（无害环境读取），body-id 校验只在 `configureBodyId()` 内。
- 契约面：JUnit `bodyIdValidationRunsInsideTheControlledRuntimeStartBoundary` 断言
  `start() < "try {" < configureBodyId()` 的 indexOf 顺序。
- 运行面：Gate C 以无效 body id 实机启动，全日志 0 次
  `ExceptionInInitializerError`，typed 原因完整落在 start() 边界内（04-invalid-config/）。

## 5. invalid-config 日志标记与进程/端口结局

- `external_bridge_start_failed_closed`：log:98（IllegalStateException，start:52 抛出）；
- `invalid_AIBOT_EXTERNAL_BODY_ID`：log:106（Caused by 链
  normalizeBodyId:35 ← configureBodyId:43 ← start:27）；
- 无 `ExceptionInInitializerError`、无端点绑定标记；
- 进程：SERVER_STARTED 监听器传播异常 → 服务器 crash 退出（正常 server_stopping 存盘路径）；
- 端口：8767 始终不可达（脚本 socket 断言 + 绑定标记 0 命中双重证明）。

## 6. 读平面失效相对执行转移的代码顺序

`BridgeKernel.applyBinding()`（源码 168-182 行）：

```text
if(logicalChanged || sessionChanged) {
    reason = logicalChanged ? "body_identity_changed" : "body_session_changed";
    invalidateReadPlane(reason);                                    // ① 读平面失效
    if(active!=null) transition(active,"outcome_unknown",…,reason); // ② 执行转移
    token=null; owner=null; …; needsReconcile=true;                 // ③ 吊销租约+围栏
    event(logicalChanged?"body_changed":"body_session_changed",…);  // ④ 会话事件
}
```

① 先于 ②③④。JUnit 契约测试以 indexOf 顺序锁死该次序（393 套件内）。
`invalidateReadPlane` 本体：`cognitive=null`、`cognitiveTick=-1`、
`cognitiveFault=reason+"_rebuild_required"`、轮询取消全部排队
LocalQuery（inspect 与 local 共用 localQueries 队列）。

## 7. 旧会话 inspect/local future 的结局

全部 `completeExceptionally(new BridgeFault(409, reason+"_query_cancelled"))`
（reason 即 body_session_changed / body_identity_changed）。已入队查询永不物化。
失效后新 inspect 在入队前即被 `cognitive==null` 拦截为
503 `cognitive_view_unavailable:<fault>`，重建必须经显式 observe。

## 8. 排队查询绝不在替换身体上执行的证明

BridgeCoreTest（94 项之一）：替换 tick 前记录 `materializes`/`inspectLocals` 计数，
替换 tick 后断言两计数不变——被取消的 inspect/local 查询从未调用后端物化/局部观察
（02-build/bridgecore-direct.log "cancelled old-session queries never execute on the
replacement body"）。

## 9. 首个重建视图属于新会话的证明

BridgeCoreTest：FakeBackend 快照 sceneJson 按 session 生成（session-1/session-2），
替换后 `kernel.view()` 的重建输出含 `"session":"session-2"` 且不含
`"session":"session-1"`（"view is rebuilt for the replacement physical session"）。
替换前的旧视图绑定 admitting session 亦有正向断言。

## 10. Node 证明 body_session_changed 属于 IMPORTANT

`dsh-plugin/src/events.mjs`：IMPORTANT 集合与 URGENT_KINDS 数组均已含
`body_session_changed`（gate-d-audit.json 两项 true）。
直接证据（05-event-delivery/gate-b-direct.log，调真实导出函数）：

```text
shouldDeliver(body_session_changed) = true
```

## 11. Node 证明 running 与 idle 双态均 steer

同一直接证据脚本输出：

```text
running agent + body_session_changed -> steer
idle agent + body_session_changed -> steer
```

套件面：events.test.mjs 的 urgent kinds 循环含 body_session_changed（running steer 断言
带 kind 名），idle 场景追加 body_session_changed 事件断言 `['steer','steer']`；
全套 43/0（02-build/node-direct.log，14 个 events 测试在内）。游标推进而无投递的
回归形态由"pump checkpoints only after delivery"与"delivery failure never advances"
两条既有测试持续拦截。

## 12. 同进程首个/替换实例 id

外部可见物理载体：Minecraft 实体 id **1517 → 1518**
（06-session-integration/mc2a03a-log-excerpt-round1.log，同名假人 despawn 后 respawn）。
kernel 侧 `body_instance_id` 为运行期生成的随机标识（测试用临时 journal 已按测试
finally 清理删除），具体值未入证据；其"实例随替换而变"由会话 epoch 轮换断言传递证明。

## 13. 首个/替换会话 epoch

`firstSession`/`secondSession` 均非空且互不相等（GameTest 显式断言，三跑全过）。
具体值为运行期生成字符串，随临时 journal 删除，不入证据。

## 14. 被打断执行的 id/状态/原因

`mc2a03a-running-goto` / `outcome_unknown` / `body_session_changed`（GameTest 断言
reason 字符串精确相等；runbook §8 停止条件"reason 不是 body_session_changed 即未完成"
未触发）。

## 15. 租约吊销与 reconcile 状态

替换 tick 后：`control_active=false` + `needs_reconcile=true`（断言）；
显式 observe 后 `needs_reconcile=false`（断言）；随后新 claim 成功。

## 16. 替换身体上的无重放证明

三层：① GameTest 断言 `TaskManager.INSTANCE.getActive(replacement).isEmpty()`；
② 服务器日志：1518 登录后至 deliberate say 前无任何 task_assigned；
③ BridgeCoreTest `body.starts==1`（后端启动计数不变，会话变更从不重放 mutation）。

## 17. journal 会话变更与绑定帧序列

GameTest 断言：`body_session_changed` kind 事件恰 1 帧、`body_binding` kind 帧恰 2 帧
（初始绑定+替换绑定）。BridgeCoreTest 同步覆盖事件语义（fence→吊销→reconcile）。

## 18. observe 后故意新执行的结果

`mc2a03a-deliberate-say`（新租约）：state=`completed`，执行回执
`body_session_epoch=secondSession`（新会话准入证明）；真实全服广播
`<Mc2a03aBot> mc2a03a-session-reconciled` 见日志摘录末行。

## 19. 五套件计数

| 套件 | 要求 | 实际 |
|---|---|---|
| JUnit dev / replay | ≥393 / 0 | **393/0/0/0 ×2**（73 XML×2，02-build/junit-*） |
| GameTest round1+round2+replay | ≥637 / 0 | **637/0 ×3**（两次物理随机类偶发失败重跑收敛，NOTE-flaky-retries.md 如实披露：hunt 掉落回收与 mc2a02 树探索，均在本轮改动面之外，失败日志一并归档） |
| Node | ≥43 / 0 | **43/0** |
| BridgeCore | ≥94 | **94 PASS** |
| Installer | ≥11 | **11 OK** |

新 MC2A03A 同进程替换测试在三跑中均在场且通过（XML classname 逐轮核对）。

## 20. DSH 工具数

**恰 29**：静态 register 12 + 操作循环 14（operations 数组逐项）+ 控制循环 3
（pause/resume/cancel）。plugin.mjs 与 a767826 字节一致（gate-d-audit.json）。

## 21. 干净重放零 diff

干净重放树（aibot-replay-2a03：上游 a029fa6 + installer 重放）与 dev 树 src 全量
`diff -r --brief`：**0 行差异**（01-diff/replay-tree-diff.txt），且重放树独立产出
JUnit 393/0 与 GameTest 637/0。

## 22. TaskGraphStore 哈希/diff 证明

与 a767826 字节一致（git diff 空 + blob 对比），
sha256(f393d817ee7374b25b73a1af17ab9f046658c477a992f2ae711d89503289c8c9)
见 07-audit/gate-d-audit.json。

## 23. Graph 派发块证明

`graphRunNext` → `graphCancel` 区块与 a767826 **字节一致**（本交付自证口径 1138 字节、
独立验收提取口径 1241 字节，差异仅为区块边界定义不同，两版本逐字节相等的结论两种口径
均成立），仍含
`submit(supplied,d.requestId(),d.operation(),d.arguments())` 且不含
`backend.start(`；JUnit 契约（kernelPersistsAndFences…WithoutTouchingGraphDispatch）
持续锁定。

## 24. FakePlayer 操作映射 diff 证明

`ServerFakePlayerExecutionDriver.java` 与 a767826 字节一致（零 diff）；14 个操作映射
字符串完整在源（gate-d-audit.json `driver_operation_mapping_complete_14=true`）；
`PhysicalExecutionDriver.java`、`MinecraftBodyBackend.java` 同样零差异。

## 25. 无 RealClient/GUI/BotView/Scheduler 蔓延

a767826→HEAD 全部新增行扫描 RealClient/BotView/real_client/render/streaming/Scheduler
关键词：0 命中；改动文件集合 ⊆ 允许集合（7 个任务包文件 + installer manifest +
SHA256SUMS，无额外文件）——gate-d-audit.json 两项审计均 true。

## 26. 是否接受为 Real Client MVP 基线

**建议接受**。ACCEPTANCE_MATRIX 全部 17 项硬门（RP-1..4 / EVT-1..2 / CFG-1..2 /
INT-1..5 / GRAPH-1 / SEAM-1 / REG-1 / SCOPE-1）均有自动化或 LIVE 证据；
INDEPENDENT_REVIEW_FINDINGS 的四缺口（同进程替换集成未跑、读平面陈旧、DSH 过滤
body_session_changed、body-id 静态校验半径）全部闭合。§8 七条停止条件零触发
（GameTest 为真同进程实体替换非重启；reason 精确为 body_session_changed；旧会话查询
未在替换上执行；body_session_changed 不再被 DSH 过滤；invalid body id 无
ExceptionInInitializerError；Graph/FakePlayer 语义零变化；重放零 diff 且 DSH 仍 29）。
下一轮可在此外接身体基线上实现 real-client transport/backend，无需在 Graph 或 DSH
面向代码插后端条件分支。

## 附录 A：本轮遗留与如实披露

1. **GameTest 两次物理随机偶发失败**：round2 首跑（hunt mutton 掉落回收）与 replay
   首跑（mc2a02 树探索 no_resource_after_explore），分属上游 hunt 与冻结 MC-2A0.2
   测试，源码均在改动面之外，同树重跑即收敛全绿；失败与成功的完整日志均归档
   （03-gametest/NOTE-flaky-retries.md）。
2. **GameTest 临时 journal 的 epoch/instance 具体值未留档**：测试 finally 删除临时
   journal（防止污染共享环境），证据以断言（非空/轮换/计数）+ 实体 id 1517→1518
   日志呈现；如需具体值可临时去掉删除行重跑（未做，避免引入夹具修正）。
3. **Gate C 服务器副本首跑启动失败一次**：一次性副本准备脚本漏拷
   fabric-server-launch.jar（638B stub）与 server.jar（vanilla 56MB），
   首跑即 `Unable to access jarfile` 退出（未触及被测行为）；补拷后重跑即 PASS。
   与被测代码无关，如实记录。
4. **上一轮遗留的 body-backend-seam-review.patch**（仓库根，未跟踪冗余副本）非本轮
   创建，保持原样未动。
