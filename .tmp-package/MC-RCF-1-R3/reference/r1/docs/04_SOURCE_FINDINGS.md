# 04｜源审查定位与依据

本包制作时通过GitHub连接重新核对：`experiment/rc-foundation-v1`仍指向 `ecab2f60cc4c6b2f72cd68cb537554de16a0f23b`。审查只读，不在用户Windows上运行Minecraft。下列为该精确快照的代码事实/可达路径分析；并非已经复现的全部实机事故。

## 1. 关键定位

| 位置（相对仓库） | 直接读到的事实 | 本轮要求/需要复现的推论 |
|---|---|---|
| `tools/rcf1_lifecycle.py`：`_spawn` | Popen在进程枚举和state写入之前 | 枚举失败或管理器崩溃可留下未登记子进程；L03验证。 |
| 同文件：`_load_json` / `start` | 读取/解析异常回退默认；无记录不查完整本轮实例清单 | 不明状态不得按空环境启动；L04/L05。 |
| 同文件：`stop` | 部分STOP_FAILED后仍到尾部_record_success | 失败预算/未结状态不能被清空；L07/L08。 |
| `tools/rcf1_lifecycle_fake_tests.py` | reset_state使用导入模块默认状态路径；并发用threading | 假测试必须独立namespace，新增真正跨OS进程和管理器故障。 |
| `RealClientActionController.java`：control/timeout/session清理 | 清输入/关闭界面，没有统一调用导航stop | Baritone持有任务不能只清键；R1-C及最终C组复测。 |
| `RealClientNavigation.java` | ensureConstrained第一次后提前return；pathTo只设GoalBlock；goalReached按无pathing | 设置只生效一次、无任务不等于到达；报告“双模式”与仓库源码需对账。 |
| `RealClientExecutionDriver.java`：craftSnapshot | `delta >= want || delta >= batches` | 32块/8批反例可能放行仅8新增；V01。 |
| 同文件：placeSnapshot | 验证块类型后把before/after写reason，未要求消耗作为成功门 | 外部同块抢占/无本次动作不能completed；V03。 |
| 同文件：eatSnapshot；客户端EatAction | `after < before || hunger > hungerBefore`；`held.getCount() < 64` | 布尔条件不能证明本次消费；V04。 |
| 同文件：moveItemsSnapshot；客户端MoveItemsAction | 目标已有足量就completed；只比较item ID/目标最终量 | 精确本次增量、组件身份及保护缺证；V02/I02/I03。 |
| `RealClientInventoryOps.java`与客户端各动作 | 映射常量为个人屏9/36与表屏10/37；若干阶段仍用个人常量 | 当前handler不能默认个人屏；跨屏映射需复现和修复；V05/I06。 |
| `tools/rcf1_tests_ia.py`：a09_a10_negative | 无实际检查，直接pass=True并计总数 | 删除占位PASS，分别实际执行A09/A10。 |
| 同文件：I01/I03/I05 | 给物后快捷栏搬家、同ID计数、约六槽称满包 | fixture缺乏真正触发条件，需重做并验证初态。 |
| `tools/rcf1_tests_n.py`：N01 | 台阶放在P_FLOOR与平台同高 | 不证明一格台阶，修装置与轨迹断言。 |
| `deliveries/mc-rcf1/RESULT.zh-CN.md` | 自报PARTIAL但G0–G3 PASS；G4矩阵未开始，G5未运行 | 完整覆盖不能接受，保留成果但纠正门状态。 |
| `deliveries/mc-rcf1/BASELINE.md` | build到GameTest失败但jar产生；记录650/605/45 | 本轮须重新绑定最终测试与产物，不继承全绿结论。 |

Java定位前缀：客户端为 `aibot-dsh-m0/aibot-overlay/src/client/java/io/github/zoyluo/aibot/client/realclient/`；服务端为 `aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/realclient/`。

## 2. 固定快照阅读入口

- [上轮RESULT](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/deliveries/mc-rcf1/RESULT.zh-CN.md)
- [上轮BASELINE](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/deliveries/mc-rcf1/BASELINE.md)
- [上轮DECISIONS](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/deliveries/mc-rcf1/DECISIONS.md)
- [生命周期](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/tools/rcf1_lifecycle.py)
- [生命周期测试](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/tools/rcf1_lifecycle_fake_tests.py)
- [I/A测试](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/tools/rcf1_tests_ia.py)
- [导航测试](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/tools/rcf1_tests_n.py)
- [服务端执行驱动](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/realclient/RealClientExecutionDriver.java)
- [客户端动作](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/aibot-dsh-m0/aibot-overlay/src/client/java/io/github/zoyluo/aibot/client/realclient/RealClientActionController.java)
- [导航适配](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/aibot-dsh-m0/aibot-overlay/src/client/java/io/github/zoyluo/aibot/client/realclient/RealClientNavigation.java)
- [背包辅助](https://github.com/ceyirelehe47/mc-bot/blob/ecab2f60cc4c6b2f72cd68cb537554de16a0f23b/aibot-dsh-m0/aibot-overlay/src/client/java/io/github/zoyluo/aibot/client/realclient/RealClientInventoryOps.java)

## 3. 保留什么，不能把什么当已验收

保留Baritone接入、原生合成与新增生命周期结构的有效实现；它们是修复基础，不要求重写。已提交局部成功摘要可做历史参考，但不能单独证明最终候选的控制、数量、Screen、核心链或过夜。

G4/G5范围、原8+6+8+12分类用例、5次链与2次夜来自原MC-RCF-1正文，不是从旧0.8交接推导。R1-L具体故障、V组细化和12小时修复预算是本包新增/明确的验收要求，不是声称上轮已承诺这些具体编号。

历史0.8交接与本轮时间/工作流不同；不带入其固定文件白名单、旧冻结状态或固定DSH数量。它不替代本页固定GitHub快照与原MC-RCF-1正文。
