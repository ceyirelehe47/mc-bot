# MC-2A0.1F RESULT｜Cognitive Acquisition + DSH Event Transport Final Closure

```text
repo      = ceyirelehe47/mc-bot
base      = f3120a0637d6028fb2d7671b6ca261045a473bfb (experiment/mc2a01-cognitive-evidence-boundary)
branch    = experiment/mc2a01-final-closure
Commit A  = 13a490cb4ce3341aa09beeecc1e0e7b688ff89e2 (production code + tests)
Commit B  = 本证据提交（见 00-meta/production-sha.txt 与最终回复报告的 branch HEAD）
冻结上游   = zoyluoblue/mc_aiplayer@a029fa6a3760fd0f83834c104051b041d986da60
```

## 1. 修复内容

**P0 认知采集边界（AUTO-OBS-1..4 / COG-AQ-1..4）**：`SemanticWorldRegistry` 拆分
omniscient `observe()`（结构卡含 authoritative current integrity，仅显式操作：
register/capture 回执、测试直调）与新增 `observeBounded()`（自动路径专用：结构卡
仅 durable 身份 id/kind/protected/inside/bounds/snapshot_cells，schema=
`mc_spatial_semantics_v2_bounded`，零 current-integrity 字段）。自动链三个入口
（`MinecraftBodyBackend.refreshCaches`、`CognitiveViewBuilder.build` fallback、
`CognitiveInspector.materialize` fallback）全部切换 bounded——不是"扫描后隐藏"，
omniscient 调用从自动路径源码级消失（JUnit 源码契约断言 backend 不含
`SemanticWorldRegistry.observe(bot)` 调用）。新增 test-only 计数器
`INTEGRITY_RAW_READ_SCANS`。`homeRepairPlan` 等显式操作语义不动（AUTO-OBS-4）。

**P1 空间缓存作用域（COG-AQ-6 / SPATIAL-CACHE-1）**：`StructureKnowledge.CACHE`
key 从 `dimension/id` 升级 `worldId()/dimension/id`——CACHE 无生命周期清理，
world_id 是阻止同名结构跨 save/world 共享 LAST_KNOWN 验证的唯一作用域；registry
未 start 时 fail-loud 不静默退化。

**P0 DSH Body 事件传输（EVT-TR-1..6）**：`src/events.mjs` 移除 followup（next-turn
队列——长 turn 不结束时事件永不到达模型并堆积为 backlog 的真实试玩根因）。新矩阵：
autoWake=false→`inject`；urgent（death/significant damage/player_message/
survival_alert/control_lost/body_changed）→`steer`；running+routine→`inject`
（同 turn 下一 step 边界可见）；idle+wake-worthy→`steer`（唤醒续作）。
durable ordering（journal→ingress→flush→cursor）未触碰。

## 2. PASS Gate 对照

