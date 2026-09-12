# 审计 — 终局结果权威（terminal outcome authority）

## 修复前

`MinecraftBodyBackend.verifyGraphPostcondition` 对 OPPORTUNITY_RESOLVED 的判定是
`机会不在活跃注册表 == SATISFIED`。但 consumed（本 bot 消费）与 stale/lost（外部消费/
丢失）都会把机会移出活跃注册表——缺席是歧义的：SUSPENDED 图可能凭"从未拿到资源"的
缺席被置 DONE。

## 修复后（0002 补丁）

- 成功权威 = BridgeJournal 中的结构化 `resource_opportunity_consumed` 收据
  （append 前 `channel.force(true)` fsync，失败则任务 `known_resource_success_receipt_not_durable`
  类型化失败、机会不移除、图不可 DONE）。
- `markOpportunityConsumed` 返回 boolean：先写收据、后移除语义条目。
- 语义三分支：
  - consumed 收据 → SATISFIED → DONE（reason=durable_inventory_gain_receipt）
  - stale/lost 收据 → TERMINAL_UNSATISFIED → STALE
  - 仍活跃 → UNSATISFIED
  - **缺席但无收据 → UNKNOWN（绝不成功）**
- `BridgeKernel.durableOpportunityResolution` 从 journal 末帧回扫，按
  worldId+dimensionId+objectId 精确作用域匹配（JUnit `receiptIsScopedByWorldDimensionAndObject`）。

## 为何注册表缺席不再能产生 DONE

DONE 的唯一入口（executionTerminal completed 分支与 reconcileSuspended）都先经
`verifyGraphPostcondition` → OPPORTUNITY_RESOLVED 先查持久收据；backend 对缺席仅返回
UNKNOWN；UNKNOWN 在 completed 分支落 SUSPENDED（postcondition_unknown）、在 reconcile 中
不动作。实测：LIVE-R1-2（收据→DONE）、LIVE-R1-3（stale→STALE）、LIVE-R1-5（旧 DONE
无收据被降级 SUSPENDED）。

## 运行时序（每 tick）

`graphs.reconcileSuspended(this::verifyGraphPostcondition)`——只证明，绝不重派发。
