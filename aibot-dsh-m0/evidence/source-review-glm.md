所有核对完成,信息充分。以下是完整审查报告。

---

# AIBot 外接大脑补丁包影响范围审查报告

审查对象:`D:\code\mc-bot\aibot-dsh-m0`(补丁包)vs `D:\code\mc-experiment\aibot`(冻结上游,HEAD `a029fa6a3760fd0f83834c104051b041d986da60`,已验证一致)。

## 一、10 个锚点核对表(文件|锚点|预期次数|实际|结论)

10 个文件的 git blob sha 与 `apply_to_aibot.py` 第 14-69 行 CHANGES 中的期望值**全部一致**(已用 `git hash-object` 逐一比对)。锚点计数(`transform()` 第 78-85 行按规则顺序应用,后者在前者替换后的文本上匹配):

| # | 文件(上游相对 `src/main/java/io/github/zoyluo/aibot/`) | 锚点(首现行号) | 预期 | 实际 | 结论 |
|---|---|---|---|---|---|
| 1 | `AIBotMod.java` | `RuntimeLifecycleCoordinator.INSTANCE.onServerStarted(server, config);`(62) | 1 | 1 | OK |
| 2 | 〃 | `ServerLifecycleEvents.SERVER_STOPPING.register(RuntimeLifecycleCoordinator.INSTANCE::onServerStopping);`(64) | 1 | 1 | OK |
| 3 | 〃 | `BotTickCoordinator.INSTANCE.tick(server);`(68) | 1 | 1 | OK |
| 4 | `brain/BrainCoordinator.java` | `public boolean handleMessage(AIPlayerEntity bot, ...`(53) | 1 | 1 | OK |
| 5 | 〃 | `private void onResponse(AIPlayerEntity bot, ...`(87) | 1 | 1 | OK |
| 6 | 〃 | `public boolean maybeWakeForFailureOrGoal(AIPlayerEntity bot) {`(265) | 1 | 1 | OK |
| 7 | 〃 | `private void submit(AIPlayerEntity bot, ...`(354) | 1 | 1 | OK |
| 8 | `brain/ToolRegistry.java` | `return Optional.ofNullable(tools.get(name));`(86) | 1 | 1 | OK |
| 9 | `task/TaskManager.java` | `private void assign(...,\n                        boolean publishStatus) {`(47,跨行) | 1 | 1 | OK |
| 10 | `coordination/IdleCoordinator.java` | `public boolean tickBot(AIPlayerEntity bot) {`(47) | 1 | 1 | OK |
| 11 | `task/BotTickCoordinator.java` | `if (!handled && GoalExecutor.INSTANCE.tickBot(server, bot)) {`(28) | 1 | 1 | OK |
| 12 | `runtime/RuntimeLifecycleCoordinator.java` | `public void onBotDeath(AIPlayerEntity bot) {`(75) | 1 | 1 | OK |
| 13 | `auth/BotAuthorizationGate.java` | `return decision.allowed();`(72/84/96) | 3 | 3 | OK |
| 14 | 〃 | `permitsLegacyOperation(bot, ...);\n    }\n\n    public boolean requireGlobalAdmin` | 1 | **原始文本 0;规则13应用后 1**(96 行区域) | **条件 OK(见下)** |
| 15 | `network/AIBotServerNetworking.java` | `String action = payload.action().toLowerCase(Locale.ROOT);`(305) | 1 | 1 | OK |
| 16 | `runtime/IntentController.java` | `Objects.requireNonNull(origin, "origin");`(127) | 1 | 1 | OK |
| 17 | 〃 | `String normalized = normalizeReason(origin, reason);`(66 + resume 内 1 处) | 2 | 2 | OK |
| 18 | 〃 | `public boolean routePlayerControlPhrase(...`(106) | 1 | 1 | OK |

