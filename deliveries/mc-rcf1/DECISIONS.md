# MC-RCF-1 实现决策记录

工作记录,随门推进更新。模板空栏 ≠ 已通过。

## 源码与环境
- 审查快照:`61aa6a85296d5722a80fc935bcc6ca53bf8bfec5`(experiment/mc2a0-8r1-acceptance-closure,G0 刷新远端确认无新提交)。
- 实际开发祖先:**无父孤儿分支** `experiment/rc-foundation-v1`(首提交 b4502b2)。原因:审查快照全部可到达历史含 DSH 世代归档中的真实外部密钥(OpenAI sk-/GitHub/Notion,经 fbbc1b9 从树中脱敏但历史对象仍在远端);按 04_ENV_SECURITY §1.4 不带入新分支。新分支树 = 旧树减 deliveries/、archive/、.build/(磁盘保留,git 不跟踪)。
- 生产源码权威:`aibot-dsh-m0/aibot-overlay/` + `scripts/apply_to_aibot.py`(锚定安装器)→ 应用到上游 `zoyluoblue/mc_aiplayer` fork `a029fa6a`。G0 已证明:干净 worktree + 安装器输出 = 活树 D:/code/mc-experiment/aibot **字节一致**(仅 mixins.json 行尾换行归一化,语义无差)。
- 依赖锁:MC 1.21.3 / Fabric Loader 0.18.4 / Fabric API 0.114.1+1.21.3 / Java 21(运行 JDK 21.0.12.1+1 与 JDK 23.0.2 均可编译);gradle wrapper 9.4.0;经本地代理 127.0.0.1:7897 拉取 maven(用户环境既有配置)。
- 候选 C:未定(逐门推进,当前 b4502b2+);测试世界:`world_rcf1` seed 178000000001,服务器 rcf1-server(端口 25599/桥 8799/控制 8798/RCON 25598,与生产 25565/8765/8766/25575 隔离),客户端 rcf1-client。

## G0 关键事实
- 旧时代进程已停:up.py 看门狗 PID 49296、hub mc-env;全部 java 清零后无 8765/8766/25565 占用。
- 活树 = 上游 + 28 个未提交修改;其中 25 个由安装器 CHANGES/EXTRA_CHANGES 锚定补丁覆盖,2 个 craft 文件为整文件替换(本轮新增 REPLACES 机制修复,修复前干净重建被拒——已提交 5a879f0)。
- 空目录构建:干净 worktree `gradlew build` 成功产出 `aibot-0.0.1.jar`(sha256 前缀 68c1909021bd6a79),已部署 rcf1-server/mods 与 rcf1-client/mods(两侧同哈希)。
- GameTest 基线(诚实记录,未修):650 个测试,45 个失败(605 过)。失败集中在 MC2A02 tree-harvest(3)、MC2A0 cognitive-view(5+)、MC2A04 tracker(semantic_registry_not_started)等;完整清单见 BASELINE.md。G0 不要求修复,后续门触及相关语义时逐项处理。
- smoke(2026-09-20 21:19,run 身份 world_rcf1/runtime f137f8de→初始 87d2…):服务器起→Bob 真实客户端 JOIN(background)→桥 observe 返回 body_id=bob/instance=faa5dca3…/session epoch→say 执行 complete 且服务器日志实证 `<Bob> rcf1 smoke hello`→杀客户端→0 人在线+lease 503 body_not_ready_or_server_tick_stale→重启客户端→重新 JOIN+租约恢复+**新 session epoch**(77a00c52→de561824,incarnation fence 生效)。

## 安全处置状态
- 已验证:HEAD 树无凭证模式命中;`.secrets/`/`.build/`/新 token 均不入库;新环境 token 三件套(bridge/control/rcon)新生成,值不落日志。
- 待账户拥有者(明确阻塞,不代做):远端历史中的 DSH 归档密钥(OpenAI sk-jMcM…、GitHub、Notion)已实际暴露于 origin/experiment/mc2a0-8r1-acceptance-closure 可达历史,**需要用户撤销/轮换**;共享历史清理(force push/GC)未获授权不做。轮换完成前,新分支推送不含旧历史对象,不扩大暴露。

## 导航
(未开始;G2 填写)

## 动作与背包事务
(未开始;G3 填写)

## 变更与审查
- 旧入口禁用/迁移:旧时代脚本(wood*/stone/ironage/survival/up.py)保留在树中作为实验代码,本轮运行不使用;up.py 对历史目录 mc2a07ar-work/drivers 的 import 依赖由新 tools/rcf1_env.py 取代(路径/凭证全部配置化)。
- 受影响协议/调用者:无协议变更;安装器新增 REPLACES(向后兼容:旧行为=拒绝)。
- 每次关键失败、假设、修复与复测:见本文件追加段落。
- 独立 reviewer 或明确的二次自审:(待各门完成后)
- 仍需用户授权的事项:外部账户密钥轮换;共享历史清理。

