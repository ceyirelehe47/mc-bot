# 06｜来源与定位（不是新的运行报告）

制作日期：2026-09-21。GitHub connector本次刷新确认分支仍指向 `8626013b4a829900bc001f00349e3984130f7d21`。本包没有运行Windows、Minecraft、Fabric、JUnit或GameTest；本包QA和checker历史反例均为离线Python。

## A. 主要依据

1. 用户提供的MC-RCF-1原计划和R1修复包。原文逐字节复制到reference/original与reference/r1，并记录源ZIP与SHA-256。
2. 用户提供的 `MC-RCF-1-R1_Independent_Review_8626013.zip`。R01–R08及四组反例是本轮直接依据；原文保留，不伪装为本轮游戏复测。
3. GitHub固定HEAD上的RESULT与源码。提交的自报PARTIAL/G4阻塞/G5和replay未跑只按报告内容记载，不视为独立LIVE成绩。
4. 历史MC-2A0.8交接中的语义/准星边界仍适用；其中旧停点、固定工具数/文件白名单/外部attestation前置等已被后续RCF计划取代，不恢复。

## B. 定位表

前缀：`aibot-dsh-m0/aibot-overlay/src/`。

| 审查项 | 当前定位 | 已知问题或要复核的事实 |
|---|---|---|
| R01 | tools/rcf1_tests_v.py | judge_ia/judge_g4误接受；helper未接入主判断；V10局部stub |
| R02 | main/java/io/github/zoyluo/aibot/external/realclient/RealClientOpportunityTracker.java | sweep先于校验并写birth；lazy eviction无receipt；replay跳过birth |
| R02 | 同目录RealClientBodyBackend.java | inspectLocalJson触发tracker.observe，来源仍写crosshair validated |
| R03 | client/java/io/github/zoyluo/aibot/client/realclient/RealClientActionController.java | EatAction准备期PICKUP后贯穿消费收尾 |
| R04 | tools/rcf1_tests_ia.py、tools/rcf1_tests_n.py | I06空cursor断言/I08非Tom’s负例/A10未证阶段/N01台阶未抬高 |
| R05 | RealClientActionController.java | finishAction未覆盖普通complete/fail/格式错误全部出口 |
| R06 | tools/rcf1_lifecycle.py | stop无记录、已验证实例早退、全局预算被另端成功清零 |
| R07 | main/.../realclient/RealClientExecutionDriver.java | craft完整批次与材料、place归因和等待分支、move数量组件 |
| R08 | deliveries/mc-rcf1-r1/ | 源码/运行绑定不完整，报告计数多于提供的原始证据 |

导航适配器与RealClientInventoryOps可复用；不要求换导航库或再建一套背包系统。修改接缝时追溯调用者，不仅改表中单个函数。

## C. 本次额外读取：G4 runner是未完成调试骨架

文件 `tools/rcf1_g4.py`，blob `e55d2fd74143f8a50af9ddd2b72cf96b1598603a`。

可静态观察到：run_b01骨架有在isinstance条件和真分支中重复调用s.do的表达式；不同路径对inventory的结构假设不一致；部分辅助函数仍可能创建独立Session。它们是本轮额外定位线索，**不宣称都已在游戏中触发**。正式runner需核对真实响应与owner生命周期，保留单次发出/单一控制者/有界恢复，不复制旧半成品。

## D. GitHub固定入口

- 分支： https://github.com/ceyirelehe47/mc-bot/tree/experiment/rc-foundation-v1
- 审查提交： https://github.com/ceyirelehe47/mc-bot/commit/8626013b4a829900bc001f00349e3984130f7d21
- R1 RESULT： https://github.com/ceyirelehe47/mc-bot/blob/8626013b4a829900bc001f00349e3984130f7d21/deliveries/mc-rcf1-r1/RESULT.zh-CN.md
- 原checker： https://github.com/ceyirelehe47/mc-bot/blob/8626013b4a829900bc001f00349e3984130f7d21/tools/rcf1_tests_v.py
- G4骨架： https://github.com/ceyirelehe47/mc-bot/blob/8626013b4a829900bc001f00349e3984130f7d21/tools/rcf1_g4.py

本包的重复数、顺序和停止约束是任务要求，不是对当前已实现能力的事实描述。源码可达错误路径、实际离线复现、自报LIVE和未来必须实现的行为在报告中也应分别标明。