| Gate | 项目 | 结果 | 证据 |
|---|---|---|---|
| A | exact base = f3120a0 | ✓ | 00-meta/base-sha.txt；branch 自 base 直接创建 |
| B | ancestry clean, no master merge | ✓ | 单父链 f3120a0→13a490c（Commit A）→Commit B |
| C | 自动 tick/view 远程 raw-read = 0 | ✓ | GameTest 628 内 mc2a01fAutomated…（counter 直证）+ LIVE-4（bounded schema）+ JUnit 源码契约 |
| D | durable Structure identity 远程可见 | ✓ | GameTest LAST_KNOWN 卡 + LIVE-4 baseline_cells=4 |
| E | 近距合法 proof 可 VERIFIED_LIVE | ✓ | GameTest 回包络 LIVE（missing=1）+ LIVE-4 回包络 LIVE |
| F | cache key 含 world_id | ✓ | JUnit structureKnowledgeCacheKeyIncludesWorldId |
| G | Body transport 源码零 followup | ✓ | Node 源码守卫测试 + 05-audit grep 0 命中 |
| H | running routine 同 turn inject 可见 | ✓ | LIVE-1（jsonl next-step 同 turn claim + 模型原话自述） |
| I | urgent 用 steer | ✓ | LIVE-2 + Node runningUrgentEventSteers |
| J | idle wake-worthy 经 steer 唤醒 | ✓ | LIVE-3（两组：completed/failed 均 next-step 唤醒新 turn） |
| K | autoWake=false 仅 inject 不唤醒 | ✓ | Node autoWakeFalseInjectsWithoutWaking + plugin pause 语义未动 |
| L | ingress/flush 失败不推 cursor | ✓ | Node 既有 3 契约全绿（02-build/node-tests.txt） |
| M | 627 基线保留 + 新增绿 | ✓ | 628=627+1（03-gametest TEST-round1/2.xml） |
| N | 全量 GameTest ×2 绿 | ✓ | 628/0 × 2 |
| O | replay GameTest 数量一致绿 | ✓ | TEST-replay.xml 628/0 |
| P | Node/BridgeCore/Installer 不回退 | ✓ | Node 42/0（37+5）、BridgeCore 77、Installer 11 |
| Q | fresh exact JUnit count | ✓ | **361**（02-build/junit-summary.txt，从 build/test-results/test/*.xml 重算） |
| R | 干净 a029fa6 replay 0-diff raw 证据 | ✓ | 01-diff/replay-zero-diff.txt（DIFF=[]，428 文件） |
| S | secrets 0 命中 | ✓ | 05-audit/secret-scan.txt REAL_SECRET_VALUE_HITS=0 |
| T | 无 Agenda/TaskGraph/Scheduler/TreeHarvest scope | ✓ | 01-diff/component-diffstat-base.txt 14 文件全部在本轮三面范围内 |

任一 C/G/H/J/R 均通过 → **可 Freeze MC-2A0.1**。

## 3. exact 测试计数（全部从 fresh 产物统计）

- **GameTest：628**（627 基线全保留 + 1 新增
  `mc2a01fAutomaticViewNeverTriggersRemoteStructureIntegrityRead`；独立 `mc2a01f`
  批——见 §6 布局教训）。round1（02:387min）/round2（03:481min）/replay（02.157min）
  三次全绿，TEST-*.xml + server log 归档于 03-gametest/。
- **JUnit：361** = 上轮真实 359 + 本轮新增 2 源码契约
  （structureKnowledgeCacheKeyIncludesWorldId、automaticObservationUsesBoundedSemanticSnapshot）。
  上轮归档报告显示 351 系陈旧产物（未从最终代码重跑）；359+2=361 与本次
  build/test-results XML 完全对账，该差异就此闭环。开发树与 replay 树各自独立
  跑出 361/0。
- **Node：42** = 37 + 5（runningRoutineEventInjectsAndNeverFollowups、
  runningUrgentEventSteers、idleWakeWorthyEventSteersInsteadOfFollowup、
  autoWakeFalseInjectsWithoutWaking、bodyEventTransportContainsNoFollowup）；
  旧测试 idle followup/busy urgent steer/pause inject-only 已按新契约重写
  （重命名+断言更新，未借改名掩盖）。
- **BridgeCoreTest：77** checks、**Installer：11** tests——均不回退。

## 4. Installer replay（REP-1）

干净本地 clone a029fa6（autocrlf=false）→ `apply_to_aibot.py --apply`
（CHANGES 22 锚点 + EXTRA_CHANGES 2 文件全部 exact-blob 校验通过）→ 与开发树
**逐文件 sha256 对比 428 个文件 0 diff**（01-diff/replay-zero-diff.txt：DIFF=[]、
ONLY_IN_WORKTREE=[]、ONLY_IN_REPLAY=[]、exit=0）→ replay 树 compileJava+test
361/0 + 全量 GameTest 628/0。**不是 diffstat+文字声称，是逐字节 raw 对比产物。**

## 5. LIVE-2A01F-1..4（全部 PASS，04-live/）

真实 DSH web（5dda764 + 本轮插件）+ 真实模型 deepseek-v4.1-flash + 隔离服
world_play（Bob）+ MC-2A0.1F jar f4b2df81：

1. **running routine inject**：turn 7（5×mc_observe 长回合计）进行中 RCON 放置
   铁矿 → 事件 163/164 resource_opportunity_actionable → jsonl
   insert(target=next-step)→claim→user/message(source=plugin) 全部落在**同一 turn**，
   模型汇报原话"在第 4 次和第 5 次观测之间，桥接层推送了两条 … 事件"；
   cursor=164=journal 末序号，backlog=0。
2. **urgent steer**：turn 6 running 中 RCON damage 6（11.5→5.5，delta≥4）→
   事件 162 → next-step 插入并在同 turn 下一步 claim，turn 6 继续至自然结束。
   原语选择由源码+Node 契约锁定（DSH 在 running 态 inject/steer 同队列，运行时
   等价——已在文档注明）。
3. **idle continuation**：两组实证——goto completed(158) 与长 goto failed(161)
   均在 Agent idle 时以 **target=next-step**（steer 通道，followup 会是 next-turn）
   入队并立即唤醒新 turn（turn 3 / turn 5），cursor 同步推进。
4. **automatic remote read zero**：Bob 远离 live2a01（~33 格 > 16 包络），
   生产 kernel 自然 tick **211 ticks** + 35 次 view 请求：mc_observe 的
   semantic_world.schema=`mc_spatial_semantics_v2_bounded`、两结构卡零 integrity
   字段、view 卡 knowledge=UNKNOWN 非 VERIFIED_LIVE/LIVE、durable baseline_cells=4
   在；tp 回包络后一次 view 即 VERIFIED_LIVE（missing=1，远端期间真实存在的破坏
   只在合法验证后出现）。raw-read=0 证据链：counter 唯一增长点在 omniscient 链 +
   实测 bounded 入口 + 源码级不可达断言 + GameTest counter 直证。

## 6. 过程缺陷与修复（备案）

- 新 GameTest 初版复用 batchId=mc2a01：fabric **同批测试并行**且相邻 cell 仅 ~13 格，
  本测试 ground() 的 ±20 格清空范围覆盖了并行中 Remote 测试的石柱（fixture 互踩，
  reverify 时 expected=2/missing=2/matched=0）。修复=独立 batchId `mc2a01f`
  （批间串行，ground 只清已完成测试的区域）。该教训同时说明 mc2a01 批内旧三测试
  的 fixture 并行互踩隐患为既有事实（上轮靠启动顺序侥幸通过），本轮未扩大修改。
- 隔离服首启 crash（8765 被 replay GameTest 服务器占用）：错峰重启即恢复，
  与交付代码无关。

## 7. 文档同步

GLM_HANDOFF.zh-CN.md / IMPLEMENTATION.zh-CN.md 的事件传输验收口径已按
inject/steer 新契约更新（旧"空闲终态使用 followup"表述已删除）。

## 8. 遗留与下一阶段

- inject/steer 在 DSH running 态行为等价（同 next-step 队列），原语区分依赖源码
  契约与单测——DSH 侧如未来提供可观测标志位可再加强 LIVE 区分度。
- 本轮通过后停止开发；下一阶段 MC-2A0.2 Playtest Reliability
  （TreeHarvestWorkset / complete-tree 语义 / TemporarySupport ownership）由外部
  审计决定开启。