**第 14 条的说明(唯一非平凡点)**:该锚点匹配的是规则 13 替换后产生的文本。语义是"两步修正":规则 13 把 3 处 `return decision.allowed();` 全部改为 `... && ACCESS.permitsLegacyOperation(bot, ...)`;但第 96 行所在方法 `authorizeBot(actorBot, targetBot, ...)`(上游 87-97 行)**没有 `bot` 变量**,规则 14 把这一处的 `bot` 改为 `targetBot`。已验证上游 96-99 行字节序列(`return decision.allowed();` + 4 空格 `}` + 干净空行 + `    public boolean requireGlobalAdmin`,LF 行尾、无 CRLF、无尾随空格),规则链在顺序应用下恰好匹配 1 次。**风险**:规则 13 与 14 存在隐式耦合——若规则 13 的 new 字符串有任何变化,规则 14 立即失配(脚本会 REFUSED 拒绝而非静默,属安全失败)。另外第 102 行 `if (decision.allowed()) {` 与 69/81/93 行 `if (!decision.allowed())` 均不匹配规则 13 锚点,不受影响。

**结论:全部 18 条规则在冻结 checkout 上可干净应用,无失配。**

## 二、3 个适配文件的编译疑点清单

### MinecraftBodyBackend.java(核心适配,25 项引用逐一核实)

| 引用位置:行号 | 引用的 API | 上游实际签名 | 风险 |
|---|---|---|---|
| :36 | `AIPlayerManager.INSTANCE.all()` | `public Collection<AIPlayerEntity> all()`(AIPlayerManager.java:227) | 低 |
| :41 | `IntentController.INSTANCE.cancelAll(bot, ControlOrigin.SYSTEM, String)` | `public IntentControlTransaction.Outcome cancelAll(AIPlayerEntity, ControlOrigin, String)`(IntentController.java:30-33),返回值忽略合法 | 低 |
| :42 | `BrainCoordinator.INSTANCE.reset(bot)` | `public void reset(AIPlayerEntity)`(BrainCoordinator.java:220) | 低 |
| :54,:84 | `AIBotConfig.get().profile().configValue()` | `public static AIBotConfig get()`(:45);record 组件 `OperatingProfile profile`(:27);`OperatingProfile.configValue()` 返回 `"strict_survival"`(OperatingProfile.java:11,21) | 低 |
| :55 | `bot.getHungerManager().getFoodLevel()` | AIPlayerEntity extends ServerPlayerEntity(:11);上游 AIBotServerNetworking.java:393 同用法 | 低 |
| :62 | `PerceptionCollector.collect(bot).toJson()` | `public static PerceptionSnapshot collect(AIPlayerEntity)`(PerceptionCollector.java:35);`public String toJson()`(PerceptionSnapshot.java:19) | 低 |
| :67 | `TaskManager.INSTANCE.activeOrigin(bot).map(TaskOrigin::safety)` | `public Optional<TaskOrigin> activeOrigin(...)`(:216);`public boolean safety()`(TaskOrigin.java:22) | 低 |
| :68-70,:124 | `isUserPaused` / `status` / `pausedDepth` | :220 boolean / :224 TaskStatus / :211 int ✓ | 低 |
| :103 | `new MoveTask(bot, goal)` | `public MoveTask(AIPlayerEntity bot, BlockPos goal)`(MoveTask.java:43)✓ | 低 |
| :105 | `new GatherQuotaTask(Item, int)` | GatherQuotaTask.java:115 ✓ | 低 |
| :106 | `new CraftTask(Item, int)` | CraftTask.java:36 ✓ | 低 |
| :107 | `new SmeltTask(Item, Item, int)` | SmeltTask.java:91 ✓ | 低 |
| :108 | `new EatTask()` | EatTask.java:10 无显式构造器 → 默认无参 ✓ | 低 |
| :109 | `new StockpileTask(true)` | `public StockpileTask(boolean allExceptTools)`(StockpileTask.java:43)✓ | 低 |
| :111-112 | `BotMemoryStore.INSTANCE.of(uuid).markPlace(...)` / `BotPersistence.INSTANCE.markDirty(server)` | `public BotMemory of(UUID)`(BotMemoryStore.java:19)+ `public void markPlace(String, ServerWorld, BlockPos)`(BotMemory.java:39);`public void markDirty(MinecraftServer)`(BotPersistence.java:118) | 低 |
| :117 | `BrainCoordinator.INSTANCE.sendPanelChat(bot, "bot", msg)` | `public void sendPanelChat(AIPlayerEntity, String, String)`(BrainCoordinator.java:346) | 低 |
| :122-123 | `getActive` / `hasPaused` / `bot.getActionPack().hasActiveActions()` | :196 Optional<Task> / :200 boolean / ActionPack.java:477 ✓ | 低 |
| :128 | `TaskManager.INSTANCE.assign(bot, task, TaskOrigin.of(Kind.LLM_TOOL, "..."))` | `public void assign(AIPlayerEntity, Task, TaskOrigin)`(TaskManager.java:34,委托锚点所在的 private 4 参版);`public static TaskOrigin of(Kind, String)`(TaskOrigin.java:26);`Kind.LLM_TOOL`(:10) | 低 |
| :133-134 | `task.progress()` / `TaskStatus.from(task)` | `double progress()`(Task.java:26);`public static TaskStatus from(Task)`(TaskStatus.java:15) | 低 |
| :135-141 | switch `TaskState` 6 常量 | TaskState.java:3-10 全部存在,switch 表达式穷尽 ✓ | 低 |
| :161-162,:165,:168 | `Task.state()` / `IntentController.pause/resume` | Task.java:10;IntentController.java:64/:86(boolean 返回值,忽略合法) | 低 |
| Yarn 层 | `getSquaredDistance`(HarvestCore.java:463 同用法)、`getRecentDamageSource().getName()`(AIPlayerManager.java:64 同用法)、`Registries.ITEM`、`Identifier.tryParse`、`getBottomY/getHeight` | 上游均有同型旁证 | 低 |

