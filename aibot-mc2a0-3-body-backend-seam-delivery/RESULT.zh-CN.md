# MC-2A0.3 Body Backend Seam — 交付报告（RESULT, 27 问）

执行日期：2026-09-12 晚。全部结论的可复核证据在本目录与隔离服 journal/图存储中。

## 1. 精确 SHA

- 分支：`experiment/mc2a0-3-body-backend-seam`（自冻结基线新建）
- 基线：`3df7fb9a0086f4eb0bd7249e0925f799c47ccb8e`（experiment/mc2a-graph-core-frozen，双亲冻结 merge）
- 生产提交：`a767826d2d6325a884dff2478b1774c960177d71`（15 文件，+1027/-457，单独生产提交）
- 证据提交：即包含本文件的提交（SHA 见推送记录与最终报告；不在文件内自引用）

## 2. exact-blob applier 是否原样运行

**是**。验证模式 `CHECKED: 9 exact frozen blobs; 3 new files` 全过，`--apply` 原样落盘（12 文件）。
应用前有一项**环境修复**（非 applier/源码改动）：全局 `core.autocrlf=true` 使工作区文件为 CRLF，
与冻结 Git blob 字节失配（applier 校验工作区字节）→ 仓库 local `core.autocrlf=false` +
`git reset --hard 3df7fb9` 重建 LF 工作区后校验通过。修复后工作区字节、Git blob、
SHA256SUMS 三方一致（比 R1.2 时代的 CRLF 工作区更 canonical）。

## 3. 全部 compile/classpath 修正

无编译修正（影响面审查 subagent 已用真实 Yarn 1.21.3 named classpath 预编译零错误，实测一致）。
以下 **4 处源码契约测试断言串修正**，全部属任务书 §3 "adjustment of source-contract test strings
after semantically identical formatting" 允许范围，语义零变化：

1. `BodyBackendSeamSourceTest` T2：断言串 `executionDriver.start(` → `requireExecutionDriver().start(`（任务包供应的断言串与任务包供应的源码格式不匹配，审查实测必挂）。
2. `TreeHarvestSourceContractTest.bridgePassesTheExactBoundedExecutionIdIntoTheBodyBackend`：gather 构造断言的读取对象从 `MinecraftBodyBackend.java` 改为 `ServerFakePlayerExecutionDriver.java`（MC-2A0.3 后 Task 构建移入驱动，executionId 绑定契约不变）。
3. `PrivilegedBoundarySourceTest.cognitiveViewStaysAReadModel`：断言串放宽为跨行安全形式 `.build(bot,journal,semanticSnapshot)`（调用点现为 FQN 前缀+换行布局）。
4. 同测试类 `cognitiveInspectIsLazyAndStaysInsideEvidenceScope`：放宽为 `.materialize(bot,semanticSnapshot,gameTime,ref,detail)`。

2-4 经 `apply_to_aibot.py` 的 EXTRA_CHANGES 注入串修改（上游 tracked 测试文件的锚点变换）。
另有：`aibot-dsh-m0/SHA256SUMS` 按任务书要求重生成（154 条目；大部分行变化为上述行尾 LF 归一化，
非内容变化——git status 证明对应文件内容未动）。

## 4-6. 身份与绑定（LIVE-1）

- 配置稳定 body id：`bob`（`AIBOT_EXTERNAL_BODY_ID=bob`，默认规则=bot 名小写）。
- 旧 journal 最后 `body_binding.body_id`（seq 2）：`faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b`（Minecraft UUID）→ 迁移后 `bob`。
- backend_kind=`server_fake_player`；body_instance_id=`faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b`（profile UUID 稳定）；首个 body_session_epoch=`62cdf01b-e174-4691-9204-7a9ede8470d2`。

## 7. 首启迁移事件与 needs_reconcile 证明

journal seq 991 `body_changed` 事件帧：payload.previous.body_id=旧 UUID（其余三字段空=旧帧无此字段），
payload.current=bob+server_fake_player+instance+epoch 四字段齐。
observe 之前 `GET /v1/status`：`needs_reconcile=true`（04-live-binding/status-phaseA-before-observe.json，
event_sequence=992>989 证明迁移帧已落盘）。历史 33 张图正常加载（A7）。

