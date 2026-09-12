# AIBot + DSH：外接大脑实验源码包 M0

**当前交付是源码、精确版本补丁脚本和已运行的离线测试，不是可直接安装的 JAR。**

已经写好 Java 控制桥、AIBot 适配源码、DSH 插件与故障测试。真实 Minecraft 类路径下的完整编译、真实 DSH 加载以及进入游戏的验收尚未执行。当前容器无法解析 GitHub 域名，不能获取完整工程和依赖；限制记录在 `evidence/network-availability.txt`。

下一位实现 Agent 应从现有代码接手，而不是从头再写。入口是 [GLM 接手文档](docs/GLM_HANDOFF.zh-CN.md)。准确验证状态见 [VERIFICATION.json](VERIFICATION.json)，行为细节见 [实现说明](docs/IMPLEMENTATION.zh-CN.md)。

## 这版怎么工作

```
DSH 的真实 Session / Agent
        ↕ 原生工具 + 持续事件读取 + 持久化 barrier
DSH AIBot 插件
        ↕ 仅 loopback HTTP 命令/查询 + 独立长轮询事件通道
Java BridgeKernel / Journal
        ↕ 只在 Minecraft 游戏线程调用
AIBot 原有 Task、机制和安全控制
```

内置 AIBot 大脑对指定 Bot 停用；现有 Task、寻路、采集、合成、安全抢占继续使用。事件读取不是模型工具循环；任务已接受后 DSH 结束当前模型回合，重要事件再推动下一步。

第一版只做一具 Bot。没有 Iris Context/Memory 集成，没有 MCP 或其他 Agent 平台适配，也没有大规模 Task 重构。

## 已提供的 DSH 工具

连接/诊断：`mc_connect`、`mc_observe`、`mc_status`、`mc_request_status`、`mc_release`。

行动：`mc_goto`、`mc_gather`、`mc_craft`、`mc_smelt`、`mc_eat`、`mc_set_base`、`mc_deposit`、`mc_say`。

控制：`mc_pause`、`mc_resume`、`mc_cancel`。

注意：`gather` 是背包总配额；`goto` 可能挖地形，必须显式同意；`say` 仅发送到 AIBot 面板。任务完成不保证用户整体目标完成，应重新观察验证。

## 离线测试

需要 Java 21+、Node 22+ 和 Python 3.10+；不需要 npm install、Gson jar 或 Minecraft。

```bash
bash scripts/test.sh
```

它会编译六个无 Minecraft 依赖的生产 Java 文件及两个测试类，运行 Java 状态/日志检查、Node 测试、真实 Java↔Node HTTP 集成和补丁脚本安全测试。**HTTP 集成使用假游戏世界；DSH 对象也是明确的测试替身。** 日志保存在 `evidence/`。

不要把本命令的成功当作真实模组 GameTest 或可游玩证明。完整上游的验证要由联网环境接着完成。

## 接入真实源码的顺序

精确基线在 `BASELINES.json`。在干净的 AIBot 基线 checkout 上先验证，再应用：

```bash
python3 scripts/apply_to_aibot.py --repo /absolute/path/to/aibot
python3 scripts/apply_to_aibot.py --repo /absolute/path/to/aibot --apply \
  --patch-output /absolute/path/to/aibot-external-body-m0.patch
```

脚本核对十个上游文件的 blob，写入九个新 Java 文件。不创建远端分支、不提交、不推送、不部署。首次应用后别重复运行。随后在真实工程运行 `./gradlew compileJava test`，并执行实际 GameTest。

安装 DSH scratch 插件：

```bash
python3 scripts/install_dsh_plugin.py --repo /absolute/path/to/deepseek-harness
```

在固定 DSH 版本完成其官方安装/构建后，从 DSH 根目录运行脚本打印的命令：

```bash
pnpm dsh web --patch /absolute/path/to/deepseek-harness/scratch-aibot-body/cordis.yml
```

**以上路径尚未在真实工程跑通。** 不要跳过编译、组装验证直接拿重要存档测试。

## 首次实机配置（供接手者验证）

使用隔离测试世界及新 Bot，例如 Bob。将 AIBot 原有运行 profile 设为 `strict_survival`，不要打开 operator 特权。配置文件路径和服务器启动方法沿用固定版本上游说明。

Minecraft 进程环境：

```bash
export AIBOT_EXTERNAL_BOT=Bob
# Iris/DSH 的稳定身体身份；默认取 bot 名的小写形式。不要绑定 Minecraft UUID。
export AIBOT_EXTERNAL_BODY_ID=bob
export AIBOT_BRIDGE_PORT=8765
# 生成一次，把同一密钥安全地提供给 Minecraft 和 DSH；不要提交或贴出值。
export AIBOT_BRIDGE_TOKEN="$(python3 -c 'import secrets; print(secrets.token_urlsafe(32))')"
```

DSH 进程需要相同的 `AIBOT_BRIDGE_TOKEN`，以及：

