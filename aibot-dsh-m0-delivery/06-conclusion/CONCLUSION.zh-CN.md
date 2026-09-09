# AIBot External Body → DSH M0 总结论

接手任务：把交接包（aibot-dsh-m0，源码 overlay + 安装器 + 离线测试）接入冻结上游、真实编译、真实 DSH 加载、隔离世界实机验收、交付证据。

**结论：核心目标全部达成；发现并修复了交接包原始实现的一个真实缺陷；未跑的场景如实标注 NOT_RUN。**

## 逐项结论

### P0-A 真实编译和上游接缝 — VERIFIED
- 冻结 checkout（a029fa6）基线 `gradlew compileJava test` 全绿（3m58s，含 12 个测试类）。
- 补丁应用（10 文件 blob 精确校验 + 18 锚点规则）一次通过；**交接包声称"未真实编译"的 9 个新文件 + 10 处补丁在真实 Fabric/Loom classpath 下直接编译通过，无需任何签名修正**。
- 修改后 `compileJava`/`test`/`build`（含 GameTest 链）全绿；`aibot-0.0.1.jar` 构建并部署实机。
- 默认（无 AIBOT_EXTERNAL_BOT）行为不变：65 个上游单测全绿。

### 发现的缺陷与修复 — VERIFIED（本仓库新增贡献）
1. **夜间任务崩溃**（交接包原始缺陷）：`DangerWatcher` 的 SYSTEM_BACKGROUND 自动任务（夜间照明/补给/进食/觅食/暗区点亮）对保留身体调用被护栏拒绝的 `assign`，未捕获异常炸服务器 tick 循环；且世界进入夜间后**任何重启 1 秒内必崩**。修复：4 个后台维持方法入口加 reserved 短路（SAFETY 路径全部保留：威胁自卫、岩浆自救、死亡重生、掉落回收）。
   - 第一版修复（整跳 DangerWatcher）教训：误伤 SAFETY 导致 Bob 被僵尸击杀后永久卡死——夜间实战验证了第二版精准修复。
2. **Windows 测试兼容**（交接包测试缺陷）：`http-integration.test.mjs` 存在观察缓存与执行状态的 1-tick 竞态（快回环下断言过早）；`child.kill('SIGTERM')` 在 Windows 不终止子进程导致 java 泄漏、测试进程挂起。修复：等待条件改为观察缓存确认；Windows 用 `taskkill /T /F` 树杀。修复后离线套件 3 项全绿、零泄漏。
3. **游戏内直聊增强**（用户需求）：免 @ 聊天直达 DSH（授权门与控制短语语义不变）；`say` 回复广播全服聊天栏。

全部修复已同步回交接包（apply_to_aibot.py 新增 2 个文件条目共 5 条锚点规则；aibot-overlay 的 MinecraftBodyBackend.java 更新），干净上游应用产出与实机部署版逐字节一致。

### P0-B 真实 DSH 插件组装 — VERIFIED
- DSH 官方流程完整执行（pnpm install → build → dsh web）于冻结 commit 5dda764。
- aibot-body 插件经 `--patch cordis.yml` 真实加载，运行状态"运行中"。
- 16 工具可见可调（实测 5 个：connect/observe/gather/craft/say）；canonical JSON 返回、执行错误正确展示。
- 事件驱动回合、concludeTurn 语义（accepted 后结束本轮、事件唤醒下轮）、重复投递去重均实测工作。

### P0-C 隔离世界最小实机验收 — VERIFIED（核心）/ PARTIAL（控制链）/ NOT_RUN（旧存档迁移）
- 基本串行链（连接→观察→采 4 原木→四步合成）全部 VERIFIED，含独立背包核对。
- 故障场景：服务器崩溃/重启对账/租约失效重连/死亡重生/安全抢占互斥 全部 VERIFIED（多为意外实战获得，证据完整）。
- 控制链（暂停/恢复/取消 + 旧 execution 隔离）：离线全绿 + 占用互斥实测；**游戏内完整 5 步序列 NOT_RUN**（被崩溃事故与用户要求重建世界中止；后续用户实玩可自然覆盖）。
- 旧存档（活动 Mission）迁移：NOT_RUN（按交接文档要求未用重要存档测试）。
- 玩家聊天到达 DSH：VERIFIED（用户实玩，免 @ 直聊 + 聊天栏回复，22:07-22:17 石器时代全流程 origin_reason=external_dsh）。

### P0-D 证据交付 — VERIFIED（本压缩包）
- 基线/修改后编译测试日志、GameTest（build 链内含）、DSH 会话完整 JSONL、服务器四阶段启动日志、crash report、subagent 影响范围审查报告、最终 diff 与安装器。

## 环境
Windows 11 / JDK 21.0.12.1(Adoptium) / Gradle 9.4.0 / Fabric Loom 1.16.2 / Node 22.19.0 / pnpm 11.7.0 / Python 3.13.7 / DSH 0.1.5-alpha.1@5dda764 / aibot@a029fa6 / MC 1.21.3。

## 遗留事项
1. 控制链 5 步游戏内完整序列待用户实玩覆盖。
2. 旧存档活动 Mission 迁移未验证（交接文档已列为后续独立工作）。
3. dsh web 重启后 30s 租约释放窗口内新会话连接会 409（可接受，等待即可）。
