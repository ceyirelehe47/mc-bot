# MC-RCF-1-R1 覆盖账(COVERAGE)

分母=原 03_ACCEPTANCE 完整矩阵 + R1 补充 L/V 组。状态枚举:required/pass/fail/blocked/not_run。
本账开工时建立,随进度更新;最终值以最终交付版为准。

## C 组(8)
| ID | 入口 | 状态 | 备注 |
|---|---|---|---|
| C01 | rcf1_tests_c.py c01 | **pass** | R1-C 后最终候选重跑 |
| C02 | rcf1_tests_c.py c02(导航/采集/GUI 各一次) | **pass** | 须含 Baritone 停止证据,旧版只测按键 |
| C03 | rcf1_tests_c.py c03 | **pass** | request id 作用域核对 |
| C04 | rcf1_tests_c.py c04(前/后断线两态) | **pass** | |
| C05 | rcf1_tests_c.py c05 | **pass** | |
| C06 | rcf1_tests_c.py c06(eat/mine/cursor 三注入) | not_run | 须扩 cursor 注入 |
| C07 | ledger 轻量单测 | not_run | 非物理 |
| C08 | 实机并存观察 | not_run | 无多开玩家 |

## N 组(6×3)
| ID | 入口 | 状态 | 备注 |
|---|---|---|---|
| N01 | rcf1_tests_n.py | **pass** | fixture 修:台阶须高于地面 |
| N02 | rcf1_tests_n.py | **pass** | |
| N03 | rcf1_tests_n.py | **pass** | fixture 修:起点在 U 内部 |
| N04 | rcf1_tests_n.py | **pass** | |
| N05 | rcf1_tests_n.py | **pass** | |
| N06 | rcf1_tests_n.py | **pass** | fixture 修:目标本身可站但封闭 |
| terrain 三态 | rcf1_tests_n.py | **pass** | true 拒/false 行/缺省零挖零放 |

## I 组(8)
| ID | 入口 | 状态 | 备注 |
|---|---|---|---|
| I01 | rcf1_tests_ia.py | **pass** | fixture 修:初态断言主包索引≥9 且快捷栏无同款 |
| I02 | rcf1_tests_ia.py | **pass** | 修:1/7/31+目标已有10再移7+满堆;按本次增量判 |
| I03 | rcf1_tests_ia.py | **pass** | 修:组件/位置断言 |
| I04 | 新增(箱子/木桶双向) | **pass** | R1-I 实现 |
| I05 | rcf1_tests_ia.py | **pass** | fixture 修:真 36 槽满+容器满+能取不能放 |
| I06 | rcf1_tests_ia.py | **pass** | 修:三阶段 cursor 注入 |
| I07 | 新增(未知槽只读) | **pass** | |
| I08 | Tom's deposit 回归 | **pass** | |

## A 组(12)
| ID | 入口 | 状态 | 备注 |
|---|---|---|---|
| A01 | rcf1_tests_ia.py | **pass** | |
| A02 | rcf1_tests_ia.py | **pass** | 修:打开工作台精确 Screen |
| A03 | rcf1_tests_ia.py | **pass** | |
| A04 | rcf1_tests_ia.py | **pass** | |
| A05 | rcf1_tests_ia.py | **pass** | 修:部分效果核算 |
| A06 | rcf1_tests_ia.py | **pass** | |
| A07 | 新增(支撑面别格/外部抢放) | **pass** | V03 关联 |
| A08 | rcf1_tests_ia.py | **fail**(flaky) | 修:主包食物路径 |
| A09 | 独立实现 | **pass** | 删占位 |
| A10 | 独立实现 | **pass** | 删占位 |
| A11 | 新增(取消/死亡/换世界 GUI) | **pass** | |
| A12 | 控制器无透视负例 | **pass** | |

## L 组(10) R1 补充
| ID | 入口 | 状态 | 备注 |
|---|---|---|---|
| L01 | rcf1_lifecycle_fake_tests | not_run | 跨 OS 进程 |
| L02 | 同上 | not_run | 慢启动 |
| L03 | 同上 | not_run | spawn 前后崩溃 |
| L04 | 同上 | not_run | 状态损坏 |
| L05 | 同上 | not_run | 枚举失败 |
| L06 | 同上 | not_run | PID 复用 |
| L07 | 同上 | not_run | stop 失败 |
| L08 | 同上 | not_run | 预算绕过 |
| L09 | 实机 60s 观察 | not_run | |
| L10 | 假测试与真状态并存 | not_run | |

## V 组(10) R1 补充
V01-V09 自动化反例(部分经注入,部分 LIVE);V10 策略单测。全部 not_run。

## B 组(G4)/S 组(G5)
not_run;依门序。

## 终值(2026-09-21 R1 收尾)
- C01-C05:pass(C 组 5/5,证据 live/c-group-20260921-083259.json)
- N01-N06+terrain 三态:pass(N 组 18/18,证据 live/n-group-20260921-080552.json)
- I01-I08:pass(含 I04 容器双向新增);A01-A12:A08 flaky fail(曾过),
  其余 pass;A09/A10 已独立实测(占位删除)
- V01/V02/V04:LIVE pass;V03=A07b pass;V05/V07/V08/V09 语义经对应 LIVE
  用例覆盖(2x2 错屏/约束验证/A09A10/own screen);V06 部分证据=C02;
  V10:not_run
- L01-L10:pass 16/16+实机 5/5
- V10/V10b:pass(策略单测);checker 反测试 H0-H10:14/14
  (tools/rcf1_tests_v.py,离线,变异必拒)
- C06(完整三阶段)/C07/C08:not_run;B(G4):BLOCKED(链编排竞态,
  机会系统 7 根因已修+单环节全通);S(G5)/fresh replay:not_run
- 分母:C 8(5 pass+3 not_run)/N 7/I 8/A 12(11+1flaky)/L 11(全 pass)
  /V 10(3 LIVE+5 语义+2 部分)/B 5/S 2/replay 1
