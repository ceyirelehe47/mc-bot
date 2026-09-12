# 审计 — 树支撑重入（tree support re-entry）

## 补丁 0001 原设计及其对本仓库几何的缺陷

原设计：surface path 回到"第一根支撑顶"，再有界步/跳逐收据遍历。
本仓库 TREE_ACCESS 支撑 = `placeOneSupportCell`（脚下方块、跳上去）产生的**垂直柱**：
第 2 根恰好占据第 1 根的顶格。>=2 支撑时"第一根支撑顶"是实心泥土——surface path
目标无解（实测 `pathfinding_failed: TIMEOUT` → 补丁自带的确定性 GameTest 即刻失败）。
该设计只对阶梯式支撑成立。

## 交付的修正语义（最小修正，逐条记录见 RESULT §3）

重入 = 三件事，全部只用既有原语、零新增特权面：

1. **每 tick 核验全部存活收据**：owner_execution、purpose=TREE_ACCESS、精确位置、
   精确当前 blockId；外来/被改 → `tree_access_reentry_foreign_receipt` /
   `…_support_conflict`（CONFLICT 标记）类型化债务，绝不猜测修复/移除。
2. **冻结位移时的栈身份**（`reentryChain`）：后续重放收据入账但不改变"走回哪根柱"；
   冻结时清空陈旧的 access 走位拒绝集（避免污染重入基地选择——实测修正）。
3. **走回柱旁 + 恢复自有放置**：`walkBesideColumn`（surface path 到柱旁站立格，
   chooseReentryBase 仅取柱邻 ±1、清空柱、拒绝集排除）→ `placeOneSupport` 一列之隔
   续爬。新收据=普通自有 TREE_ACCESS 收据，与被位移栈一并反向清理。

配套修正：
- 调用方仍以"bot 在最新支撑顶"进入普通分支；重入只在离顶时接管。
- **cleanup 选柱改最高优先（同高取最早）**：latest-first 会立刻把爬升辅助柱当清理
  目标拆掉，放/拆振荡至材料耗尽（实测）——单柱场景最高==最新，经典反向下降不变。
- **cleanup 接近改有界步/跳优先，否则同原语爬升辅助**：surface path 无法解析高位
  自有站格（TIMEOUT→类型化 unreachable），有界接近覆盖混合柱拓扑。
- **击退后落地标志修复**：无客户端 fake body 可能停在自己格子上方零点几格，
  onGround=false 永久为假、一切 pillar jump 被拒。放置前允许**至多 4 步**经已评审的
  `FakePlayerMotion.stepToStandable` 相邻步重发布落地标志（初版每 tick 无界步的缺陷
  已实测修正）；真悬空给 40 tick 物理沉降窗，超时类型化债务。

## 不变量对照（TREE-R1-1..6）

- R1-1 同 workset：accessTarget 支撑存活期间钉死，nextRemaining 不换目标
- R1-2 同已提交目标：见上
- R1-3 只遍历精确自有收据：核验+新收据均自有；零邻域推断（无 dirt/cobble 猜测）
- R1-4 外来/被改 → 类型化债务（实测 support_conflict 于 entomb 改写场景）
- R1-5 完成提交树+全量反清+零脚手架：实测 11/11
- R1-6 通用 PILLAR_UP 未触碰：放置原语复用 placeOneSupportCell，无通用爬升规划器
