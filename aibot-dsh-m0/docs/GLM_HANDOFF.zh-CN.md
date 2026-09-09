# 交给 GLM-5.3 的接手任务：AIBot External Body → DSH M0

## 先读结论

用户已授权推进“以 AIBot 为身体、DSH 为外部大脑”的实验实现。当前交接包已经包含第一批真实代码和离线测试，不是让你从零设计。

**你的主要任务是：把现有源码覆盖文件接进冻结上游、修复真实编译/加载问题、运行真实 Minecraft + DSH 的最小场景，提交可重现证据。不要趁机重写任务系统或接入 Iris。**

交接包中 `VERIFICATION.json` 是验证状态入口。`README.zh-CN.md` 是运行顺序；`IMPLEMENTATION.zh-CN.md` 说明实际行为和限制。之前的社区评价、项目 README、自报 PASS 都不等于我们这版已经通过测试。

## 一、不可更换的起始版本

```
AIBot repository: zoyluoblue/mc_aiplayer
AIBot commit: a029fa6a3760fd0f83834c104051b041d986da60
Minecraft: Java Edition 1.21.3
Java: 21

DSH repository: deepseek-ai/deepseek-harness
DSH commit: 5dda764ed3aa172535a7967b06ff95d9cbfe536a
```

先用精确 SHA，不追 main，不把 master/main 文档中的新接口拼到旧代码上。DSH 的 Node/pnpm 要求以这个 SHA 的 package/toolchain 声明为准；本包离线 Node 测试使用 v22.16.0，**不是 DSH 整体运行的版本认证**。

尚未找到可用的用户 MC fork；`ceyirelehe47/mc_aiplayer` 读取返回 404。不要往 `iris_agent`、`iris-context` 或用户其他无关仓库塞这套代码。使用用户提供的可写目标；没有目标时保留本地分支和 patch。不要擅自部署到已有游戏服务器。

## 二、现成文件

```
aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/
  BodyBackend.java           游戏线程适配口
  BridgeKernel.java          控制租约、回执、状态、重启对账
  BridgeJournal.java         持久帧日志、互斥锁、CRC、有限重放
  BridgeHttpServer.java      仅 loopback HTTP，鉴权和独立事件读取
  JsonOutput.java             输出 JSON，不解析任意 Java 对象
  BridgeFault.java            边界错误
  MinecraftBodyBackend.java   AIBot Task 适配（未真实编译）
  ExternalBodyAccess.java     外部模式与旧入口护栏（未真实编译）
  ExternalBodyRuntime.java    模组生命周期/死亡/聊天接缝（未真实编译）

dsh-plugin/src/
  index.ts                   真正 DSH 原生入口（尚未组装加载）
  plugin.mjs                 工具注册、会话独占、生命周期
  client.mjs                 HTTP、私有令牌、幂等请求与未知回执
  events.mjs                 事件分类、DSH ingress、游标处理
  cursor.mjs                 持久接收位置

scripts/apply_to_aibot.py     十个上游精确 blob/锚点补丁 + 九个新文件
scripts/install_dsh_plugin.py DSH 本地 scratch overlay 安装器
scripts/test.sh               无联网依赖的测试入口
scripts/inspect_bridge.mjs    不取得控制权的状态/观察诊断
```

## 三、先验证已有代码，不先改设计

在解压目录运行：

```bash
bash scripts/test.sh
```

这个命令不下载 Minecraft，不启动真实 DSH，不调用 LLM。Java 核心与 HTTP 的测试使用假身体；DSH 的测试注入明确标为假的工具注册器和 Agent。不要把这些通过结果写成“真实 Agent 已能玩 Minecraft”。

然后准备两个隔离源码 checkout。示例中 `BUNDLE` 改为解压后目录，`WORK` 是干净工作路径：

```bash
export BUNDLE=/absolute/path/to/aibot-dsh-m0
export WORK=/absolute/path/to/mc-experiment
mkdir -p "$WORK"
cd "$WORK"
git clone https://github.com/zoyluoblue/mc_aiplayer.git aibot
git -C aibot checkout --detach a029fa6a3760fd0f83834c104051b041d986da60
git -C aibot switch -c experiment/dsh-external-body-m0
```