## 8. observe 清 reconcile

LIVE-1 A8：`GET /v1/observe` 后 status `needs_reconcile=false`。LIVE-2 P12 复证。

## 9. 执行收据绑定字段

LIVE-1 A9/A10：say 执行 completed，收据 body_id/backend_kind/body_instance_id/body_session_epoch
与当时 status 完全一致；journal accepted 帧含同样四字段（离线断言 A13）。

## 10. 正常重启 epoch 轮换

LIVE-1 阶段B B2：`62cdf01b-…` → `98356085-…`（instance 同 UUID、body_id 仍 bob、needs_reconcile=true、无重放，B1-B10 全过）。

## 11. 进程内替换的 instance/session

如实披露：runbook 的 `/aibot despawn`+`spawn` 进程内通道被 **overlay 治理边界结构性拒绝**
（`ExternalBodyAccess.permitsLegacyOperation` 对保留身体一票否决 legacy ADMIN；回执
"[AIBot] 找不到该 Bot 或无权限。" 归档于 05-live-session-fence/despawn-governance-receipt.json，
P0 断言）。该拒绝本身是外部身体治理的正确证据。物理载具更换改经**重启通道**完成：
instance 同 UUID `faa5dca3-…`，session epoch `9d66fd81-…` → `5e16f334-…`（LIVE-2 P5）。
进程内换 session 的围栏逻辑由 BridgeCoreTest 89 断言中的 fake-binding 轮换 6 断言覆盖。

## 12. 被打断执行

LIVE-2：goto 执行（RUNNING 中被物理载具更换打断）→ `outcome_unknown`；
reason=`server_stopping_no_automatic_replay`（重启通道下停服语义先于 session 语义触发；
进程内通道的 `body_session_changed` reason 语义由 BridgeCoreTest 覆盖；P9 断言接受两种围栏原因并记录实际值）。
收据保留 admit 时绑定（epoch_1，P10）。

## 13. 租约吊销与无重放

LIVE-2 P6 `control_active=false`；P11 静置后仍 outcome_unknown（无自动重启）；
P17 journal 新增 accepted 帧仅 goto+say 两条（无重放 mutation）；P15/P16 `body_session_changed`
事件帧+新 `body_binding` 帧存在。

## 14. 围栏后新工作证明

LIVE-2 P12-P14：observe 清 reconcile → 新租约 → 新 request id 的 say `completed`，
收据绑定新 epoch_2。

## 15. say 终态

`completed` / `sent_to_aibot_panel_and_global_chat`（LIVE-3 S1，含四绑定元数据）。

## 16. goto pause/resume

LIVE-3 S2：铺石路装置上 RUNNING → `paused`（reason=user_or_lease_pause）→ 同一 execution
`resume` 回 running → `completed` / `arrival_within_3_blocks_verified`。

## 17. Graph mine

LIVE-3 S3（06-live-behavior/graph-*.json）：全新矿格 569,68,127 新化身
`ore_27a43aee7126_be10b3e546fa4fa3` ACTIONABLE → plan `graph-3ec6c0fc0008d3acfcf04e71` READY
→ `run-next`（graphd dispatch，execution 归档）→ 图 **DONE** → 机会从注册表销账（durable
consumed）→ raw_iron 0→1 真实拾取 → journal `resource_opportunity_consumed` 帧绑定该执行。

## 18. 树 gather

LIVE-3 S4：自然树（naturalTree 几何预置）`gather oak_log x1` → `completed` /
`inventory_family_or_exact_quota_verified:accepted=4`，oak_log 0→4（清包后**真实砍伐**，
非旧库存假阳性）。树/workset/支撑清理语义由冻结 GameTest mc2a02 系列（636 内）持续覆盖。

## 19-21. 接缝边界证明（LIVE-4，10/10 PASS）

- MinecraftBodyBackend 零 mutation Task 构造（11 个禁串零命中，A1）；四个控制调用全部委托（A2）。
- ServerFakePlayerExecutionDriver 拥有全部 14 操作映射+assign+实例替换哨兵（A3/A4）。
- PhysicalExecutionDriver 无 net.minecraft/AIPlayerEntity/MinecraftServer 引用（A5）。

## 22. Graph 冻结证明