```bash
export AIBOT_BRIDGE_URL=http://127.0.0.1:8765
# 可选。请放在本次实验专用目录，不与其他实例共享。
export AIBOT_DSH_STATE_DIR=/absolute/path/to/private-aibot-dsh-state
```

不要在另一终端重新生成一个不同密钥。服务跨机器时使用受控隧道，不把桥监听改成 `0.0.0.0`。

先按 AIBot 现有方式生成并确认 Bob。桥不会自己生成玩家；Bob 不存在时 `body_ready=false`。可用下面命令诊断：

```bash
node scripts/inspect_bridge.mjs status
node scripts/inspect_bridge.mjs observe
```

`observe` 除了读新鲜缓存，还会解除一次“必须先对账”的门槛，仅应在你确实准备重新检查身体状态时调用。

通过真实加载验收后，在 DSH 里首先试：

> 连接 Minecraft 的 Bob，检查状态。只收集4个原木；操作接受后等待身体事件，不要反复轮询。完成后检查背包并向我汇报，暂时不要做其他任务。

然后再尝试从实际背包制作木板/工作台/木镐。不要第一轮用“完全自主通关”掩盖基础接口问题。

## 暂停、退出和安全

`mc_pause` / `mc_cancel` 对准具体执行标识。只停止模型生成不等于停止身体。关闭 Agent 会话或释放连接会释放租约；失联后等待租约过期，下一游戏 tick 暂停普通工作；不会重新启用内置大脑。

为避免用户暂停后终态通知又让模型干活，暂停/取消后的通知只注入、不自动唤醒。恢复或新行动才恢复自动推进。

重启服务器后未完成任务变成未知，不自动重新开始。应查询旧请求/执行、观察身体，再决定后续。丢失回执时禁止换一个新请求标识盲重试。

## 必须提前知道的边界

- 启动环境选择 external，不支持动态模式切换；其他未指定的 Bot 不应改变行为。
- 旧存档的活动 Mission 恢复尚未验证，新的分配护栏可能与旧恢复器冲突。**不要直接迁移重要存档。**
- 只有八个动作；没有额外打猎、全自动装备链、建筑或 mine_ore 接口。
- 最近伤害来源不是完整死亡因果链；普通健康变化也不能冒充明确伤害来源。
- 日志32 MiB、执行1024、控制2048、事件重放4096条，有上限且无自动轮换；容量不足会拒绝/暂停，不静默删除证据。
- 事件通过真实 DSH 的公开 flush barrier 后才保存游标，但仍可能重复投递；不是跨两个系统的 exactly-once 事务。
- 此源码包没有修改远端仓库，没有任何部署结果。真实使用效果必须由接下来实测确认。

## 包内容完整性

发布时的文件摘要在 `SHA256SUMS`。从包根目录执行：

```bash
sha256sum -c SHA256SUMS
```

重新运行测试会更新证据文件，届时这些文件与发布时摘要不一致是正常现象。不要通过覆盖旧证据伪装原始发布结果。

## MC-2A0.4：真实客户端身体 MVP

服务器端显式选择唯一 physical authority：

```bash
export AIBOT_EXTERNAL_BOT=Bob
export AIBOT_EXTERNAL_BODY_ID=bob
export AIBOT_EXTERNAL_BACKEND=real_client
export AIBOT_REAL_CLIENT_PORT=8766
export AIBOT_REAL_CLIENT_TOKEN="$AIBOT_BRIDGE_TOKEN"
```

Bob 客户端是独立的 offline profile，不使用拥有者的 Microsoft/Minecraft 账户。客户端进程还需：

```bash
export AIBOT_REAL_CLIENT=1
export AIBOT_REAL_CLIENT_BOT_NAME=Bob
export AIBOT_REAL_CLIENT_BODY_ID=bob
export AIBOT_REAL_CLIENT_HOST=127.0.0.1
export AIBOT_REAL_CLIENT_PORT=8766
export AIBOT_REAL_CLIENT_TOKEN="$AIBOT_BRIDGE_TOKEN"
```

`scripts/real_client_supervisor.py` 根据配置数组启动/重启客户端，计算标准 offline UUID，且
不经 shell 拼接命令。MVP 只支持 `say`、短距离 `goto` 与 explicit opportunity
`mine_opportunity`；其余现有 DSH 工具会得到 `operation_not_supported_by_backend`，绝不偷偷
回落 FakePlayer。GUI、模组 Screen、BOT_POV、音频与直播不在本轮。

真实客户端崩溃或断线会将在途执行置为 `outcome_unknown:body_session_changed`，吊销旧租约、
清空旧认知读平面并要求显式 `mc_observe`；重连后不自动重放任何 mutation。

发布前必须用 `python scripts/gen_sums.py` 重新生成 `SHA256SUMS`。canonical manifest
必须排除 `SHA256SUMS` 自身，再执行 `sha256sum -c SHA256SUMS` 验证全部条目。

