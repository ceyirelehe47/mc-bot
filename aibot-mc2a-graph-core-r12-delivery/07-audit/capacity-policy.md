# 审计 — 容量策略(capacity-policy)

## R1.2 后的语义

- `evictOpportunityIfNeeded` 整体删除(源码零残留, 契约测试
  `capacityMustNotEvictALiveIncarnation` 断言 `OPPORTUNITIES.remove(oldest)` 不存在)。
- 新发现拒绝分支: `if(prior==null && OPPORTUNITIES.size()>=MAX_OPPORTUNITIES) return;`
  (MAX_OPPORTUNITIES=256)。即: 活动化身是生命周期状态而非可抛弃缓存; 容量满时**忽略**
  新的未跟踪发现, 直到 consumed/stale 终态释放容量。
- 已活动化身不受容量分支影响(prior!=null 路径直接复用 id), 终态移除
  (consumed/stale 收据成功后的 `OPPORTUNITIES.remove`)是唯一释放路径——R1/R1.1/R1.2
  语义一致。
- reconcile 对恢复后的总量护栏: `activeBirths.size()>MAX_OPPORTUNITIES` →
  `opportunity_lifecycle_capacity_exceeded` fail-closed。

## 对比 R1.1(变化点)

R1.1 及之前: 容量满时按 lastSeenGameTime 淘汰最旧的普通(非 MINED_PENDING_PICKUP)条目。
随机化身身份下, 被淘汰的仍存活物理矿在再观察时会铸新 id——身份分裂。R1.2 关闭该路径,
这是独立评审 BIRTH-5 的直接落地。

## 257-ore LIVE 压测

按 runbook 为可选(策略为直接有界分支, 256 次 fsync birth 的边际置信低), 本轮未执行,
以源码契约 + 全量回归(JUnit 385×2 / GameTest 636×3 / 零 diff)代替。
