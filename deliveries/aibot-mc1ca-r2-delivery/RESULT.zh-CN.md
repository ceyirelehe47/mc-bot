# MC-1C-A R2 实机验收结果

- 仓库：ceyirelehe47/mc-bot
- 分支：experiment/mc1ca-structure-semantics（R2 基线 e8cb2bd 之上继续提交）
- 上游重建基线：zoyluoblue/mc_aiplayer@a029fa6a3760fd0f83834c104051b041d986da60
- DSH 基线：deepseek-ai/deepseek-harness@5dda764ed3aa172535a7967b06ff95d9cbfe536a
- R2 包：/root/download/aibot-mc1ca-r2-repair.zip（md5 981d3f50d1dca3287649691b7dacef47，本地副本 C:/Users/15027/Downloads/aibot-mc1ca-r2-repair）

## 一、构建与集成（7 项）

| # | 项目 | 判定 | 证据 |
|---|------|------|------|
| 1 | apply_r2 精确应用（blob+锚点） | PASS | 包口径 11 blob 校验全对 + 13 文件写入（11 patch + 2 新增 overlay）；git 口径 14 文件（12 改含 SHA256SUMS + 2 增）；上游已应用侧三关键文件 blob 与 overlay 完全一致——01-apply/apply-and-verify.log |
| 2 | 包脚本转义缺陷修复（先行） | PASS（记录） | apply_r2_to_mc_bot.py patch_installer gametest-entry 锚点多一层反斜杠转义 + `\n` 实换行错误——subagent 审查发现、本地修复（2 行+测试样例）后全链路 20/20 锚点通过；远端原件未动 |
| 3 | verify_r2_source + test_r2_pack + py_compile + test_installers | PASS | R2 SOURCE VERIFY: PASS；9/9；py_compile OK；11/11（CHANGES=19） |
| 4 | 干净 a029fa6 重放 installer | PASS | 20 exact upstream blobs + 15 新文件（无锚点漂移） |
| 5 | gradlew clean compileJava test | PASS | BUILD SUCCESSFUL（JUnit 0 failure）；原始输出复跑归档 02-build/gradle-verify-rerun.log |
| 6 | runGameTest（AIBOT_EXTERNAL_BOT=Mc1caBot） | PASS | **600/600**（594 旧 + 6 个 r2* 专项实测出现在 XML）；03-gametest/TEST-aibot-gametest.xml |
| 7 | DSH plugin Node tests + dsh web 真加载 | PASS | node --test 34/34（原始输出 02-build/node-tests.log）（含 22 tools 断言）；dsh web --patch 实际加载，DSH 会话自报 22 个 mc_ 工具（含 mc_capture_home/mc_repair_home/mc_mine_opportunity），04-dsh-web-22tools.png |

## 二、实机验收（LIVE-R2-1..8，9 项）