先按上游要求安装依赖并运行未修改基线的编译/测试，保存完整日志及退出码。之后：

```bash
python3 "$BUNDLE/scripts/apply_to_aibot.py" --repo "$WORK/aibot"
python3 "$BUNDLE/scripts/apply_to_aibot.py" --repo "$WORK/aibot" \
  --apply --patch-output "$WORK/aibot-external-body-m0.patch"
cd "$WORK/aibot"
./gradlew compileJava test
```

安装器默认仅检查，要求 exact HEAD、干净 tracked worktree、十个文件 blob 完全匹配。任何锚点失败都要查真实源码原因，不允许取消检查或全文件粗暴替换。首次应用后不要对同一个脏 checkout 重复运行安装器。

接着获取固定 DSH：

```bash
cd "$WORK"
git clone https://github.com/deepseek-ai/deepseek-harness.git dsh
git -C dsh checkout --detach 5dda764ed3aa172535a7967b06ff95d9cbfe536a
python3 "$BUNDLE/scripts/install_dsh_plugin.py" --repo "$WORK/dsh"
```

在该 SHA 按官方流程安装/构建 DSH，再加载生成的 patch。不要创建另一个独立 Agent loop，也不要让 AIBot 用 LLMProvider 回调 DSH。

## 四、本轮的 P0 工作顺序

### P0-A：真实编译和上游接缝

编译新增九个 Java 文件及十处补丁。优先修实际方法签名、泛型、枚举、imports、生命周期顺序。任何修正同步回交接包/分支，保留差异。不要因为编译失败而删除租约、幂等、来源或对账保护。

特别检查：

- `AIPlayerManager.all()`、`TaskStatus.from()`、Task 构造器、Gson、Minecraft API 与服务器线程调用是否完全匹配。
- External 模式没有任何内置 DeepSeek 请求，包括新消息、失败续推理、迟到响应、重生和配置重载路径。
- 默认没有设置 `AIBOT_EXTERNAL_BOT` 时，上游原有行为和测试不变。
- 外部模式的 Task 分配护栏不会阻断 `SAFETY`；`IntentController` 原有的暂停/恢复语义仍保留。
- 公共授权门、面板、命令、自动作业不能绕过外部控制。`@Bot` 和面板聊天仍经过原授权，且只能作为游戏数据进入 DSH。
- 服务停止时外部 shutdown 在 AIBot 持久化之前，避免外部普通 Task 被旧恢复器盲目续跑。
- 确认桥只绑定127.0.0.1；未配置密钥的启动失败不能退回内置大脑。

### P0-B：真实 DSH 插件组装

必须在真实 `dsh web` profile 测试，而不只是 TypeScript 检查或 mock。确认16个工具可见，`output.schema={type:'json'}` 能返回 canonical 值，参数和执行错误正确展示。

确认 `exec.concludeTurn()` 在已接受长任务后结束推理，但不取消身体任务。确认空闲终态使用 followup、忙碌紧急事件使用 steer、暂停后注入不自动唤醒。禁止进度事件每 tick 调用 LLM。

确认 `ctx.sessionPersistence.stat(agent.id)` 对 Web 会话成立，事件入队后的 `flush()` 确实包含该事件，成功后才落游标。不要通过开启第二个 Session write handle 解决 flush。公开服务已经提供 barrier。

确认 Agent dispose、插件卸载、重复连接、两个会话、同一个持久 Session 的两个进程等路径不会双控制或泄漏监听器。

### P0-C：隔离世界最小实机验收

先使用全新或没有未完成 Mission 的测试存档。名字例如 Bob。桥接是启动配置，不是已实现的 `/aibot brain mode external` 命令。

先做下面的基本串行链，不承诺铁装/钻石：

1. DSH `mc_connect` → `mc_observe`，核对 Bot UUID、维度、背包和位置。
2. 从就近可达树获取4个原木；先收到 accepted，再实际执行，最终由事件唤醒 DSH。独立观察背包配额，不用模型口头完成作为验收。
3. DSH 规划并执行木板、工作台或木镐所需的一小段合成，核对真实物品和材料消耗。
4. 采集过程中暂停、恢复、取消；取消旧 execution id 不能影响新任务。
5. 长任务中授权玩家聊天能够到达 DSH，内置大脑不参与。

