# MC-2A0｜Cognitive View Foundation 交付结果（RESULT）

任务：`mc.cognitive_view.v0` read model + `mc_view` / `mc_inspect` / `mc_inspect_local`
三个只读 DSH 工具 + canonical scene_hash + EvidenceRef + 测试与实机验收。
以下按任务书 §23 的 23 项必答逐项陈述；所有结论以本目录 raw 证据为准，不采信自报。

## 1. base / final SHA + branch
- base：`d3699fb3b0b6707984aef8ca5d6479dc5f778b1a`（experiment/mc1ca-structure-semantics，R2.1 冻结点）
- 分支：`experiment/mc2a0-cognitive-view`（自 base 直接创建；无 merge/reset/force push；master 未动）
- final HEAD：见 `00-meta/git-status-final.txt`

## 2. 修改文件
- **新增 7**：`external/cognition/{CanonicalJson, CognitiveSnapshot, EvidenceRef, CognitiveViewBuilder, CognitiveInspector}.java`、
  `gametest/MC2A0CognitiveViewGameTests.java`（9 required）、`src/test/.../cognition/CognitiveCanonicalizationTest.java`（5 JUnit）
- **修改（overlay 文件，R2 起非上游 tracked）5**：`BodyBackend`（+3 default 只读方法）、
  `BridgeHttpServer`（+3 查询路由 + 无锁等待 helper）、`BridgeKernel`（+view/inspect/submitLocalQuery、
  tick 缓存与 server 线程查询队列）、`MinecraftBodyBackend`（observeJson 的缓存刷新提取为 refreshCaches 共用 + override 三方法）、
  `SemanticWorldRegistry`（+2 只读快照访问器 structureEvidences/farmEvidences）
- **锚点修改 2**：`fabric.mod.json`（注册 MC2A0 GameTest 类）、`PrivilegedBoundarySourceTest`
  （+cognitiveViewStaysAReadModel 源码守卫）
- **上游 tracked 文件（CHANGES）：0 处新增变更**（R2.1 的 22 项原样，test_installers 断言 22）
- **DSH 侧**：`plugin.mjs`（+3 只读工具 + limits 文案）、`client.mjs`（+3 查询方法）、
  `events.mjs`（IMPORTANT 白名单补 `resource_opportunity_stale`——修复 R2.1 墓碑事件被 DSH pump
  静默丢弃的交付缺陷）、测试 3 件更新、`FakeBridgeServer`（dsh 仓 + mc-bot bridge-tests 双份）认知 override、
  `BridgeCoreTest` +认知内核断言块（70 checks）、`test.sh` javac 清单 +cognition 两类。
- 变更面审计见 `05-audit/mutation-diff-review.md`。

## 3. v0 schema
`{"schema":"mc.cognitive_view.v0","meta":{...},"scene":{world,self,environment,
semantic_objects{structures,farms,resource_opportunities},execution,
recent_significant_events,uncertainty}}`——与 `COGNITIVE_VIEW_V0_CONTRACT.md` 对齐；
实际样例见 `04-live/dsh-session-2a0.jsonl` 中 mc_view 原始返回（encoded ≈10.3–10.9 KB）。

## 4. canonicalization / scene_hash 精确规则
- `scene_hash = "sha256:" + SHA-256(CanonicalJson.write(scene) 的 UTF-8 字节)`；
- CanonicalJson：object key 一律字典序（构建用 Map 的插入序无关）；List 保序（集合语义卡片由
  builder 先按确定键排序）；数字仅有限值，整型十进制、浮点 `Double.toString`；无空白；UTF-8；
- 排序键：structures/farms 按 object_id；opportunities 按状态优先级
  （MINED_PENDING_PICKUP > ACTIONABLE > BLOCKED > UNREACHABLE > 其他）→ distance → id；
  inventory 按物品 id（TreeMap 双保险）；uncertainty 按 (scope_ref, field, reason)；
  inspect_local 样本按 (distance, type, x, y, z)、实体按 (distance, type)。

## 5. hash 之外的字段（仅 meta，不进 scene/hash）
`generated_server_tick`、`generated_game_time`（构建时刻 world.getTime()）、`encoded_bytes`。
scene 内一切时间语义先离散：`day_phase`(MORNING/DAY/DUSK/NIGHT/DAWN)、
`freshness`(LIVE/RECENT/AGING/STALE/UNKNOWN)、`progress_bucket`(0/25/50/75/100)；
不进 scene 的还有：server tick、请求时间戳、任务 elapsed tick、semantic_age_ticks、journal 游标。