## 关键失败台账
1. **installer 拒绝干净重建**(复现:rcf1-rebuild-base worktree `--apply` → `Overlay target already exists: craft/CraftingHelper.java`)。假设:overlay 两个 craft 文件为活树整文件修改,安装器无替换机制。修复:REPLACES 锚定整文件替换(blob SHA 校验)。复测:重建输出与活树字节一致。提交 5a879f0。
2. **构建 TLS 握手失败**(maven.fabricmc.net)。根因:`-g` 自定义 gradle home 绕过了用户全局代理配置。修复:包装 bat 显式传代理。复测:构建成功。
3. **服务器被 supervisor 树杀**:包装 python 退出→java 子进程同 Job 被杀。修复:java 作为被监督进程经 bat 直启。复测:服务器存活、桥就绪。
4. **external_bridge_start_failed_closed**:hub env 漏传 token。修复:bat 内 `set /p` 从 .secrets 文件读入。复测:桥绑定成功(fail-closed 行为符合预期)。

## G1 记录(2026-09-20 21:50)
- 修复:play.py submit 任意失败→cancel 无关执行(现仅 409 execution_in_progress + 显式 preempt 授权,取消对象=status 确切 execution_id);keepalive 固定 llm-play 越权重获(现 per-session owner);do_async 死代码删除。
- 修复:rcf1_env.acquire_lease reuse 路径丢弃桥轮换的新 token(返回旧值导致 control_lease_invalid)。
- 修复:RealClientActionController.clearInputs 漏 useKey/sneakKey/pickItemKey——取消进食/放置后按键残留会继续产生未授权使用。
- 发现(继承弱点,非本轮引入):goto face 转向超时(client_final_facing_timeout)在部分地形高频出现;walkTo 直线+局部偏航导致 C02 途中坠落到 y=94。均属 G2 Baritone 接管范围。
- C 组 LIVE 5/5 PASS(C01/C02/C03/C04-lite/C05),证据 D:/mc-rcf1-raw/c-group/c-group-20260920-214823.json。C06(use/cursor 取消)依赖 G3 食物/容器事务;C07(journal 淘汰)轻量 LIVE+journal 单测归自动化阶段;C08 记录设计证据(background 窗口+每 tick unpressAll 输入隔离+唯一仲裁器),真实共存需用户客户端在线,列为人工复核项。
- 环境事实:C 组 fixture(设白天+清 48 格敌对)是开发模式 armed 前布置,计分运行不用;首次无 fixture 运行时 Bob 被夜袭击杀→死亡重生翻转会话→在途执行正确转 outcome_unknown(fence 行为的意外实证)。

## 生命周期治理(用户指令 2026-09-20,优先于 MC-RCF-1 各门)
- 事故:重复拉起客户端(pid 文件单点失效+bat cd 失败期间),4 个同 UUID 客户端互踢顶号。用户叫停。
- 止血:hub rcf1-srv 停止(连树),全部 hub 条目终态无 restart;看门狗此前已停;我方 4 客户端全清,清单确认 5 个无关进程未动。
- 唯一入口 tools/rcf1_lifecycle.py:msvcrt 文件锁跨进程原子互斥;身份=PID+创建时间+命令行 marker 三要素;枚举失败禁止启动;停后零自动复活;连续 3 败锁定 BLOCKED(显式 unlock);配额 server/client 各 1(STARTING 占位)。rcf1_env 起停全部委托,无第二条拉起路径。subagent 禁止拉起游戏(其 tools 白名单不含生命周期入口)。
- 假进程测试 8/8 PASS(fake-tests-full.log):并发/连续/慢启动(等待 9.9s)/崩溃陈旧/PID 复用拒启/停后不复活/三败锁定/unlock 恢复。测试中发现并修复三处真缺陷:枚举过滤器漏 python、追加日志历史标记假就绪(改轮转)、_wait_ready 瞬态缺失即判死(改 5 次容忍)。
- 实机验证:4 路并发 server 启动→单实例同 pid 单监听;3 路并发 client→单实例入服;顺序幂等;stop 后 10s 无复活;重启正常;非 marker 进程零接触。

## G2 记录(2026-09-21 00:25)
- 兼容性切片(G2a):Baritone v1.12.0 api-fabric 官方资产(sha256 b3b36aa3… 三方核验:计划包/gh 认证 API/下载字节)与 aibot+tomstorage 共存零 Mixin 冲突;后台可驱动;#stop 后 drift=0.00。
- 适配器(G2b):RealClientNavigation 仅收 Body 目标;GoalBlock 精确站格(GoalNear 水平假到达已修);allowBreak/allowPlace/allowInventory/chatControl 每次进入强制 false;旧 直线+偏航+挖穿 机制从正式路径删除,组件缺失诚实失败。
- N 组 18/18(N01-N06 ×3,零世界改动,普通场景 ≤12s)。
- 工作站位评价:当前在玩层(站位=可站格+face 目标);G3/G4 需要时扩展评价器(脚/头空间/LOS 由 Baritone 可达性+face 距离约束隐式覆盖)。
- 绕行备选未启用(Baritone 达标);输入隔离与 Baritone 共存实测正常(unpressAll 在 actuator 写键前,Baritone 自写自读不冲突)。
- 教训入台账:源码三副本(overlay/活树/构建树)同步靠手工 cp 漏了控制器→旧字节码假跑 3 轮 N 组;现部署必须解包验证字节码再重启。
