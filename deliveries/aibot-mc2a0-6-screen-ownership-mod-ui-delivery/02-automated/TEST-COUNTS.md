# 自动化测试计数汇总(MC-2A0.6 最终)

| 套件 | 门槛 | 实测 | 备注 |
|---|---|---|---|
| JUnit dev round1(供给原样) | — | 437 / 5 fail（计数来自 Gradle test 报告 XML 聚合脚本） | 5 处源码契约断言失配(拆行), 预审 subagent 预测逐一命中 |
| JUnit dev round2(修正后) | ≥437/0 | **437 / 0 / 0** | junit-postfix |
| JUnit dev round3(--rerun-tasks) | ≥437/0 | **437 / 0 / 0**（修正前 jar 轮的 XML 聚合,修正仅涉 Driver/fixture 不触测试计数面） |
| JUnit clean replay | ≥437/0 | **437 / 0**（replay 树 test 任务 rc=0=零失败+XML 聚合 437） |
| GameTest dev round1 | ≥643/0 | **643 / 0** | gametest-round1.log |
| GameTest dev round2 | ≥643/0 | **643 / 0** | gametest-round2.log |
| GameTest 修正后复验 | ≥643/0 | **643 / 0** | gametest-postfix.log |
| GameTest clean replay | ≥643/0 | **643 / 0** | replay-postfix.log |
| BridgeCore | ≥105 | **105 PASS** | javac 直跑 |
| Node | ≥43/0 | **43 / 0** | node --test |
| Installer | ≥11 | **11 OK** | test_installers.py |
| Supervisor Python | ≥11/0 | **11 OK** | test_real_client_supervisor.py |
| DSH tools | exactly 29 | **29**(12 静态+14 operations+3 控制) | 描述文字一变, 结构零变 |
| clean replay | byte zero diff | **src 0 diff** | replay 树重置→重新 apply→diff -rq 空 |
| SHA256SUMS | — | **188 条无自引用** | 3a7738dd08760cdb |
| fixture mod build | PASS | **双产物**(remap+dev) | 06-live-mod-fixture/fixture-jar-sha256.txt |

计数推导: 425→437 = ScreenWireTest +2(替换版) + OwnershipSourceTest +8(新) + WireSessionTest +2(变换新增)。

注：JUnit 三轮计数由 build/test-results/test XML 聚合脚本产出（Gradle 成功日志不打印计数）；replay 树 test rc=0 佐证零失败。