## 6. EvidenceRef 规则
`mc://<world_id>/<urlencoded-dimension>/<kind>/<urlencoded-object_id>`；kind ∈ {structure,farm,opportunity}；
编码 `URLEncoder(UTF-8)` 且 `+`→`%20`；parse 严格（`mc://` 前缀 + 恰好 4 段 + kind 白名单 + 非空段），
malformed→400；解析成功后在当前 view 的 inspect 索引中查找，foreign world/dimension/kind mismatch/
unknown object 一律 404 fail-closed，绝不回退（GameTest #5 + BridgeCoreTest + node http-integration 三层覆盖）。

## 7. UNKNOWN / freshness 规则
- `knowledge`：VERIFIED_LIVE（structure integrity——物理直读已注册 baseline；farm ≤32 格 live 统计）/
  LAST_KNOWN（opportunity 持久化记录、远 farm）/ UNKNOWN；
- opportunity `freshness`：last_seen 年龄 ≤2400t RECENT / ≤24000t AGING / 更久 STALE / 缺时间 UNKNOWN；
- 不可验证 ≠ false：farm 远时 summary 只含 crop/registered_cells 并入 uncertainty；opportunity 的
  STALE/UNKNOWN/UNREACHABLE 入 uncertainty（field=current_block_state）；
  MINED_PENDING_PICKUP 一律 `recovery_obligation:true` +
  `resource_acquired:{knowledge:UNKNOWN,reason:inventory_delta_not_yet_verified}`（R2.1 typed 状态不扁平化）；
  禁止 0..1 主观 confidence（无任何此类字段）。

## 8. limits / truncation
caps：structures 16 / farms 16 / opportunities 24 / events 12 / baseline missing 样本 64 /
farm cell 样本 64 / local 样本 64 + 实体 64；硬上限 view 32KiB、inspect/inspect_local 64KiB，
超限 500 fail-closed（拒绝而非静默截断语义）。集合形态
`{items,total,truncated,omitted_count}`——GameTest #7 以 30 注册→24+truncated+omitted=total-24 实证。
insufficient：`recent_significant_events.availability=UNAVAILABLE_THIS_SLICE`（journal 缺失时）。

## 9. busy RUNNING query 语义
- 与 `mc_observe` 同级的 body-ready/新鲜度要求；**不清 `needs_reconcile`**（BridgeCoreTest 锁定：
  view 成功后 submit 仍 409，仅 observe 解锁）、不要求/消耗 lease、不产生 execution receipt、
  无 body-busy 拒绝、不 pause/cancel/replace 执行（LIVE-2A0-4：gather RUNNING 中三查询成功、
  同 execution、无第二个 mutation）。

## 10. hidden observation 是否变化
**否**。`mc_inspect_local` 与 perception 走同一 `ObservableWorldQuery.canObserveBlock/canObserveEntity`
严格六面射线 + 同一 HIDDEN_BLOCK_SCAN 能力门（context=inspect_local，denied 决策照常入日志）；
LIVE-2A0-2 遮挡钻石矿不泄漏、暴露后可见、日志含 `allowed=false`。`mc_view` 不做任何块扫描
（复用 observe 的 10-tick semantic 缓存，源码守卫断言锁定）。

## 11. 两轮 GameTest exact count
`required = 623`（614 旧全保留 + 9 新 mc2a0）× run1/run2 均 **623 tests / 0 failures**
（`03-gametest/TEST-*.run{1,2}.xml` + gradle log；旧 614 无一删除/跳过）。

## 12. Node exact count
**37/37**（R2.1 基线 34 全保留 + plugin 1 + client 1 + http-integration 1），
dsh 仓与 mc-bot 交付副本（dsh-plugin）双跑均 37/37（`02-build/node-tests.log` 为交付副本运行结果）。

## 13. clean upstream replay exact count
干净 a029fa6 重放（installer --apply）：48 个变更文件与最终工作区逐文件 **0 diff**
（LF 归一化，`01-diff/replay-verify.log`）；重放产物 `compileJava test` 通过（JUnit 357/0）、
完整 GameTest **623 / 0 fail（含 9 个 mc2a0）**（`01-diff/replay-gametest.log`）——
replay required count == final registered count（623）。

