# 范围审计(scope-audit)

## 变更面(生产 commit)

`git diff fca017b..<生产提交>` 仅含:

| 文件 | 变更 | 来源 |
|---|---|---|
| aibot-overlay/…/SemanticWorldRegistry.java | id 化身化(findActiveOpportunityAt/newOpportunityIncarnationId) | 0001 补丁 |
| aibot-overlay/…/TaskGraphStore.java | failed 分支 TERMINAL_UNSATISFIED→STALE | 0002 补丁 |
| aibot-overlay/src/test/…/OpportunityResolutionReceiptTest.java | 化身隔离测试+fields 重载 | 0001 补丁 |
| aibot-overlay/src/test/…/TaskGraphStoreTest.java | FAILED→STALE 正负分支测试 | 0002 补丁 |
| aibot-overlay/src/gametest/…/MC2A03OpportunityIncarnationGameTests.java | 新 GameTest(新文件) | 0001 补丁 + 本轮 fixture 修正(末尾清场) |
| aibot-dsh-m0/scripts/apply_to_aibot.py | fabric.mod.json 登记锚点加 MC2A03 一行 | 本轮 fixture 修正 |
| aibot-dsh-m0/SHA256SUMS | 150 项重生成 | 任务书 §6 |

## 补丁应用方式

两补丁上下文逐字符核对后以 `git apply --recount -C1` 应用(假行号窄上下文包的标准等价修复,
零语义增删;详见 RESULT 问题 2)。

## 未发生(禁项核查)

- 无 Agenda/后台调度器/新自治 producer;
- 无 Real Client / BotView 工作;
- 无 DSH 工具面变化(29 = 12 显式 mc_* + 14 operation 循环 + 3 控制,dsh-plugin 零 diff);
- 无 TaskGraphStore 模式/坐标墓碑代;无第二收据库;
- 图九状态模型、submit 唯一派发、重启不盲放、收据权威、startup 对账顺序全部保持。

## GameTest 计数说明

635(R1 基线)+ 1(mc2a03 化身测试)= 636,符合任务书预期(≥636)。
