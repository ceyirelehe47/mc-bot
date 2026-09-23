# 06｜来源、现状审查与技术候选

## 1. 证据级别

- **仓库事实**：本包制作时通过 GitHub connector 读取的文件或 Git 元数据。
- **报告自述**：`EXPERIENCE.md` 的运行叙述，不等同于独立 LIVE 验收。
- **静态判断**：根据源码调用路径作出的推论；不声称已在当前环境复现。
- **新计划要求**：本包规定的路线和验收，是未来要实现的内容。
- **外部官方资料**：用于确定导航候选和凭证处置原则，不代表本项目已集成成功。

本包没有对用户电脑执行 Minecraft、Fabric build、账户密钥有效性测试或生产修改。

## 2. 最新仓库锚点

仓库：`ceyirelehe47/mc-bot`
审查 commit：`61aa6a85296d5722a80fc935bcc6ca53bf8bfec5`
分支：`experiment/mc2a0-8r1-acceptance-closure`

本次重新确认的远端 ref：
https://api.github.com/repos/ceyirelehe47/mc-bot/git/ref/heads/experiment/mc2a0-8r1-acceptance-closure

下列链接全部绑定审查 commit；仓库若继续变化，Agent 必须做差异盘点。

### R01｜运行经历
https://github.com/ceyirelehe47/mc-bot/blob/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/EXPERIENCE.md

报告称：改用会话内 LLM 直控，曾做出木镐/木斧，夜晚死亡反复丢失进度，放置待实战。它同时声称所有行为走真实客户端；R03/R04 显示合成例外。不能单凭报告判断三天耗时分布或真实成功率。
Git blob：`a65953c6f75a872bb803478a1b515f1c8bb8d470`。

### R02｜仓库与活工作区
https://github.com/ceyirelehe47/mc-bot/blob/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/README.md

说明 DSH 不再参与运行，活工作区在 `D:/code/mc-experiment`，仓库是档案/工具与 overlay 并存。本轮应核验实际源码，不自动把 README 中泛用操作清单当 Real Client 可用能力。
Git blob：`ef487a038c82851fbdba78519187821f691a063b`。

### R03｜服务端 Real Client 动作
https://github.com/ceyirelehe47/mc-bot/blob/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/realclient/RealClientExecutionDriver.java

定位与静态发现：
- `OPERATIONS` 已含 8 个业务动作，不再是旧版仅四个。
- `startCraft` 直接执行 `InventoryCrafting.execute`，完成原因叫 `server_side_inventory_transformation`。
- `startEat` 查全主背包，发送槽位却是 `slot<9 ? slot : 0`。
- `startPlace` 选任意 BlockItem，`placeSnapshot` 以非空气作为主要完成条件。
- `startSmelt` 的请求 count 没传到客户端；`smeltSnapshot` 仅结合客户端完成和玩家输入减少。
- `startGoto` 接受 terrain 参数但没有把它加入客户端 command；存在准星 ±1 容差。
Git blob：`177d080909dc3d3cd6d12edc9e6ad6e3804ba9ac`。

### R04｜服务器合成实现
https://github.com/ceyirelehe47/mc-bot/blob/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/realclient/InventoryCrafting.java

`commit()` 直接写 main/offHand；3×3 条件允许背包持有工作台或附近扫描到工作台。它含材料扣减/容量预演，但不证明原生客户端合成链。
Git blob：`ede8bc163441c00f61d16c9c43be17d33396a683`。

### R05｜客户端执行器
https://github.com/ceyirelehe47/mc-bot/blob/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/aibot-dsh-m0/aibot-overlay/src/client/java/io/github/zoyluo/aibot/client/realclient/RealClientActionController.java

`walkTo/startBreaking` 为局部偏航/碰撞/挖穿；并非成熟路径计划。`PlaceAction` 瞄空气目标后点准星命中的面；`SmeltAction` 只检查炉子类型及固定槽位，而非完整目标 ownership 事务。应逐项实测与修复/禁用，不把源码注释当验收。
Git blob：`053ce79459241044f50fe4845786df1d527e0fa8`。

### R06｜游玩 API
https://github.com/ceyirelehe47/mc-bot/blob/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/tools/play.py

`submit` 在任意不成功时尝试 cancel 当前执行；`keepalive` 重获用固定 owner；存在历史实验目录 import 和重复/不可达代码。本轮先修错误分类和运行权威，再做上层稳定测试。
Git blob：`54ca98d49809b6a1ec43a3f44e337f8840658ae0`。