再做故障场景：客户端网络中断、DSH 进程结束、控制租约失效、玩家离线、安全抢占、死亡重生、服务器停止和重启。每项分别收集真实世界状态、Body 回执/事件、DSH 日志、退出码。

让假身体测试通过只是必要条件；真实 Task 本身仍可能因上游的世界寻路/采集问题失败。记录第一次真实失败，不要为了让场景 PASS 偷偷改目标、送物品、打开 operator 能力或禁用安全系统。

### P0-D：证据交付

提交以下内容：

- 原始与修改后 commit、完整 diff、构建环境、依赖锁定信息。
- baseline 和修改后编译/单元测试原始日志；发现旧测试失败要区分基线失败与新增回归。
- 使用上游实际 GameTest 入口。先看 `./gradlew tasks --all` 与仓库说明，不凭印象编造 task 名称。
- 真正的 DSH 插件加载日志、一次工具调用 canonical 结果、事件导致的新模型回合，以及持久化 flush/游标顺序证据。
- 游戏场景 trace 和最终背包/世界状态；测试种子、出生点、模式、初始物品应写清。
- 一段中文结论，逐项区分 VERIFIED / FAILED / NOT_RUN。没有跑的绝不能写 PASS。

## 五、已经确定的约束

- AIBot 保持唯一的世界动作与本地安全执行者；DSH 是唯一高层大脑。没有第二个 LLM loop。
- 当前只控制一具指定身体，不扩多 Bot 管理。
- 不引入 Iris P0–P5、Historian、长期记忆服务、Capability Registry 或最终通用 Body 契约。
- 不做 MCP、A2A、NATS、Redis、WebSocket、多厂商插件矩阵；当前 HTTP + 独立事件读取足够完成这次实验。
- 不复制一份 AIBot 的 Task 系统；不把所有63个旧工具不加筛选暴露出去。
- 不把“HTTP成功”“函数未抛异常”“accepted”或“安全任务结束”当成用户目标完成。
- 状态/身份/参数细节在本包两端已经配套；为匹配真实上游做最小修正可以，但不要单端修改造成协议漂移。
- 不靠调高 timeout 掩盖死锁；不靠重新赋予旧 epoch 权限修复重连。
- 不把游戏聊天提升为系统指令，不把 Bearer/lease token 写进 Prompt、截图、日志或提交。

## 六、已知限制：不要自行隐瞒

1. 没有真正编译完整模组或加载 DSH 的证据；目前游戏/Agent 测试对象是假实现。
2. 旧存档有活动 Mission 时，新的 Task 分配护栏可能干扰上游 `assignSilently` 恢复。**先验证新 Bot。** 正式存档迁移列为后续独立工作，不允许以删除护栏解决。
3. 八个操作不足以保证从零铁装；`gather` 也只覆盖上游本来支持的物品。
4. goto可能挖掘，必须明确同意，优先隔离世界。
5. say暂时仅面板且占单操作槽；这是试玩限制，不是全服自然聊天功能。
6. 日志容量有限，无轮换；达到上限安全停止，不能删账本后继续伪装同一条执行历史。
7. 服务器重启会把未完成工作归为未知；没有跨重启自动恢复承诺。
8. `mc_request_status` 查不到未知请求时，没有暴露安全的同id恢复工具。先保持暂停和人工对账，后续再补，不能新id盲重试。
9. 伤害事件只是健康变化；死亡有最近伤害来源，但没有完整事故因果链。
10. 对话/记忆是 DSH 原生能力；尚未证明相较 AIBot 内脑有多大实际改善，要通过同场景实测判断。

## 七、停止条件

新世界基本链、暂停恢复、独立事件、旧脑隔离、玩家离线和 Host 断开通过后，打一个实验版给用户试玩。不要等做完整 Minecraft 自动通关，也不要把这个 M0 扩为重新设计整个 Iris。
