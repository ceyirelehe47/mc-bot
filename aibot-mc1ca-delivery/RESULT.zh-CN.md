# MC-1C-A 结构语义实机检查 — 结果

- 日期：2026-09-10（凌晨自主执行）
- 分支：`experiment/mc1ca-structure-semantics`（自 `f7dc7ba` 建支，先应用包内 prereq-R1，再应用 MC-1C-A）
- 上游：干净 `mc_aiplayer@a029fa6` 重放组合安装器后重建
- 实机环境：隔离副本世界 `D:\code\mc-experiment\mc-server-mc1ca`（全新 world，非长期存档）；DSH web + aibot-body 插件（19 工具版）；全程夜间自主执行

## 结论

```text
SOURCE_REPRODUCIBLE = PASS
COMPILE = PASS
UNIT_TESTS = PASS
GAMETESTS = PASS
DSH_REAL_LOAD = PASS
HOME_PROTECTION = PASS
AMBIGUOUS_LOG_FAIL_CLOSED = PASS
FARM_SEMANTIC_OBSERVATION = PASS
FARM_TEND = FAIL
REGISTRY_RESTART_PERSISTENCE = PASS
R1_SAFETY_REGRESSION = PASS
R1_PROVENANCE_REGRESSION = NOT_RUN
```

## 逐项证据

### SOURCE_REPRODUCIBLE = PASS
- `git checkout --detach f7dc7ba → switch -c` 分支；依次应用 prereq-R1、MC-1C-A，`verify_mc1ca_source.py` PASS。
- 包测试 10/10（`tests/test_mc1ca_pack.py` 5/5 + `prereq-r1/tests/test_r1_repair.py` 5/5）。
- 安装器在干净 a029fa6 上精确应用：16 个锚点文件（M0 的 12 + MC-1C-A 的 MiningAction/HarvestCore/PerceptionCollector/GatherQuotaTask）+ 12 个 overlay 新文件（9 + SemanticWorldRegistry/BreakPolicy/NaturalTreeClassifier）+ gametest 入口注册。
- `01-build/installer-tests.txt` 11/11、`r1-verify.txt` PASS。

### COMPILE / UNIT_TESTS = PASS
- 基线：`./gradlew clean test`（`01-build/baseline-test.log` BUILD SUCCESSFUL）。
- 应用后：`./gradlew clean compileJava test` 首次通过，零 Yarn 适配（`01-build/compile-test.log`）；65 个 JUnit 套件 0 失败（`02-test-results/junit/`）。

### GAMETESTS = PASS
- 新增专项 `MC1CASemanticsGameTests`（GT-1 保护拒绝/GT-2 SAFETY 覆盖/GT-3 结构原木非树/GT-4 自然树判定/GT-5 无叶 fail-closed/GT-6 农田统计/GT-7 registry 持久化+malformed fail-closed），经 installer 的 `EXTRA_CHANGES` 注册进 `fabric-gametest` 入口保证可复现。
- 全套 `runGameTest`（env `AIBOT_EXTERNAL_BOT=Mc1caBot`）：**All 594 required tests passed**（587 存量 + 7 新增，两次全绿，含 OreProspector 过滤修复后复跑）。`01-build/gametest.log`、`02-test-results/gametest/`。

### DSH_REAL_LOAD = PASS
- 插件 19 工具（`plugin.test.mjs` 断言 tools.size=19 + 三新工具，node 34/34）。
- 真实调用证据：`03-dsh/relevant-session.jsonl`（1044 行）含 agent 实际调用 `mc_register_home`（LIVE-1）、`mc_register_farm` ×2（LIVE-3）、`mc_tend_farm` ×3（LIVE-4）——非源码 grep。

### HOME_PROTECTION = PASS（LIVE-1）
- 木屋（oak_log 角柱×4×3 + 木板墙/地板/顶）+ 注册 HOME（radius=4, below=1, above=5），屋外自然橡树。
- **逐块快照**：HOME cuboid 567 格 `execute if block` 全枚举（`04-live/LIVE-1-home-before.json` vs `-after.json`），**sha256 完全一致** `635377f56952506eb912ae69b46cd87733825cdb93e9ac3519898237870717d8`，0 格变化——多次 gather 期间房柱曾被 prospect 选中为目标（`gather_prospect_unreachable found=3,113,3` 等），挖掘层硬门全部拦截。
- 外部自然树实际变化：多棵真实树被采伐（(14,115,46) 橡树、(12,113,0) 树基×3、孤岛树群）。
- 配额达成：oak_log=4 严格满足（`task_completed` 02:48:33）。
- 过程发现（非缺陷但重要）：上游 gather 的 family 计数把任意原木计入任意原木配额（cherry_log 满足 oak 配额导致秒完成），严格 postcondition 又只认精确物品——见 LIVE-1.log 多次 `observed=N:quota=4`。

### AMBIGUOUS_LOG_FAIL_CLOSED = PASS（LIVE-2）
- 泥土上 3 根竖直 oak_log、无叶、周围无其他树：gather 明确失败 `no_resource_nearby`（02:51:25），**0 格变化**，服务器无崩溃、无死循环。`04-live/LIVE-2.log`。
- 附加实证（天空孤岛，02:43）：树基被砍后剩余树干因失去泥土根不再判为自然树 → gather 停止采集——§3.3 保守性按设计生效（每棵树只能采到近地 1-2 根，配额需多棵树凑）。