| # | 项目 | 判定 | 证据 |
|---|------|------|------|
| 8 | LIVE-R2-1 DIG 路径不拆 HOME | PASS | 05-live-r2/LIVE-R2-1-dig-wall.md：goto failed/move_dig_no_progress + gather failed/no_resource_after_explore；墙 clone+if blocks 前后两次全等（0 变化）；服务器存活 |
| 9 | LIVE-R2-2 Farm harvest 闭环 | PASS | 05-live-r2/LIVE-R2-2-farm.md：register total_cells=5（connected exact mask）；tend done=3 实际收割、补种 2、缺种 skip 1（note 明确）；掉落物真实入包 wheat×5；mask 外草未 till；r2home 不受影响（R1 轮 FAIL 项修复实证） |
| 10 | LIVE-R2-3 维度同名隔离+restart | PASS | 05-live-r2/LIVE-R2-3-dimension.md：Overworld/Nether 同名 r2home 并存互不覆盖；视图严格维度作用域（双向）；完整 restart 后双侧/farm 5 格/4 机会/背包全延续 |
| 11 | LIVE-R2-4 双存档隔离/mismatch | PASS | 05-live-r2/LIVE-R2-4-world-isolation.md：world-id 2eadb4ef≠9558480e；registry 互不串（isob vs r2home*3）；植入 A registry 到 B → 启动即 world_id_mismatch fail-closed 崩溃拒启（零加载） |
| 12 | LIVE-R2-5A 采石途中机会 | PASS | 05-live-r2/LIVE-R2-5AB-opportunity.md：任务运行窗口内 5 条 ACTIONABLE 注入（时间戳交叉验证）；6 ACTIONABLE↔6 注入 1:1；原 execution 不被篡改；结束后 mc_mine_opportunity 63 ticks 精确闭环（挖/拾/销账）；嵌岩"僵尸机会"缺陷如实记录 |
| 13 | LIVE-R2-5B deferred diamond | PASS | 同上文件：无镐 BLOCKED/insufficient_tool→restart 同 id 存活→给铁镐 13 条全部重估 ACTIONABLE+1:1 推送→mine_opportunity 只挖那颗（coal/cobble/其余 13 条机会逐条未动）→销账→diamond×1 入包；pickup_timeout 缺陷记录 |
| 14 | LIVE-R2-6 自然树 lease | PASS | 05-live-r2/LIVE-R2-6-tree-lease.md：tree@-10 全树 5 节连砍无残留、tree@0 连砍 4 节；配额止浮空节被新 gather fail-closed 拒绝（语义自洽）；跨树跨种（5 oak+1 cherry）；R2-G family 语义正向实证；SAFETY pause 实机 NOT_RUN（GameTest 覆盖） |
| 15 | LIVE-R2-7 HOME capture/repair | **FAIL（部分）** | 05-live-r2/LIVE-R2-7-home-repair.md：capture ✓（4 次）、conflict 409 原子 fail-closed ✓、extra 不删 ✓、missing 检出 ✓；**missing 恢复 ✗——BuildTask 放置对全部目标 80-tick 空转 target_timeout（equip 从未发生、与距离/站位/材料无关），按任务第 6 节记录真实失败未擅改** |
| 16 | LIVE-R2-8 R1 SAFETY 回归 | PASS（GameTest）+实机 NOT_RUN | 05-live-r2/LIVE-R2-8-safety-regression.md：600 GameTest 含 R1 SAFETY 语义全过 + R2 未触及 SAFETY 代码；实机触发工具性不可达（hunger effect 对 fake player 无效、player NBT 不可写、自然饥饿需小时级）——如实记录 |

## 三、缺陷清单（如实记录，未掩盖）

1. **[阻断修复] R2 包 apply 脚本转义缺陷**（#2）——本地最小修复后全链路通过
2. **[实机 FAIL] BuildTask 放置空转**（#15）——ensureObservableWorkPose 或 nearbyStand 静默失败路径，1/1 与 3/3 场景均 target_timeout、零放置；需上游修复
3. **[可用性] wrong 预检对自然变化过敏**（#15）——基线含 dirt 时草蔓延持续制造 wrong 使 repair 永久 fail-closed（两次实证）
4. **[缺陷] 嵌岩机会僵尸悬置**（#12）——visible_but_not_reachable 不销账不降级，last_seen 反复刷新
5. **[缺陷] known_resource_pickup_timeout 不可重试**（#13）——销账先于拾取，失败即丢
6. **[语义确认] gather family 计数**（#9/#14）——oak 配额被 cherry 满足为 R2-G 设计语义（DSH 视角存疑，双方观点保留）
7. **[记录] mine_opportunity 远距 pathfinding_throttled**（#12）——按任务文档以 mc_goto 分段靠近规避

## 四、环境与安全

- 实机栈收尾全关停：MC 服 A/B（25565/25566）、桥（8765/8766）已停；dsh web(3080) 验收后关闭
- 密钥零泄漏：DSH session（zstd 归档）扫描 COMMANDCODE key 0 命中、bridge token 0 命中；证据目录不含任何密钥
- SHA256SUMS 重生成并校验（aibot-dsh-m0/）