## 14. LIVE-2A0-1..5
全部 **PASS**（`04-live/` 各自记录 + 原始 log/jsonl/png）：
1. stable hash：冻结场景 3×view tick 变 hash 稳定；RCON give 后 hash 变；再 3× 稳定新值；
2. hidden no leak：遮挡矿不可见 / 暴露可见 / 能力日志 denied；
3. structure inspect：527 cells bounded drill-down、前后 hash 与物理格零变化、无 execution；
4. busy query：gather RUNNING 中三查询成功且同 execution 不受干扰（含 lease 过期 pause→resume 的护栏复验）；
5. DSH load：真实 pnpm dsh web --patch、25 个 mc_ 工具、v4.1-flash 单回合真实四步调用
   （mc_connect→mc_view→mc_inspect→mc_inspect_local，原始 JSON 存档）。

## 15. 性能数据（隔离服实测，HTTP RTT 含缓存读/组装）
- `mc_view`：p50 15.0ms / p90 73.5ms；encoded ≈10.3–10.9 KB（world_r2 真实 registry：2 结构+1 农场+多机会）；
- `mc_inspect`（索引读）：p50 15.4ms / p90 63.4ms；
- `mc_inspect_local(radius=8, blocks)`（server 线程 17³ cube 真实逐格可见性扫描）：
  p50 47.8ms / max 50ms（230 可见块、直方图 6 种）——仅显式请求触发，不进 view 每 tick 路径（PERF-1）；
- kernel 认知快照每 ≤5 server tick 重建（复用 10-tick semantic/perception 缓存），构建失败只令查询 503 不影响 observe/execution 主链。

## 16. 已知缺陷 / deferred
- GameTest 时钟稳定性测试的环境限制：并行 batch 共享全局 registry 与游走实体，故 #1 以
  冻结 semantic 快照 + 剔除 nearby 实体计数的等价形式断言（注释说明），完整 freeze 由 LIVE-2A0-1 覆盖；
- `mc_inspect` 只能展开当前 view 索引中的对象（v0 无跨维度 last-known drill-down，符合任务书 §11 推荐）；
- weather 语义来自 `ServerWorld.isRaining/isThundering`（全局公开状态，非隐藏观察）；
- R2.1 遗留保留：机会 `seen_from` 不随 re-observe 更新；`resource_opportunity_stale` 的 DSH 白名单缺失本轮已修复；
- MC-2A1+（Agenda/TaskGraph/Scheduler/主动感知转头等）全部记为 `DEFERRED_MC2A1_OR_MC3`，本轮零引入。

## 17. 是否触碰 Agenda/TaskGraph/Scheduler
**否**（SCOPE-1）：仓库无 TaskGraphStore/GraphProducer/AutonomyScheduler/AgendaItem 任何运行时；
三查询为纯 read model，无策略判断字段（home_quality/should_* 类字段不存在，GameTest+审计双重确认）。

## 18. 无法真实验证的项
- 无 `NOT_VERIFIED` 项：本轮所有验收点（schema/hash/边界/只读/busy/加载/LIVE×5）均有 raw 证据。
  唯一接近项：LIVE-2A0-5 的工具计数采用「plugin.test 断言 25 + 会话真实调用成功」组合证明
  （LLM 自述不作证据，已按任务书排除）。

## 附加：测试增量说明
- GameTest 9 个 required 全部使用任务书指定名称（mc2a0 前缀）；其中 #4/#7 运行于 mc1caR21 批
  （批内串行共享保留名——observeVisibleBlock 的 reserved 门要求 Mc1caBot；其余 7 个用独立
  Mc2a0Bot 避免并行批抢名，类注释记录该取舍）；
- JUnit `CognitiveCanonicalizationTest`（5）：map 插入序/数字编码/List 保序+集合排序/字符串转义/
  EvidenceRef round-trip 与 fail-closed；hash 语义（inventory/state 变化、时钟噪声、截断确定性）
  由 GameTest #1/#2/#4/#7 在真实 registry/世界状态下覆盖，与本单测分工互补；
- `BridgeCoreTest` +12 checks（总 70）：view 不清 needs_reconcile、fail-closed 分类、
  查询在 server tick 线程执行（future 语义）、observe 才解锁新工作。

## 结论
候选 **PASS**：任务书 §24 的 A–O 全部满足（A 精确基线 / B 614 旧全保留 / C 623×2=0 fail /
D replay 623=final / E node 37 / F,G clock-stable+delta-changes-hash / H 无泄漏 / I UNKNOWN 显式 /
J ref 作用域+伪造 fail-closed / K 只读+busy-safe / L R2.1 语义保留（614 旧测试全过）/
M bounded+显式截断 / N 真实 DSH 加载 25 工具 / O 无 Agenda/TaskGraph/Scheduler）。