### FARM_SEMANTIC_OBSERVATION = PASS（LIVE-3）
- 注册前 `mc_observe` → `semantic_world.nearby_farm_candidate`：`{crop:wheat, farmland:5, mature:2, immature:2, empty_farmland:1, needs_tending:true}` 与真实世界完全一致。
- `mc_register_farm {name:east_wheat, radius:4}` 注册成功并持久化（registry JSON）。
- 注册后 `semantic_world.farms[0]` 同样含精确统计。视角注记：Bob 在屋内时南墙遮挡观察（canObserve LOS），站到屋外即恢复——观察语义而非注册语义。

### FARM_TEND = FAIL（LIVE-4）
**失败子项：成熟作物未被收获。**
- 三次 `mc_tend_farm`（含距成熟麦 0-2 格、白天无敌对环境）确定性复现：FarmTask 只执行补种（`plant` 事件，空耕地→wheat age=0），从不生成 HARVEST 目标，任务 2-6 tick 即完成。
- 通过子项：空耕地被补种 ✓（3 次 plant 事件）；未熟作物未被破坏 ✓（age=2/4 跨越全部运行存活）；不破坏 HOME ✓；不修改农田外方块 ✓。
- 根因（代码级定位，未修）：上游 `FarmTask.survey` 的候选流以 `ObservableWorldQuery.canObserveBlock(bot,pos) || (pos.up())` 过滤——strict_survival 观察是六面射线命中判定；站立作物的碰撞箱极薄（4/16），射线命中其脚下整块 farmland 而非作物 → 作物格不可观察 → 永不进入 targets。空 farmland 是整块 → 可观察 → PLANT 正常。这属于上游 strict_survival 观察规则与 FarmTask 的交互问题，修复需改生产语义（放宽观察或改 FarmTask 目标发现），超出本轮授权（§6「如果必须改变生产语义，标 BLOCKED」）。
- 复现：注册农田 + 站熟麦旁 → `mc_tend_farm` → 观察 wheat[age=7] 保持原样。日志 `04-live/LIVE-3-4.log`（02:58:37 / 03:00:52 / 03:04:34 / 03:06:54 四次 farm 任务，仅 plant）。

### REGISTRY_RESTART_PERSISTENCE = PASS（LIVE-5）
- 注册 HOME + east_wheat + close_wheat 后正常停服 → 重启：`bot_restored` → bridge bound，DSH 重连后 `semantic_world` 同时恢复 `['home']` 与 `['east_wheat','close_wheat']`（03:10）。
- 重启后**第一次破坏性执行即受保护**：重启后在屋内 gather oak_log（附近唯一原木是受保护房柱）→ 任务失败、8 根房柱柱身逐格验证 0 变化（03:10:45，`04-live/LIVE-5-post-restart.log`）。不存在「重启后保护晚一拍」窗口。
- registry 在 HTTP endpoint 开放前加载：`SemanticWorldRegistry.start` 位于 `ExternalBodyRuntime.start` 的 try 内、`http.start()` 之前（installer 生成代码时序）；GT-7 另证 malformed fail-closed。

### R1_SAFETY_REGRESSION = PASS
- critical hunger + 有食物：`hunger_eat_started {critical=true, food=6}` → SAFETY EatTask → food 6→14（03:12:55）。
- critical hunger + 无食物：`survival_alert` 事件连续发布（seq 170-180+，`reason=critical_hunger_no_food`），无自主 HuntTask。

### R1_PROVENANCE_REGRESSION = NOT_RUN
- 服务器无真人客户端（仅 fake player Bob）；`ServerMessageEvents.CHAT_MESSAGE` 仅真实客户端聊天可触发（R1 轮已实证 fake player 广播/RCON 均不进入该管线），panel 需 mod 客户端。
- 可验证部分：本分支源码 `authorized_player_control` 0 处（verify_mc1ca_source FORBIDDEN 检查 PASS）；本轮事件流与服务器日志 0 合成身份。
- 待真人玩家补测（预计 10 分钟）。

## 本轮对包/上游的最小修复（超出纯应用的部分，全部可复现于安装器）

1. **R1 陈年缺陷（复用上一轮结论）**：包内 prereq-R1 生成的 installer 含三引号拼接语法错误（`''+'ACCESS'+''`，py_compile 失败）→ 最小修正为 `'''+ACCESS+'''`；`test_installers` 陈旧计数 10 → 16（12+MC-1C-A 的 MiningAction/HarvestCore/PerceptionCollector/GatherQuotaTask）。BreakPolicy 类注释补 reason 说明以满足包校验标记（纯注释，无行为变化）。
2. **OreProspector 选目标路径补过滤（生产代码最小修复）**：MC-1C-A 只过滤了 HarvestCore 两条 nearestReachableBlock；GatherQuotaTask 的 prospect/explore 走 `OreProspector.nearest`，会把受保护房柱选为 harvest 目标（实测 `gather_prospect_unreachable found=3,113,3`，靠挖掘层硬门兜底但引发路径超时→好树被拉黑→配额失败）。给两处调用加 `NaturalTreeClassifier.isHarvestCandidate` 过滤——这是包自身不变量（§3.3「不作为 gather log target」）在剩余选目标路径上的补全，不改变保护语义。修复后全套 594 GameTest 复跑全绿。
3. **GameTest 入口注册**：gametest 类需在 `src/gametest/resources/fabric.mod.json` 的 `fabric-gametest` 入口注册才会被 fabric 发现——为可复现性将该注册做成 installer 的 `EXTRA_CHANGES` 规则（带 blob 校验），否则干净重建后 7 个专项测试会静默消失。

## 环境注记

- 实机操纵沉淀：天空石台会被 mod 的位置管理拉回地面（fake player 位置锚定），验收场地须建在地面；RCON 远端区块未加载时 fill 失败，先 tp 加载再建。
- 证据目录含 `04-live/snapshot_region.py`（逐块枚举快照工具，验收方法本身的一部分）。
