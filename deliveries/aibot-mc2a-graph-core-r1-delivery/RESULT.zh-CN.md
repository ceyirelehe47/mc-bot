# MC-2A Graph Core R1 Closure — RESULT

基准：`experiment/mc2a-graph-core-foundation@8df1ca38f3d3488afa59cd4b938b8936d26f73b6`
分支：`experiment/mc2a-graph-core-r1-closure`（生产提交 + 本证据提交）
任务包：`mc-bot-mc2a-graph-core-r1-closure-taskpack-20260912.zip`（sha256 校验全过）

## 结论

六个 LIVE 门（LIVE-R1-1..6）全部 PASS；旧基线零回归；四冻结阻断（A/B/C/D）全部闭。
`8df1ca3` + 本闭包轮可作为 Graph Core 冻结基线。

## 任务书 §10 的 18 问

1. **精确 base/final SHA**：base=`8df1ca38f3d3488afa59cd4b938b8936d26f73b6`；final 见
   `00-meta/final-sha.txt`（生产提交，本证据目录随其后的证据提交归档）。
2. **补丁是否原样应用**：0001 经 `git apply --recount` 干净套用；0002 原样**语义**
   套用——两个补丁在包内预校验即 `git apply --stat` FAIL（PATCH_VALIDATION.md 自述
   "corrupt patch"）。根因：7 个 hunk 以 +/- 行结尾且无尾随上下文行，git apply 对其
   施加"只能匹配文件末尾"语义故必败。修复方式为按真实文件补 1 行尾随上下文
   （`0002-fixed.patch` 相对原包补丁仅多 7 行上下文、零 +/- 行变化，已程序化比对），
   再以 `--recount` 套用——补丁语义 100% 原样。
3. **最小编译/API 修正**（全部在 0001 设计被实测证伪处，逐条有实测依据）：
   a. 重入"走回第一根支撑顶"对垂直柱几何无解（第 2 根占据第 1 根顶格，surface path
   TIMEOUT）→ 重入改为"每 tick 核验全部存活收据 + 冻结栈身份 + 走回柱旁 + 复用
   placeOneSupport 邻柱续爬"；新收据为普通自有 TREE_ACCESS 收据并参与全量反清。
   b. GameTest 轮询回调把 `snapshot==null` 早退置于完成断言之前 → 完成检查前置
   （任务第 352 tick 已 COMPLETED 而测试干等 3200 tick 超时的实测缺陷）。
   c. cleanup 选柱 latest-first 会把刚放置的爬升辅助柱当目标拆掉，放/拆振荡到
   材料耗尽 → 改最高优先（同高取最早；单柱场景等价旧行为）。
   d. cleanup 接近高位自有站格时 surface path 必 TIMEOUT → 有界步/跳优先，否则同
   原语（placeOneSupportCell）爬升辅助接近。
   e. 冻结重入身份时清空陈旧 access 走位拒绝集（否则污染重入基地选择，
   `reentry_no_adjacent_base` 实测）。
   f. 击退后无客户端 body 可能悬停自身格上方零点几格（onGround 永假、pillar jump
   全拒）→ 放置前至多 4 步经已评审的 `FakePlayerMotion.stepToStandable` 相邻步重发布
   落地标志；真悬空给 40 tick 物理沉降窗（初版每 tick 无界步的缺陷已实测修正）。
   另 `placeOneSupport` 抽出 `placeOneSupportCell`（cleanup 复用，无 canReach 短路）。
   全部修正未新增特权面（无新直接 teleport——`PrivilegedBoundarySourceTest` 契约保持绿）。
4. **真实敌对 Safety 重入结果**：PASS。僵尸近战击退离栈（path_drop_down 实证）→
   同任务恢复 → `tree_support_reentered supports=2`（逐收据核验）→ 邻柱续爬 → 8/8 原木
   → 反清。最终 jar 亦有"位移+完成+零残留"复验轮。
5. **同 workset/树/owner 证据**：位移前后 treeId=`…:145685291176007` 与
   execution=`82707092…` 全程一致（LIVE-R1-1-events.log）。
6. **最大并发支撑数**：教科书轮 4（原 2 + 重入辅助 2）；全部会话日志中观测到的最大并发为
   10（上限 MAX_TEMP_SUPPORTS=12，无越限）。
7. **零脚手架证据**：RCON 逐格扫描树列±1 与证台列 67..78：placed==removed
   （教科书轮 5/5），残留仅树基泥土（地面本身）。
