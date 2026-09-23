# COVERAGE — MC-RCF-1-R3C(分母=继承矩阵+R3C TASKBOOK;不缩分母)

| 项 | 状态 | 真实入口 |
|---|---|---|
| I01,I02a-d,I03,I04,I05(a/b),I06,I07,A01-A07,A08,A09-A12,V01,V02,V04 | PASS(28/29) | evidence/ia-facts-r3c.json;audit/ia-run-final3.log;judge-ia 唯一拒绝原因=I08 |
| I08 | **BLOCKED** | evidence/i08-blocked.json;5 组网实测零转移;上游 tom5454/Toms-Storage#381 |
| C01-C05 | PASS(LIVE 5/5) | audit/tests-c-r3c.log;evidence/tests-c.jsonl |
| C06 | PASS(继承等价) | I06 三阶段取消+C02 导航取消(R2 口径) |
| C07 | PASS(继承等价) | ledger 单测+checker v2 重复身份拒绝(selftest M15/ST-G4-M08) |
| C08 | NOT_RUN(condition-unavailable) | tests-c.jsonl not_run 行;未触碰用户 PCL2 |
| N01-N06 ×3 | PASS(18/18) | audit/tests-n-r3c.log;evidence/tests-n.jsonl |
| L01-L14(含 R3 L14) | PASS(20/20) | audit/lifecycle-fake-r3c.log;evidence/lifecycle.jsonl |
| V01-V09 | PASS(继承等价) | LIVE 语义分布于 IA(V01/V02/V04/A07b/A09/A10/A03/A12)与 C02;evidence/tests-v.jsonl 逐行指针 |
| V10 | PASS(7/7 离线真策略) | audit/tests-v-r3c.log |
| checker 自测 | PASS(31/31) | rcf1_checker_selftest(v2 判定逻辑+M 分支) |
| 成对变异 | G4 组 PASS(9/9 拒+正例受) | evidence/mutations-r3c/results.json;IA 组 INCONCLUSIVE(正例被 I08 阻断;语义分支由 selftest 覆盖,如实不冒充) |
| G4 B01-B05 | **PASS(5/5,accept=true)** | evidence/g4-runs-r3.json;audit/g4-runs-r3c.log |
| G5 S01 | **BLOCKED** | evidence/g5/g5r3-r3c-s01{,b,c,d,e}.jsonl(5 次尝试全留) |
| G5 S02 | **BLOCKED**(同源故障) | 与 S01 同因:白昼窗口 vs 链速;未启动独立 run(不重复必败条件) |
| 构建 | PASS | evidence/build-report.json(fresh 树 remapJar+selftest+lifecycle) |
| Java JUnit | not_applicable | R3 基线无独立 JUnit 运行器入口;覆盖由 GameTest 源集+离线自测承担(如实声明,不伪造数字) |
| fresh 部署 | PASS | evidence/fresh-deployment.json;REPRODUCE.md 全命令 |

## 继承口径说明

- C06/C07/V01-V09 的"继承等价"沿用 R2 COVERAGE 的映射口径,本轮
  把对应 LIVE 案例在全新部署重跑通过后才计 PASS。
- G5 BLOCKED 的失败尝试全部保留(5 份 jsonl),无选择性删除;
  死亡/重生记录如实含在 act 回执(outcome_unknown/body_session_
  changed)与 night-death 行中。

## 判定一致性

judge-all(11 门)输出与上表一致:7 过 4 拒(ia/i08/g5×2);
evidence/judge-all-manifest.json 可复跑。
