# MC-2A0.2 Tree Harvest Reliability — 交付结果（RESULT）

- 日期：2026-09-11
- 交付分支：`experiment/mc2a02-tree-harvest-reliability`
- 任务包：`mc-bot-mc2a02-tree-harvest-full-implementation-taskpack-20260911`（SHA256SUMS 全部校验通过）

## 1. 必答清单（任务书 §10）

### 1) exact base / final SHA

- base = `7ec7838e60f812e7f691797fe96de40490bfba16`（= `experiment/mc2a01-final-closure` HEAD，
  production parent = `13a490cb4ce3341aa09beeecc1e0e7b688ff89e2`，ancestry 已验证）
- final（生产 commit）= `2359502c4e40b6979714f2636b476e6e07ca1e38`
  （`b04c2a3` 初版 + amend 收编 GameTest fence 修正；本 RESULT 归档为证据 commit 之前的最终生产 SHA）

### 2) 是否原样应用补丁，改动清单

补丁 `git apply` 原样应用成功（9 文件，+1344/−10）。真实编译/测试暴露 **2 个真实缺陷 +
3 个集成缺口**，全部按"最小修正、不改 TREE-1..6 语义"处理：

| # | 修正 | 性质 |
|---|---|---|
| 1 | `TreeHarvestWorkset.java:548` 比较器 lambda 加显式 `(BlockPos p)` | javac 对 `Stream.min(comparingDouble(...))` 的嵌套类型推断限制（parse-front 静态验证无法发现） |
| 2 | 契约测试断言匹配 installer 生成的两行调用形状（生成源中无连续子串 `TreeHarvestWorkset.acquire`） | 测试与生成代码形状偏差 |
| 3 | installer EXTRA_CHANGES 注册 `MC2A02TreeHarvestGameTests`（fabric.mod.json gametest entrypoint） | 补丁遗漏注册，5 个新 GameTest 静默不执行；沿用 MC-1C-A 同位修复惯例 |
| 4 | GatherQuotaTask transform 恢复无条件 `if (waitForDryGround(bot))`（补丁原文本在 TREE_CLEANUP 旁路水救援暂停） | 违反既有 `SurfaceExpeditionWaterRecoverySourceContractTest` 安全契约；pause 不丢 workset（TREE-5），无需旁路 |
| 5 | `MC2A02TreeHarvestGameTests.mc2a02QuotaOneDoesNotTruncateCommittedTree` 在 assign 前等 5 tick 让外部 body respawn fence（`external_mode_enter` cancelAll）先完成 | R1 fence 对每个 fresh body 实例触发一次；spawn 同 tick assign 会被 fence 杀任务。其余 4 个新测试断言在同步块内完成，不受影响 |

### 3) owner_execution 全链路

`BridgeKernel.java:364 backend.start(e.id,e.operation,e.arguments)`
→ `MinecraftBodyBackend.java:121/151 new GatherQuotaTask(target,count,executionId)`
→ `GatherQuotaTask.java:124/70 externalExecutionId`
→ `:1194-1196 TreeHarvestWorkset.acquire(bot, targetPos, externalExecutionId)`
→ receipt 携带同一 owner（`TreeHarvestWorkset.java:511`）。日志实测：`origin_reason=external_dsh:<executionId>`（LIVE-6 server log 直证）。owner 不靠 bot 名/坐标/"当前唯一 gather"猜测。GameTest `mc2a02TemporaryTreeSupportsAreOwnedAndReverseCleaned` 锁定 `ownerExecution`+`purpose=TREE_ACCESS`。

### 4) frozen candidate tree 语义

`TreeHarvestWorkset.acquire` 首次 proof 冻结 `LinkedHashSet<Long> candidates`（`NaturalTreeClassifier.HarvestClusterProof`）；`reconcile` 只对账 frozen cells，绝不邻域扫描扩张。GameTest `mc2a02WorksetNeverAbsorbsNewOrNeighbourTreeLogs`（新增 log 不吸收+邻树不泄漏）+ LIVE-2 实测（frozen proof=6，高处 y=74/75 未在 proof 中→不被处理，恰是冻结语义的反向验证）。