### ExternalBodyAccess.java
仅引用 `AIPlayerEntity`(:3)、`TaskOrigin`(:4)、`System.getenv`(:9)。`reserved/checkTool/checkAssignment/permitsLegacyOperation/message` 与 10 处锚点插入代码的调用签名逐一匹配。**无编译疑点。**

### ExternalBodyRuntime.java
| 引用位置:行号 | 引用的 API | 上游实际签名 | 风险 |
|---|---|---|---|
| :3,:31,:66,:69 | `AIBotMod.LOGGER` | `public static final Logger LOGGER`(AIBotMod.java:29) | 低 |
| :26 | `server.getSavePath(WorldSavePath.ROOT)` | KnowledgeBase.java:238 / BotPersistence.java:395 同用法 | 低 |
| :19-29 | checked 异常 | `BridgeJournal`/`BridgeHttpServer` 构造器 throws IOException,均在 `catch(Exception)`(start 内 :22-38)内;`http.close()` 无 throws 声明(BridgeHttpServer.java:111);`journal.close()` 在 `catch(Exception)` 内(:69) | 低 |
| :43 | `.filter(ExternalBodyAccess::reserved)` | static 方法引用,`all()` 返回 Collection → stream ✓ | 低 |
| :47-48,:54,:61 | `kernel.publish(String, Map)` 传 `Map.of(...)` 混合类型 | 目标类型 `Map<String,Object>` 驱动推断,合法;`death` payload 7 对(Map.of 上限 10) | 低 |

**汇总:未发现必然编译失败的引用。**

## 三、核心 6 文件 API 契约摘要