### R07｜避难与阶段编排
https://github.com/ceyirelehe47/mc-bot/blob/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/tools/survival.py

`dig_shelter_and_seal` 不强制所有步骤成功；调用者没抛异常就置 sheltered。`mine_at` 到指定位置后按最近同类型机会选目标，有目标串换风险。本轮以真实条件建立状态，旧时代脚本停用。
Git blob：`bce910e0c4701e3b9086106907b8fda44f3822bb`。

### R08｜未实测的继续扩展
https://github.com/ceyirelehe47/mc-bot/commit/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5
https://github.com/ceyirelehe47/mc-bot/commit/7a29fc4bcd47ad8840e7e20397f57098c940aec4

提交说明分别称铁器编排、冶炼待环境实战。它们不构成基础闭环已可靠的证据。

### R09｜安全风险记录
https://github.com/ceyirelehe47/mc-bot/commit/fbbc1b958807f1201d2626344221deae3d576b91
https://github.com/ceyirelehe47/mc-bot/tree/61aa6a85296d5722a80fc935bcc6ca53bf8bfec5/.build

前者提交说明涉及多类外部密钥的脱敏；后者包含被跟踪的租约状态文件。此包没有取出或附带这些秘密，也不提供含秘密的历史 patch；必须本地验证实际风险和处置状态。

## 3. 历史交接的使用范围

用户上传的旧交接《粘贴的 markdown (1)。md》记录了 0.8 尚未应用、由 ChatGPT 先写完整补丁、DSH 工具数/冻结文件限制等历史状态。这些已经不适合作为本轮实现状态和工作流要求。

仅延续仍有价值且与最新目标一致的边界：独立 Bob Real Client、无系统级输入模拟、正常客户端动作、无透视、精确准星/Screen 权威、FakePlayer 不透明接管、可重建和证据脱敏。

最新用户要求“计划包约束下 Agent 独立实现”优先；本包不提供生产 applier，不要求使用旧 exact-blob 补丁，也不让历史 attestation 缺失阻断所有本轮本地开发。

## 4. 外部技术候选：Baritone

官方 release：
https://github.com/cabaletta/baritone/releases/tag/v1.12.0
官方 release API：
https://api.github.com/repos/cabaletta/baritone/releases/tags/v1.12.0
官方 tag ref：
https://api.github.com/repos/cabaletta/baritone/git/ref/tags/v1.12.0

2026-09-20 查询到：
- release 描述支持 Minecraft 1.21.2/1.21.3，含 Fabric。
- `v1.12.0` tag 指向 `deae0f3094b145f2afd55ff4e4b573993ae2e5bb`。
- 候选运行资产：`baritone-api-fabric-1.12.0.jar`。
- 发布 API 为该资产提供的 SHA-256：`b3b36aa3d74c4df053d147ee9254b70c15f4d1e5e11a2766141a146eea3bd60b`。
- 官方候选下载地址：
  https://github.com/cabaletta/baritone/releases/download/v1.12.0/baritone-api-fabric-1.12.0.jar

**上述摘要来自发布元数据，不是本包对实际下载 JAR 做过的字节校验。** Agent 必须实际下载、校验并验证构建/运行依赖；发布资产并非不可变保证。不要从默认分支或 release target_commitish 推断兼容版本。

技术描述原文：
https://github.com/cabaletta/baritone/blob/deae0f3094b145f2afd55ff4e4b573993ae2e5bb/FEATURES.md

官方说明包含路径分段、A*、多种目标和地形处理；这为“优先接入成熟导航”提供依据。需要 Agent 自行核验的重点是与本项目输入隔离、单控制权、暂停/取消和无透视资源选择的兼容性；不允许直接启用库的任意采矿目标搜索。

## 5. 外部安全说明

https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository

GitHub 官方指出，真实秘密暴露后首先应撤销/轮换；历史重写有协作与重新污染风险，且并不自动清除全部副本。本包因此不授权 Agent 盲目 force push，而把本轮新分支的安全发布和共享历史处理分开。

## 6. 不确定性

未在本环境验证：实际游戏运行 jar 与 Git 完全一致；Baritone 与现有 Mixins 共存；三天耗时/模型成本/真实失败占比；报告中的每次木器产物；外部凭证已失效；最新快照的全量回归结果。

这些必须在对应门里获取证据，不得用本计划包自身的完整性校验冒充。