### 5) quota vs TREE_COMPLETE

`GatherQuotaTask.java:219 countSoFar >= targetCount && treeWorkset == null` 才允许 quota 直接终止（JUnit 契约锁定）；workset active 时 progress 上限 0.99（:148），砍完当前树+支撑清理+pickup 对账全闭合才 DONE；quota 满足后允许下一棵树。LIVE-1/3/6 实测 quota=1 → 整树 accepted=4。

### 6) remaining/completed/blocked

`TreeHarvestWorkset`（:133-153）：`remaining`（frozen−resolved）、`completed`（含外部已解决格）、`blocked`（`BreakPolicy` 拒绝/harvest_timeout/access blocked，typed reason）。TREE_COMPLETE 要求 `logsResolved() && !hasTemporarySupports() && pickupsReconciled() && 无 debt && 无 abandon`（:226-229）。

### 7) pickup obligation

只有 gather 已进入该 exact cell 的 HARVEST 后 `noteHarvested`（GatherQuotaTask:822 调用）才建立 obligation；proof 二选一沿用 R2：accepted inventory delta / `Stats.PICKED_UP` delta（pre-existing 库存不算，PICK-1 由 family 绝对基线语义保证：LIVE 实测 task 基线=当前背包原木总数）。bounded deadline 后显式 `LOST`（resolvePickupLoss:897-901），不静默遗忘。

### 8) TREE_ACCESS placement provenance

唯一放置点 `TreeHarvestWorkset.placeOneSupport`（:470-518，由 tickAccess 进入）：placement 前过 BreakPolicy/净空/无流体/材料上限 12 道闸；`BuildAction.placeBlockAt` **成功并核实方块存在后**才记录 `TemporarySupport(pos, blockId, ownerExecution, "TREE_ACCESS", originalState, placedAt)`（:511）。日志事件 `tree_support_placed` 带 tree/execution/pos/block（LIVE-2/5 实测）。

### 9) reverse cleanup

`tickCleanup`（:391-468）：逐 receipt 复核（exact blockId 一致/BreakPolicy/bot 在支撑上方或 no-dig route 回上/移除后安全下降）→ 真实 `MiningController` 挖除 → `stepToStandable` 下降 → `REMOVED` 记账；任一不过 → typed `DEBT`。不碰 generic `PathExecutor.PILLAR_UP`（0 引用，JUnit 契约锁定）。

### 10) foreign/persistent isolation

cleanup 只删"exact receipt-own 且当前 blockId 仍与 receipt 一致"的方块；CONFLICT（外部替换）→ debt 且不删。无任何 "nearby dirt/cobble" 材质推断路径（grep 0 命中，见 05-audit）。LIVE-3（紧贴 foreign 三块幸存）+ LIVE-5（gold_block 替换幸存）+ GameTest 支撑/债务两用例。

### 11) Safety pause/resume

pause/resume 用原 task 实例，workset/support ledger 原地保留；`TreeHarvestWorkset.onResume`（:242-247）只清 transient。LIVE-4 实测：threat→`task_paused(gather)`→`origin=SAFETY evade`×6 轮→`task_resumed`→**同一 treeId/execution 继续砍完**（resolved ×3 → complete），未另开新树。GameTest `mc2a02SafetyPauseResumeKeepsSameTreeWorkset` 锁定身份不变。

### 12) cancel/replacement

新 execution 不继承旧 receipt（ownership 绑 exact executionId，实例级）；取消时若仍有 owned support，terminal 带 `tree_cleanup_debt_supports` + bounded summary（onAbort 织入段），不猜测拆世界。

### 13) restart

