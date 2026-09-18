# MC-2A0 审计：隐藏观察边界不放宽（hidden-observation-review）

## 结论
本轮对 strict_survival 观察边界零放宽（VIEW-7）；`mc_inspect_local` 与既有 perception
走**同一条**可见性判定路径，被遮挡方块与隐藏扫描一样不可见。

## 机制对照
| 面 | perception（既有） | mc_inspect_local（本轮） |
|---|---|---|
| 块可见性 | `ObservableWorldQuery.canObserveBlock`（六面中心 raycast，COLLIDER+ANY 命中即挡） | 同一方法，逐格调用 |
| 实体可见性 | `ObservableWorldQuery.canObserveEntity` | 同一方法 |
| 能力门 | `CapabilityRuntime.decide(bot, HIDDEN_BLOCK_SCAN, "perception_snapshot")` | `decide(bot, HIDDEN_BLOCK_SCAN, "inspect_local")`（同一能力，独立 context，denied 照常节流打日志） |
| 半径 | `min(config.radius(), 8)` | `min(请求 1..16, min(config.radius(), 8))`，输出回显 `radius_effective` 与 `perception_policy_radius` |
| 遮挡泄露 | 无 | 无：扫描先 `getBlockState`，空气跳过，非空气必须过 `canObserveBlock` 才计入直方图/样本 |

- `mc_view` 不做任何块扫描（PERF-1）：semantic 数据完全复用 observe 的 10-tick 缓存，
  守卫测试锁定调用形态。
- `mc_inspect` 只展开 registry 已登记对象的注册时数据（baseline histogram、cell mask、
  typed 状态），不产生任何新的世界读取能力；structure baseline 的 missing 样本复用
  R2.1 已验收的 `homeRepairPlan`（missing-only 语义），不引入第二份判定。
- evidence_ref 不可构造出"远程坐标"：ref 只能指向 registry 中已注册对象，
  inspect_local 的中心固定为当前身体，二者均无法表达任意远程位置（VIEW-10）。

## 证据
- GameTest `mc2a0InspectLocalDoesNotLeakOccludedBlock`：石墙后钻石矿在遮挡时不出现在
  `detail=blocks` 输出，拆墙后出现；
- LIVE-2A0-2：实机同几何复验 + server 日志 `capability_decision ... capability=HIDDEN_BLOCK_SCAN
  allowed=false`（strict_survival denied 决策照常记录，context 含 `inspect_local`）。