`TaskGraphStore.java` 与 3df7fb9 **字节一致**（git diff --quiet，A6）；
`graphRunNext→graphCancel` dispatch 块与冻结基线**逐字节一致**且仍走
`kernel.submit(supplied,d.requestId(),d.operation(),d.arguments())`（A7/A8）；
Graph 物理路径唯一经 BridgeKernel.submit（A8 反向断言无 backend.start）。

## 23. 自动化计数

| 套件 | 结果 | 备注 |
|---|---|---|
| JUnit dev | **390/0/0/0**（73 XML） | 385 基线+5 新契约 |
| JUnit clean replay | **390/0/0/0**（73 XML） | 同 |
| GameTest | **636/0 ×3**（round1/round2/replay） | 全环境变量齐备 |
| Node | **43/0** | |
| BridgeCore | **89 PASS** | 78 基线+11 新 |
| Installer | **11 OK** | |
| SHA256SUMS | 重生成 154 条目 | 任务书 §4 要求 |

## 24. 干净重放零 diff

upstream `a029fa6a` 本地克隆 → 安装器重放 → 与 dev 树 `diff -r`（排除 .git/build/.gradle）：
**src 全部 744 文件零差异**；唯一报告行 = dev 树 `logs/`（GameTest 运行残留，非源码）
（01-diff/replay-tree-diff.txt + 双 manifest）。

## 25. DSH 工具数

**恰 29，未变**（dsh-plugin 仅 test/FakeBridgeServer.java 同步改动，无工具增删；Node 43 过）。

## 26. 无范围蔓延

LIVE-4 A9：15 个变更文件中无 RealClient/BotView/GUI/Scheduler/Agenda/Streaming 实现
（changed-files.json 归档）。

## 27. 接缝是否就绪下一轮 RealClient MVP

**是**。`PhysicalExecutionDriver` 为后端中立契约（Request record+四控制方法，零 Minecraft 依赖）；
`BodyBackend.Binding` 携带稳定逻辑 body_id+backend_kind/instance/session 三元物理事实；
kernel 围栏按 session 变化 fail-closed。下一轮可实现 real-client 驱动（同 body_id、不同
backend_kind），无需在 Graph 或 DSH 面向代码中插入后端条件分支。

## 附：如实披露事项

1. **并发会话**：21:17 另一 ZCode 会话（非本会话）在 mc-server-mc1ca 主服启动了带
   AIBOT_EXTERNAL_BODY_ID=bob 的服务器并将 world_play 改名 world_play.pre-mc2a03-play
   （用户 skyline_cc 随后登录游玩）。本会话的 LIVE-1 阶段A 证据在该启动前已捕获归档，
   journal 随改名完整保留；其余 LIVE 门全部转 mc-server-mc1ca-b 第二隔离服完成（RCON 25576/
   桥 8766/world_r2），未触碰主服与用户会话。
2. **LIVE-2 通道差异**：见第 11/12 问——runbook 的进程内 despawn 通道与 overlay 治理边界
   结构性冲突，实机围栏经重启通道验证，despawn 拒绝回执作为治理证据归档。
3. **LIVE-3 装置演化**：v1→v5 五轮装置修正（每轮 journal/图存储留痕=诚实历史）：goto 目标
   溺水改铺路；RCON 清包命令语法（`replaceitem` 在 1.21 已移除→`item replace … with`）；
   旧图 SUSPENDED 持 resource claim 需 cancel；失败 mine 留 MINED_PENDING_PICKUP 挂账化身
   需换新矿格；矿-站位须相邻 1 格（拾取碰撞可达）+清地表掉落+树访问支撑材料（圆石）。
   产品语义（pending recovery fail-closed、claim fail-closed）均按设计工作。
4. **影响面审查 CONCERN B**（不阻断）：`ExternalBodyAccess.BODY_ID` 静态初始化校验失败会使
   类加载失败半径扩大（合法配置不触发）。建议下轮把校验移回 start()。
5. 主服（mc-server-mc1ca）当前 mods 含新 jar c7cfd284、journal 为 LIVE-1 阶段A 状态的
   pre-2a03 备份；二服（mc1ca-b）mods 同 jar，world_r2 含本轮 LIVE 全链诚实历史。
