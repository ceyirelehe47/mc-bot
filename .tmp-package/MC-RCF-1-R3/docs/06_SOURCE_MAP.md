# 06｜来源与定位（不是冻结白名单）

## 当前材料

基准：`ff29078ab3cd52d6cfceb928344c90e0c25ab2a7`，仓库 `ceyirelehe47/mc-bot`，分支 `experiment/rc-foundation-v1`。

本包制作时经授权GitHub connector再次读取分支和 `deliveries/mc-rcf1-r2/RESULT.zh-CN.md`，基准未变。此前独立审查原文及逐字节源码/离线反例在 `reference/review_ff29078/`。其中事实、离线复现、静态推导分别标明；不是本轮Fabric实机验证。

| 审查项 | 定位 | 本轮目标 |
|---|---|---|
| F01 | tools/rcf1_checker.py；rcf1_checker_selftest.py；tests_v | 从真实事实判定，与主入口同体，成对语义变异 |
| F02 | tools/rcf1_g4.py；delivery live/g4-runs.json | 真实cursor/ledger、运行身份、过程与计时，不硬填真 |
| F03 | tools/rcf1_g5.py；rcf1_shelter.py | 无armed后tp/特权决策、真实入洞/位置/清晨进度、两自然场景 |
| F04 | src/main/.../external/realclient/RealClientEatDecisionCore.java；RealClientExecutionDriver.eatSnapshot；客户端EatAction | 实际消费完成归因，不是同一次减法重复验 |
| F05 | RealClientBodyBackend.inspectLocalJson；RealClientOpportunityTracker.opportunities/markStale/replay | 纯读、可见性、有界输出、持久状态转换 |
| F06 | tools/rcf1_lifecycle.py stop/preflight/status；fake_tests | 全角色范围收尾，有记录仍查额外实例 |
| F07 | deliveries/mc-rcf1-r2/RESULT/COVERAGE/BASELINE/REPRODUCE与live/audit | 全分母、真实本轮文件、从锁定上游重建 |
| 继承恢复 | src/client/.../RealClientActionController.finishAction/restoreCursorThenClose/restoreCraftingGrid | 清理确认、恢复债务与下一动作准入 |
| I08 | RealClientStorageTarget、ScreenController、TomsStorage*Compat、测试fixture | 真正网络存入及工具保留 |

`src/main/...`/`src/client/...` 都位于 `aibot-dsh-m0/aibot-overlay/`，完整来源由本包参考review和仓库解析。

已核验审查源码blob：
- tools/rcf1_checker.py：`8ad34b34a0d3addf69f009544d6fb57b386dc630`
- RealClientEatDecisionCore.java：`173269283de55e2338fd7681c089e59a0af8afc5`

这些哈希锁定的是**历史反例的被测对象**，不是要求本轮禁止修改；修复后必须测试当前生产路径。历史示例的输入格式也不是应用协议。

## 事实与任务新增要求的区别

- 七组CLI误接受、Java外部移物误归因是前次离线实际复现；包内再跑能复核，不代表游戏LIVE已复现。
- G5两处armed后tp、硬填inside与清晨`<0`、读调用间接写账本、stop有记录漏额外实例是静态源码事实/推导；实际现场归因要补证据。
- “实现线/验证线分工、ARMED执行权限隔离、主checker覆盖G5、允许同真实session连续多run、成对变异入口”是本轮明确化要求，不冒称旧系统已有。
- 当前报告的G4成功摘要具有诊断价值；不等于已被独立正式接受。

本轮没有要求换模型、后端、MC版本或引入新外部服务。涉及精确第三方API兼容时实现者查锁定版本的实际源码/官方资料，并在实现记录中注明，不能把通用知识当成本项目的运行事实。