- **BodyBackend.java**(接口):`boolean ready()` / `String bodyId()` / `String observeJson()` / `Handle start(String operation, String argumentsJson)` / `void pause()` / `void resume()` / `void cancel(String reason)`;嵌套 `interface Handle { Snapshot snapshot(); }`;嵌套 `record Snapshot(String state, double progress, String reason)`(紧凑构造器校验 state ∈ 6 值、progress 夹紧 [0,1])。
- **BridgeFault.java**:`class BridgeFault extends RuntimeException`,`public final int status; public final String code`,构造器 `(int, String)`。
- **BridgeKernel.java**:`claim(String[, String])` / `renew(String)` / `release(String)` / `status()` / `observe()` / `execution(String)` / `requestStatus(String)` / `submit(token, request, operation, arguments)` / `control(token, request, executionId, action)` / `publish(String kind, Map)` / `shutdown()` / `tick()` / `journal()`,全部返回 `Map<String,Object>` 或 void,全部 synchronized;`observe()` 返回的 Map 内嵌 `JsonOutput.Raw`。与适配层契约:MinecraftBodyBackend 只被 `tick()` 在服务器线程调用(:226-273),`start()` 的 `BridgeFault` 被转为 failed(:257)。
- **BridgeHttpServer.java**:`BridgeHttpServer(BridgeKernel, int port, String bearer) throws IOException`(bearer 32..256 URL-safe)、`start()` / `int port()` / `close()`(无 throws);`journal().waitAfter(epoch, after, wait)` 支撑 `/v1/events` 长轮询。
- **BridgeJournal.java**:`BridgeJournal(Path, LongSupplier[, long maxBytes]) throws IOException`、`append(Map<String,String>)` / `replay()` / `lastSequence()` / `readAfter(epoch, after, limit)` / `waitAfter(epoch, after, waitMs) throws InterruptedException` / `close()`;`public final String epoch`。
- **JsonOutput.java**:`static String encode(Object)`(支持 null/Raw/String/Boolean/Number/Map/Collection)、`static String quote(String)`、`record Raw(String json)`。

契约一致性:MinecraftBodyBackend/ExternalBodyRuntime 对这 6 个类的调用(构造器参数、`publish`、`waitAfter` 未直接用)全部对得上;bridge-tests/BridgeCoreTest.java 离线编译已覆盖。

## 四、上游调用方与测试影响

**默认行为(无 `AIBOT_EXTERNAL_BOT`)**:`ExternalBodyAccess.enabled()` 为 false → `reserved()` 恒 false → 18 处插入点中,BrainCoordinator/IdleCoordinator/BotTickCoordinator/IntentController/AIBotServerNetworking 的 reserved 分支全部短路;`checkTool/checkAssignment` 不抛;`permitsLegacyOperation` 恒 true(`authorize` 结果 = `decision.allowed()` 原值);`ExternalBodyRuntime.start` 直接 return(:20),`tick` kernel==null return,`stop` 全 null 无操作。**唯一持续性差异**:`ToolRegistry.get()`(:86)现在每次返回包装后的**新 ToolDefinition 实例**——上游唯一调用方 ActionDispatcher.java:88 只做 `orElseThrow` 后 `handler().invoke(bot, args)`,不做 == / equals 比较,行为等价。

**上游测试(65 个,`src/test`)**:4 个"源码契约测试"直接读取被改文件文本,逐一分析:
1. `brain/ToolRegistryStrictMiningBoundaryTest.java`(:28-41):断言 `legacyMiningTaskRejection("` ≥2、`legacyMiningTaskRejection(taskType)` 在 `createTask(...)` 之前——这些模式都在 ToolRegistry.java 第 259/782/809 行(补丁插入点第 86 行之后),文本插入不改变相对顺序;`occurrences == 2` 断言的是 AIBotTaskSubcommand(未修改)。**不受影响**。
2. `goal/MiningPlanningSourceContractTest.java`(:80):截取 `oreTargetsFrom` 到 `private static String escape` 的区间断言 contains——插入文本不含任何被断言字符串。**不受影响**。
3. `mode/PrivilegedBoundarySourceTest.java`(:90):断言 ToolRegistry.java `contains("ObservableWorldQuery")`——原文件已含,插入不减。**不受影响**。
4. `mining/MiningEvidenceAuditTest.java`(:160):读 RuntimeLifecycleCoordinator.java 做 contains 断言——插入行 `RUNTIME.death(bot);` 不含任何被断言的反模式。**不受影响**。