遵循已验收 BridgeKernel 规则：in-flight → `outcome_unknown` + reconcile，不自动 replay。本轮不引入 GraphStore persistence；restart 后残留 support 不会被猜测为新 execution 财产（RESTART-1）。未做专门 LIVE 重启场景（下轮建议见 §20）。

### 14) cleanup debt

LIVE-5 端到端实测：own support 放置后 0.15s 内被外部替换为 gold_block → execution **failed**，terminal reason `tree_cleanup_debt:tree_cleanup_support_conflict:530, 68, 111`（typed+精确坐标），gold_block 幸存，无 own scaffold 残留，绝不 false success。GameTest 债务用例锁定 DEBT/非 TREE_COMPLETE/不删语义。

### 15) old 628 完整保留

fabric.mod.json entrypoint 含全部旧注册；GameTest 三轮实测 633 = 628 旧全绿 + 5 新全绿（round1/round2/replay 三轮 XML 归档于 03-gametest/）。

### 16) 最终数量

| 套件 | 数量 | 结果 |
|---|---|---|
| GameTest（×3 轮：round1/round2/replay） | 633 | 全部 required、0 fail |
| JUnit（开发树 + replay 树） | 363 | 0 fail 0 error |
| Node | 42 | 0 fail |
| BridgeCore | 78 checks | 0 fail |
| Installer | 11 tests | OK |

### 17) clean replay 0 diff

干净本地 clone `a029fa6`（`core.autocrlf false`）→ `apply_to_aibot.py --apply`（24 exact blobs + 30 new files）→ 与开发树**逐文件 SHA-256 对比 431 文件 0 diff**（`01-diff/replay-zero-diff.txt`：DIFF=[]、ONLY_IN_WORKTREE=[]、ONLY_IN_REPLAY=[]、exit=0）→ replay 树 JUnit 363/0 + GameTest 633/0。

### 18) 六个 LIVE

| ID | 判定 | 关键证据（04-live/） |
|---|---|---|
| LIVE-2A02-1 quota=1/complete tree | **PASS** | `accepted=4`（整树 over-gather）、workset acquired logs=4→resolved×4→complete、无残留 log、背包 oak_log×4 |
| LIVE-2A02-2 tall/branched | **PARTIAL** | proof=8（高位 acquire）、resolved×6、`tree_support_placed/removed`（owner=exact execution、trunk 原位 dirt）✓、零 scaffold 残留 ✓；但高处 y=74/75 因 **D-1 振荡缺陷**（见 §20）材料耗尽 blocked 未处理 |
| LIVE-2A02-3 foreign/persistent | **PASS** | 紧贴树 1-2 格的 cobble/dirt/dirt 三块在完整事务后全部幸存；场景树 4 log 全处理 |
| LIVE-2A02-4 Safety preemption | **PASS** | threat:HOSTILE→gather paused→SAFETY evade×6 轮→resumed→同 treeId/execution 砍完→complete have=3/1 |
| LIVE-2A02-5 cleanup debt | **PASS** | own support 被 gold_block 替换→failed+`tree_cleanup_debt:tree_cleanup_support_conflict:530,68,111`、gold_block 幸存、零残留 |
| LIVE-2A02-6 real DSH | **PASS** | 模型只发普通 `mc_gather minecraft:oak_log`（request_id `dsh-…` 前缀）→Body 完成 `0daed956` accepted=4→事件唤醒模型汇报终态；transport 取证：`agent/inbox/spliced target="next-step"`（steer 通道），全会话 **followup 0 命中** |

LIVE 环境：隔离服 `mc-server-mc1ca`（外部模式 Bob，jar sha256 `3aa86160…` 1768512 字节）+ frozen DSH（5dda764 + scratch-aibot-body 插件，deepseek/deepseek-v4.1-flash @ commandcode relay）。

### 19) DSH tools 数量

**25**，无新增（补丁不触 DSH 插件面；grep/diff 证明 + LIVE-6 实测模型仅用既有工具）。

