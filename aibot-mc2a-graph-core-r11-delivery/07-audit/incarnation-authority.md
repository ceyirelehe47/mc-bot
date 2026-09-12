# 化身权威审计(incarnation-authority)

## id 铸造与复用(R1.1 0001 号补丁)

- `SemanticWorldRegistry.observeVisibleBlock`:先 `findActiveOpportunityAt(dim, pos, blockId)`
  在活动注册表内按 维度+精确坐标+方块 精确匹配;命中 → 复用 `prior.id`(OPP-INC-2:
  同化身重复观察/重启恢复不重铸);未命中 → `newOpportunityIncarnationId`。
- 新 id = `ore_` + UUIDv3(worldId\n dim\n x,y,z\n blockId) 前 12 hex + `_` + UUIDv4 前 16 hex。
  定位前缀确定性(诊断友好),随机段是化身边界(OPP-INC-1)。全小写,33 字符,
  通过快照 `id()` 归一化 `[a-z0-9_-]{1,48}` 与 SpatialRef boundedId(≤160)。
- 同格双活动化身(快照损坏)抛 `duplicate_active_opportunity_incarnation` fail-closed,不猜。

## 终态收据匹配面不变,匹配值化身安全

`BridgeKernel.durableOpportunityResolution` 仍按 `world_id + dimension + opportunity_id`
全等匹配(BridgeKernel.java:327-329,R1 语义);由于 opportunity_id 现在化身唯一,
历史终态收据在结构上不可能命中后来者 —— 修复点在 id 铸造,不在匹配器。

## 旧数据兼容(OPP-INC-3)

- 持久化 v1/v2/v3 旧 id(36 字符确定性)按"遗留化身"原样恢复,启动不重写;
  它们若仍在活动注册表内,继续按位置精确匹配复用,不换 id。
- 实机证明:R11 LIVE 期间 R1 时代的收据(seq 423/468,旧格式 id `ore_f8c8e0f5…`/`ore_d9b0920e…`)
  与新格式化身(`ore_3467bfcc851f_…`)在同一 journal 内共存,互不干扰。

## 启动对账

`reconcileOpportunityTerminalReceipts` 在图存储/HTTP 端点之前执行(R1 顺序保持);
收据只删与自身 object id 全等的活动条目。LIVE-R11-1:seq 783(A 的 stale 收据)存在,
重启后同格新化身 858f… 存活、其 SUSPENDED 图未被终态化。

## 自动化锚点

- JUnit `terminalReceiptNeverCrossesOpportunityIncarnationsAtSameCell`(同格两个化身 id,
  旧收据只对自身 TERMINAL_UNSATISFIED,对新化身 empty)。
- GameTest `mc2a03SameCellSameOreGetsNewIncarnationAndOldReceiptCannotDeleteIt`
  (重复观察同 id → markStale 终结 → 同格同方块重铸新 id → 旧收据手工 journal 对账 removed==0
  → 新化身存活;装置末尾清场:markStale+清方块,避免污染共享注册表——本轮新增的 fixture 修正)。