其余:`BrainCoordinatorControlContinuationTest` 只调 static 方法 `shouldContinueAfterControl/hasRuntimeWork`,锚点改的全是实例方法;gametest 源集(如 AIBotVerifySubcommand 引用 `handleMessage/routePlayerControlPhrase`)依赖的方法签名均未变(只在方法体首部插入)。**结论:上游单测预期全绿。**

## 五、dsh-plugin 依赖与安装器风险

**install_dsh_plugin.py(:1-20)**:校验 dsh HEAD == `5dda764e...`(:10-11)→ `shutil.copytree(dsh-plugin → <repo>/scratch-aibot-body, ignore=node_modules,.env)`(:14)→ 写 `scratch-aibot-body/cordis.yml`(:15-16,内容 `- insert: id: aibot-body, name: <绝对路径>/src/index.ts`,路径经 `json.dumps` 转义反斜杠,YAML 双引号串合法)→ 提示 `pnpm dsh web --patch <overlay>`。不修改 dsh core ✓;已存在即拒绝(:13)。

**依赖清单(需真实 dsh 环境验证的标注 ★)**:
- `index.ts`:`@deepseek-ai/cordis`(type Context)★、`@deepseek-ai/dsh-tools`(defineTool)★、`@deepseek-ai/dsh-llm`(createUserMessage)★、本地 `./plugin.mjs`。★ 项在补丁包 package.json 中**无 dependencies 声明**,完全依赖 dsh checkout 自身 node_modules 解析;文件内注释(:6-7)自认"必须在真实 checkout 中测试"。
- `plugin.mjs`:仅 node 内置(path、timers/promises)+ 本地 3 文件;但运行期消费 `ctx.sessionPersistence.flush/stat`(:11)、`ctx.effect`(:34)——cordis Context 契约 ★。
- `client.mjs`:node:crypto + **全局 fetch**(:64,Node ≥22,engines 已声明)。
- `events.mjs`:node:timers/promises。
- `cursor.mjs`:node:fs/promises、node:path、node:crypto。

**安装器风险点**:cordis.yml 的 insert schema 与 `--patch` flag 是否存在 ★;`.ts` 入口能否被 dsh 加载器编译 ★;Windows 绝对路径注入 ★;`pnpm` 可用性 ★。本地方可离线验证的只有 4 个 `test/*.test.mjs`(node --test)。

## 六、总体结论

**预期编译:高度可能直接通过。** 10 个 blob sha 与 18 条锚点规则在冻结 checkout 上全部成立(含 BotAuthorizationGate 的两步顺序替换链);3 个适配文件引用的约 25 项上游 API(类/方法/构造器/枚举常量/record 组件)逐一核实存在且签名匹配;6 个核心 overlay 文件与适配层契约自洽;`aibot-overlay/src/main/java/...` 复制目标正确落入 main source set;默认(无环境变量)行为与上游等价,65 个上游单测与 gametest 签名均不受影响。

**最可能的前 3 个问题(按概率排序)**:
1. **锚点链脆弱性(脚本层,非编译层)**:BotAuthorizationGate 规则 13/14 的"先全量替换再修正变量名"耦合——任何对规则 13 new 串的微调都会使规则 14 失配(表现为 REFUSED,安全失败但阻断 apply);且规则 13 单独应用时第 96 行会引用不存在的 `bot` 变量,必须依赖规则 14 才能编译。
2. **Yarn 映射细节**:适配文件使用的 `World.getHeight()/getBottomY()`、`DamageSource.getName()`、`PlayerInventory` 遍历等虽均有上游同型旁证,但最终仍需真实 loom classpath 编译确认无重映射偏差(尤其 `getServerWorld` 的返回类型 `ServerWorld` 在 `markPlace` 第三参处的重载解析)。
3. **dsh 侧 3 个 `@deepseek-ai/*` 包导入**:index.ts 的 `defineTool/createUserMessage/Context` 导出名与 cordis.yml `--patch` 机制只能靠真实 dsh checkout 验证,是整个链路中唯一完全未经任何离线编译/测试覆盖的部分。