### 20) 新发现缺陷与下一轮建议

**D-1（真实缺陷，本轮实测确认）TREE_ACCESS 支撑同格振荡**：
高位树（proof 含不可直达 log）场景中，access 在已砍空 trunk 原位 `placed(530,68)→removed(530,68)` 每秒循环约 60 秒不向上推进，直至支撑材料（32 dirt）耗尽 → `tree_access_no_support_material` fail-closed。所有权/清理/零残留语义全程正确，但 access planner 的"place→verify→descend/cleanup"回路在 bot 未真正登上支撑顶时无法进入下一层。**建议下轮**：placeOneSupport 成功后由 access 状态机维持"支撑顶=工作面"直到 canReach(target) 或超时换 base，reverse cleanup 仅为 drop chase 让路时才发生。

**D-2（上游已知语义再确认）family 绝对基线**：`acceptedInventoryCount` 把任意原木计入任意原木配额且含已有库存（birch_log 满足 oak 配额）；配额型验收/操作前必须清空全部原木 family + 地面掉落物。建议后续轮在 DSH 工具层加"配额前清点"提示。

**流程教训**：
- GameTest 必须以 `AIBOT_EXTERNAL_BOT=Mc1caBot` + 合规 `AIBOT_BRIDGE_TOKEN`（32-256 URL-safe 字符）环境运行，否则 registry 不启动+reserved 语义失效 → 全局性失败（非补丁缺陷）；
- LIVE 场景需大范围清树叶/树苗/掉落物：自然掉苗生长会持续制造干扰树（实测多轮 gather 被"截胡"）；
- `agent/inbox/spliced` 多 frame zstd 解码需 `read_across_frames=True`（2A01F 教训仍适用）。

**下一轮建议**（优先级序）：① D-1 振荡修复（access 状态机分层推进）；② restart LIVE 场景补测（RESTART-1 已有源码+GameTest 层保障，缺实机证据）；③ 自然树分类器 proof 高度受 acquire 时观察眼位限制（LIVE-2 实测地面 acquire 只证到 y=73）——评估 acquire 时机或分批 acquire 策略。

## 2. 审计与安全

- 审计报告：`05-audit/TREE-ACCESS-OWNERSHIP-CLEANUP.audit.md`（placement 唯一调用点、owner 链、removal 全复核表、grep 证明无材质推断、PathExecutor 0 改动）
- secret scan：`05-audit/secret-scan.txt` — 扫描 65 文件，`REAL_SECRET_VALUE_HITS=0`（候选均为 SHA/标识符误报，`sk-`/`user_` 真实密钥模式 0 命中）

## 3. 证据索引

```
00-meta/          base/final SHA、git status
01-diff/          mc2a02-production.diff（95KB）、component-diffstat-base.txt、replay-zero-diff.txt
02-build/         environment、junit-summary(363/0)、bridge-core-tests(78)、node-tests(42)、installer-tests(11)、jar-info
03-gametest/      TEST-round1/round2/replay.xml（633×3）、server log ×3
04-live/          live1..live6 server log/事件提取、live6 dsh session jsonl.zstd + transport forensics
05-audit/         审计报告、secret-scan
scripts/          replay_diff.py、secret_scan.py
```

## 4. 验收矩阵对照（ACCEPTANCE_MATRIX）

BASE-1 ✓ / PATCH-1 ✓ / OWN-1 ✓ / TREE-1..6 ✓ / FREEZE-1 ✓ / FOREIGN-1 ✓ / PICK-1 ✓ /
RESTART-1 ✓（源码+静态证明；实机 LIVE 未跑，见 §20） / REG-1 ✓ / REG-2 ✓ / REPLAY-1 ✓ /
LIVE-1..6 ✓（LIVE-2 判 PARTIAL，因 D-1；支撑所有权/清理/隔离子项全 ✓）