8. **consumed 收据精确结构化字段**：journal seq 456：
   kind=resource_opportunity_consumed, execution_id, opportunity_id=ore_fed47d71…,
   world_id=80980dea-…, dimension=minecraft:overworld,
   payload={x:552,y:68,z:129,block:minecraft:iron_ore,resolution:inventory_gain_proven,
   opportunity_id,world_id,dimension}。
9. **注册表缺席为何不再能 DONE**：见 06-audit/terminal-outcome-authority.md——缺席=UNKNOWN，
   DONE 唯一入口均先查持久收据；UNKNOWN 落 SUSPENDED 不动作。
10. **stale/lost→STALE 证据**：LIVE-R1-3（journal seq 468 stale 收据 →
    `reconciled_terminal_unsatisfied:durable_stale_or_loss_receipt`；绝不 DONE）。
11. **崩溃窗口启动对账证据**：LIVE-R1-4（注入旧快照重启 → 复活机会在端点就绪前被移除，
    DONE 仍由收据背书，无物理重放）。
12. **legacy DONE 迁移证据**：LIVE-R1-5（v0 DONE fixture → 首就绪 tick 审计
    SUSPENDED+done_audit_success_not_durably_proven、无重放；补收据重启 → reconcile 回
    DONE）。
13. **精确读取截断证据**：LIVE-R1-6（截断末字节 → 启动 fail-closed
    `task_graph_store_invalid`，不静默加载；JUnit aX→a 歧义回归双覆盖）。
14. **实际计数**：JUnit 378/0 ×（dev+replay）；GameTest 635/0 ×3（round1/round2/replay，
    634+1 新重入回归）；Node 43/0；BridgeCore 78；Installer 11。
15. **干净安装器重放**：436 文件（435+1 新测试文件）逐字节 sha256 零差异，exit=0
    （01-diff/replay-zero-diff.txt；重放树=干净 a029fa6 克隆 + apply_to_aibot）。
16. **DSH 工具仍为 29**：dsh-plugin 零改动；Node 43（含工具矩阵契约）全绿。
17. **图物理路径零绕过**：`graphRunNext → BridgeKernel.submit` 唯一（dispatch_request_id=
    graphd-… 的普通 execution 425d9d00 即证）；源码契约测试 TaskGraphBridgeSourceContractTest 绿。
18. **未新增 Agenda/后台调度器**：未实现 seen_from 刷新；无新 producer；29 工具不变。

## 冻结阻断闭包对照

| 阻断 | 闭包证据 |
|---|---|
| A 真实 Safety 位移失败 | LIVE-R1-1 + GameTest 重入回归（635×3 内含） |
| B 缺席歧义 | LIVE-R1-2/3 + OpportunityResolutionReceiptTest 3 例 |
| C 跨存储崩溃顺序 | LIVE-R1-4 + 启动对账源序 |
| D 二进制精确读取 | LIVE-R1-6 + TaskGraphStoreTest 截断回归 |

## 诚实记录（不影响门判定）

- 击退落点留在自有支撑顶时走"续接"子路径（无 reentered 事件）——补丁设计内合法子情形。
- 测试装置层面的僵尸贴脸可触发 emergency_entomb；自埋泥土改写支撑格实测得到
  `tree_access_reentry_support_conflict` 类型化债务（TREE-R1-4 保守 fail-closed，正确）。
- `mc2a02gatherkeepstemporarysupports…` 曾出现一次地形性偶发（96 格内零树、未放支撑），
  重跑及后续 4 轮全绿，与本轮改动路径无关（该测试未触及重入/清理改动）。
- 教科书日志（server-textbook-r1-1.log）窗口内还含两个未采纳的失败轮：
  11:32:35 `tree_access_reentry_no_adjacent_base`（修正 e 之前的悬崖地形轮）与
  11:35:56 `tree_access_no_support_material`（双僵尸 entomb 轮耗尽泥土）——均为修正
  迭代过程或测试装置层面问题，最终源与最终 jar 下不再复现。
- LIVE-R1-3/4/5 md 中的图状态引文来自驱动会话的控制台输出（一手为会话现场），
  由 graph-api-responses.json（含 r1c-iron-2 DONE / r1c-iron-3d STALE 的当前状态）、
  服务器日志与 JUnit 确定性装置交叉印证；R1-5 的 fixture bin 与前后备份随证据归档。
