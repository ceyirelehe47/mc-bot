# COVERAGE — MC-RCF-1-R3

分母 = requirements/INHERITED_REQUIRED_ITEMS.txt(60 项)+ 原矩阵
原子条件;状态来自本轮真实入口,不以测试函数数组生成分母。

## G4/B 组(5/5 计分)
| 条件 | 状态 | 入口/证据 |
|---|---|---|
| B01 橡木 | PASS | g4-runs-r3.json b01;judge-g4 ok |
| B02 橡木 | PASS | 同上 b02 |
| B03 白桦 | PASS | 同上 b03 |
| B04 白桦 | PASS | 同上 b04 |
| B05 布局扰动 | PASS | 同上 b05(perturb: 采1木→4板→move_items 至 hotbar7) |
| 每次终态(空包/双镐/台位/cursor/无 unknown/≤720s) | PASS | judge-g4 全项重算 |
| FRESH-REPLAY | PARTIAL | 全新目录构建成功+内容逐项比较(0 class 差异);部署目录沿用受管环境 |

## I/A 组 LIVE(事实版)
| 条件 | 状态 | 证据 |
|---|---|---|
| I01-I06 | 本轮事实见 ia-facts-r3.json(逐用例快照/回执;判空者如实列缺) | judge-ia |
| I07 | (同上,随最终事实文件) | |
| I08 | IA 采集含 i08 场景(日志 ia-run-r3c.log);正式网络转移状态见日志 | |
| A01-A12 | 同 ia-facts-r3.json | |
| 变异(02 §3 表) | 11 项真实单因素变异全拒(成对探针) | mutations-r3 |

## C 组
| 条件 | 状态 |
|---|---|
| C01-C07 | NOT_RERUN(R2 记录为旧格式;本轮时间预算未重跑,不冒领) |
| C08 | NOT_RUN(无授权共存环境) |

## N 组
N01-N06 ×3 | NOT_RERUN(同 C 组说明)

## L 组(离线假进程)
L01-L13 19/19 + L14(R3 新增:stop 尾部同角色核验)→ PASS

## V 组
V10a-g(真实策略 fail-closed/空包清晨/植物液体/LLM 通道)
+ checker 自测 16/16 + CLI 入口 → PASS;V01-V09 LIVE 注入 NOT_RERUN

## G5/S 组
| 条件 | 状态 |
|---|---|
| S01 | BLOCKED(尝试链:场景修正后客户端侧游走,采伐未完成;详 FIXES) |
| S02 | NOT_RUN(S01 阻塞按预算不盲跑) |
| N-TERRAIN | PASS(N 组 R2 语义;本轮导航改动仅站位选择) |

## 自动化
- JUnit:479/479(rcf1-rebuild-base,含 R3 新增 4)
- GameTest:未重跑(R2 语义保留;本轮改动不含 gametest 目标)
- 洞内放置小切片:PASS
