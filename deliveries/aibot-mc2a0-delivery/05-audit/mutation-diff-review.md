# MC-2A0 审计：变更面与 world-mutation 风险审查（mutation-diff-review）

## 变更构成（相对 a029fa6 干净基线，经 installer 重放 0 diff 验证）
- 新增（overlay 整文件，7 个）：`external/cognition/{CanonicalJson,CognitiveSnapshot,EvidenceRef,CognitiveViewBuilder,CognitiveInspector}.java`、
  `gametest/MC2A0CognitiveViewGameTests.java`、`src/test/.../cognition/CognitiveCanonicalizationTest.java`
- 修改（overlay 整文件更新，5 个，均为 R2 起的 overlay 文件、非上游 tracked 文件）：
  `BodyBackend`（+3 default 只读方法，接口兼容）、`BridgeHttpServer`（+3 查询路由 + 无锁等待 helper）、
  `BridgeKernel`（+view/inspect/submitLocalQuery + tick 缓存与查询队列）、
  `MinecraftBodyBackend`（observeJson 缓存刷新提取为 refreshCaches 供两条路径共用 + override 三个 default）、
  `SemanticWorldRegistry`（+2 只读快照访问器 structureEvidences/farmEvidences）
- 锚点修改（EXTRA_CHANGES）：`fabric.mod.json`（注册 MC2A0 GameTest 类）、
  `PrivilegedBoundarySourceTest`（+cognitiveViewStaysAReadModel 守卫）
- 上游 tracked 文件（CHANGES）：0 处新增变更（R2.1 的 22 项保持不变，test_installers 断言锁定 22）
- DSH 侧：plugin.mjs（+3 只读工具注册 + limits 文案）、client.mjs（+3 查询方法）、
  events.mjs（IMPORTANT 白名单补 `resource_opportunity_stale`——R2.1 墓碑事件此前被
  DSH pump 静默丢弃，属交付缺陷修复）、测试 3 件 + FakeBridgeServer 认知 override；
  `BridgeCoreTest` +认知内核断言块（view 不清 needs_reconcile / fail-closed / 查询走 tick 线程）。

## mutation 风险逐点
1. `BridgeKernel.tick()`：新增 cognitive 缓存刷新（独立 try，失败只令查询 503 不炸桥）与
   localQueries drain（异常 completeExceptionally 回传 HTTP 线程）。既有 observe/control/execution
   逻辑零改动（diff 验证）。
2. `observeJson()` 重构：`refreshCaches()` 与原内联块逐行等价（同条件、同 10-tick 窗口、
   同维度失效），行为不变；623 GameTest 中全部 observe 相关用例通过佐证。
3. `SemanticWorldRegistry`：仅新增只读方法（快照 List.copyOf），既有状态机/持久化/事件零改动；
   R2.1 全部语义用例（pending/unreachable/stale/recovery）在 623 内原样通过。
4. `BodyBackend` 接口：default 方法不破坏三个测试替身（FakeBridgeServer ×2、BridgeCoreTest.FakeBackend
   均已 override）；纯 JDK 编译集经 `test.sh` 更新纳入 `cognition/{CognitiveSnapshot,EvidenceRef}`。
5. world mutation 面：cognition 包 5 文件经源码守卫断言不含任何方块/背包/任务变更调用；
   三个 HTTP 端点不进 `OPERATIONS` 白名单、不产生 journal execution 帧（BridgeCoreTest 锁定
   `submit` 409 与 `view` 成功并存）。
6. 确定性：canonical 序列化按 key 字典序，HashMap/插入序扰动不影响字节（JUnit + GameTest 双覆盖）；
   卡片/uncertainty/events 排序规则固定并写入 RESULT。
