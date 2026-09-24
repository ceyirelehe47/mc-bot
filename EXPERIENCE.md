# mc-bot 通关之路：经历文档

> 目标：**不作弊通关原版 Minecraft（击杀末影龙）**——LLM（会话内 AI）直接操控真实客户端身体（Bob），边玩边完善 bot。
> 本文档记录从空白接手到当前的完整历程。更新至：2026-09-20 15:00。

## 一、总体架构（当前）

```
┌─────────┐   HTTP(127.0.0.1:8765)    ┌──────────────┐   控制TCP(8766)   ┌────────────────┐
│ LLM 玩层 │ ──Bearer+租约+幂等执行──▶ │ 桥(mod,服务器)│ ──命令/回执────▶ │ Bob 生产客户端  │
│ tools/*.py│ ◀──────认知视图/事件───── │  AIBot 外身   │ ◀─心跳+传感器── │ (真实 MC 1.21.3)│
└─────────┘                           └──────────────┘                  └────────────────┘
```

- **LLM 玩层**（`tools/`）：`play.py`（异步 API：submit/poll/term/overview/inspect-local 分层查询）、`up.py`（环境起停自愈）、各时代策略脚本。
- **桥**（mod 服务器侧）：单执行槽、租约互斥、执行账本（journal 持久）、认知视图（32KB 上限截断）。
- **Bob 客户端**：无人值守真实 MC 客户端，输入隔离（防人鼠键干扰）、30fps 保底、自动重生。

操作集（8）：`goto`(绕障+挖穿) / `mine_opportunity` / `craft` / `eat` / `place` / `deposit` / `say` / 控制(pause/resume/cancel)。

## 二、两个时代之前：方案演进

1. **DSH 外脑时代**（已归档）：AIBot 身体 + DeepSeek Harness 大脑，HTTP 桥全链路打通（mc1ca 世代）。
2. **感知时代**（mc2a0.x）：真实客户端身体、后台原生屏幕、屏幕所有权、360° 全向语义感知（8r1 冻结基线）。
3. **LLM 直控时代**（当前）：用户拍板弃用 DSH，会话内 AI 直接通过桥操控。本文件主要记录这段。

## 三、LLM 直控时代大事记

### 阶段 0：工作区整理
- mc-bot 仓库归档：25 个交付目录→`deliveries/`，实验散件按批次→`archive/experiment/`，活工具→`tools/`，密钥→`.secrets/`（不入库）。
- 删除旧实验世界，重开新档（种子 4167799982467607063，生存/简单难度，不作弊约束生效）。

### 阶段 1：直控闭环打通
- 复用 8r1 的服务器克隆（mc-server-mc2a07ar）+ 生产客户端，`up.py` 环境自愈（join 失败自动冷重启双方）。
- `play.py` 桥客户端：observe/view/inspect/inspect-local/goto/mine/craft/eat/place/say 全接通。
- **关键纠偏**：用户指出 RCON `kill` 清怪属于作弊 → 全部撤除，此后 RCON 仅只读观察。旧世界被 kill 污染已重建。

### 阶段 2：基础设施连环加固（每一条都是实测踩坑）
| 问题 | 根因 | 修复 |
|---|---|---|
| Bob 死亡卡死屏 | 客户端无人点重生按钮 | 服务端 auto_respawn（重生点 24 格有敌对则推迟）+ 客户端 DeathScreen 自动 requestRespawn |
| 入服" Took too long to log in"循环 | 后台窗口被压到极低帧率，CONFIGURATION 阶段超时 | RenderSystem.limitDisplayFPS mixin 保底 30fps |
| 点击窗外弹暂停菜单吞输入 | pauseOnLostFocus=true | options + mod 双层强制 false |
| goto 山地原地跳死锁 | 寻路=直线走+撞墙跳（跳不过 2 格坎）；collision 标志 tick 时序不可靠 | walkTo 统一入口：距目标无改善检测→偏航绕行序列→确定性位置挖穿（真实挖掘）|
| 执行槽死锁 | cancel 回执丢失后客户端 action 永不终止 | 客户端单槽替换语义 + 150s 硬超时 + 服务器 goto 45s→120s stall 检测 |
| 账本 503 capacity_exhausted | 执行表 1024 上限只增不减，journal 重放回满 | 终态执行内存淘汰（历史仍在 journal）|
| 认知视图 503 exceeds_hard_limit | 泥土机会刷爆 32KB 视图 | 视图机会卡按距离截断（40→56）|
| 机会字段错位 | 玩层用 `o["id"]`，实际是 `object_id`；坐标在 inspect 证据顶层 x/y/z | 全部玩层脚本修正 |
| craft"完成"却不消耗 | craft 是最终配额语义（已有≥请求则无操作）；oak_planks 双配方 find() 无序二选一 | 请求当前+增量；配方合并 anyOf（含 stripped 原木）|
| face 对不准 | 视线被山坡遮挡（crosshair 打到远处坡面）| facing 超时 200→400tick + 服务器 1 格容差 + 玩层 5 方位站位轮试 |
| 多进程互抢租约 | 租约状态文件全局共享 | owner 隔离状态文件；submit 撞槽→LLM 抢占（主动 cancel 残留执行）|
| 视野太小找树难 | 感知半径硬上限 8/12 | 校验放宽至 32/48，env 拉到 24/32；新增 overview() 分层聚合查询 |

### 阶段 3：游戏推进（真实游戏内成绩）
- **出生**：平原村庄（钟/门/村民），森林在 362 格外（`/locate biome` 只读）。
- **首块原木**：机会→inspect 坐标→mine 闭环验证（server_authoritative_block_and_inventory_gain_verified）。
- **拆村取材**：感知只报 INTERACTABLE 方块 → 改从机会列表挖 stripped 橱柱/原木。
- **木板×15 → 工作台 → 木棍 → 木镐 → 木斧**：服务端背包合成全部 completed 验证。**木器时代达成**（2026-09-20 08:20）。
- **place 操作已实现两端**（挖洞封口过夜用），待实战验证。
- 石器时代进行中：圆石采集（下挖到石层）→ 石镐/熔炉。

### 夜晚生存（核心损耗）
- 夜间敌对生物围攻 → 死亡掉落 → 回出生点 → 白天进度清零，曾造成"一晚进度为 0"。
- 已修：夜静默策略（不执行世界动作）；进行中：水平挖洞+place 封口过夜。
- 夜间死亡非作弊（auto_respawn 是游戏机制），但损耗大，封洞是根治。

## 四、当前状态

- 环境：mc-env 常驻（hub 管理），服务器+Bob 客户端健康。
- 背包（最近观测）：木镐/木斧/工作台已验证可达成；当前轮次在"挖木→木板→木镐→下挖石"循环里反复（偶尔夜亡清零）。
- 操作集 8 个全部实现（place 待实战）。
- 玩层脚本：`survival.py`（石器时代状态机：挖木兜底→镐→下挖石→石镐/熔炉→黄昏封洞）。

## 五、下一步路线

1. **石器时代收口**：圆石≥14 → 石镐+石斧+熔炉（survival.py 跑通）。
2. **过夜封洞实战**：dusk 触发水平挖洞+place 封口，验证夜间零死亡。
3. **eat 实战**（腐肉兜底逻辑已实现）。
4. **铁器时代**：下挖找铁矿（tracker 矿石机会原生支持）→ 熔炉冶炼（需实现 smelt 或用挖穿凑合）→ 铁镐/铁剑/铁甲。
5. **食物农业**：小麦种子已在手 → 锄地/种田（待实现 farming 编排）。
6. **中期**：钻石→下界→末地→末影龙。

## 七、MC-RCF-1-R3 轮(2026-09-23)
- **G4 正式 5/5 首次以事实链通过**(judge-g4 accept=true):两端运行时 jar SHA 握手、完整回执、迟效对账、事件区间校验。
- **进食见证(F04)**:mixin 注入 ConsumableComponent#finishConsumption;LIVE 回执 witness=1;外部移物不能冒充消费。
- **实测根因**(带日志实证,详 deliveries/mc-rcf1-r3/FIXES.md):
  1. player_pos 默认 (0,0,0) 把 Bob 派去世界原点(传感器 dist≈107 吻合)。
  2. 挖断留洞→平视射线穿洞→机会死锁;补洞对照实验立即出生。
  3. 高枝超客户端准星 4.5 格;近距柱目标中心瞄准先中更低格(南向主站位修)。
  4. PlaceAction 面心瞄准落格边界被解析到邻格(内缩 0.35 修)。
  5. 客户端动作无硬预算→取消竞态→Baritone 自旋 10 分钟+(硬预算修)。
- **G5 仍 BLOCKED**:客户端侧不明游走(两次尝试 Bob 被移回 110 格外,提交源未定位)——下轮最高优先排查。
- **洞内连续放置修复**(R2 遗留):站位扫描含下一层对角+排除头顶目标格;先上后下封口序;小切片 PASS。
- **教训**:runner 不把查询错误当空结果、不默认坐标、不静默 PASS 数量不足——三个都是本轮真实咬人的坑。

## 六、约束（不可违反）

- **不作弊**：无 RCON 修改世界（仅只读）、无创造物品、无 teleport、挖掘/合成/放置全部走真实客户端动作（工具/时长/掉落遵循游戏规则）。
- 桥只绑 loopback；token 不入日志/证据文件。
- 死亡/重生是游戏机制；掉落损失真实承受。

## MC-RCF-1-R3C 轮(2026-09-24)
- **G4 五连首次全绿**(全新部署、同候选、每 run 带 status_end/execution_id);judge-g4 accept=true。
- **S01"客户端游走"证伪**:R3 的 body_session_changed 真因=站位嵌坡→Baritone 搜索失败→站立→夜怪击杀→重生回出生点(距场景 112 格)。不是客户端漂移。修复:GotoAction 停滞检测(单调无改善 300tick→fail)+站位纪律(py 地面高,非 tgt_y-2)。
- **I08 深挖到底仍 BLOCKED**:Tom's 终端屏开/授权/quick-move 全通,但 1.21-2.1.2 重写版网络在任何组网下零收货(上游 #381 丢连接器)。教训:第三方 mod 的"重写版本"要先查 issue 再排产线。
- **事实层 v2 三教训**:①pre 必须在 submit 前真采(懒采=终态后拍照);②稳定双读必须带时间间隔(背靠背双读读的是同一个旧状态);③run() 清 _CASE_ID 会把多检查场景的后半段动作全部漏录。
- **感知坑**:跟踪器只收录视野/准星扫过的方块——站定转身只录取最终朝向;基点在旧挖掘坑=感知死区。要走查(短途 goto 环路)才有效。
- **满包场景的屏幕债务**:36 槽全满的 craft/容器失败会把内容物滞留光标/网格,下一 case 开屏瞬间倾倒污染守恒——场景末尾必须显式收口(腾槽+goto 强制关屏+清落实体)。
- **vein_miner 蚀平台**:采矿链会连锁破坏测试平台支撑石,A02/A06 类 place fixture 必须自恢复支撑。
- **G5 现实约束**:白昼 ~11 分钟 vs 采伐链 ~2.3min/根+合成+挖洞——链速不提速就必死于夜。下轮方向:goto-face 周期压缩(<60s/根)或场景选址贴树。
- **Verifier 复审两 P1 均值得**:①checker 的 craft 计分只数 args 不核回执权威串——M07 类变异直通;计数类判定必须锚定回执权威数字。②"JUnit 入口不存在"是想当然——fresh 树 479 测试,首轮 15 败全因 git apply 在 Windows 产 CRLF、SourceContract 跨行 indexOf 失配(纯换行伪差);**Windows 上 patch 后必须 LF 规范化(core.autocrlf=input 重检出)**。

## R3D(2026-09-25)
| 现象 | 根因 | 修复/教训 |
|---|---|---|
| D 切片全 409 control_lease_invalid | 平台 tp 至 y130 半空→摔死→body session 翻转→桥清租约(applyBinding) | 诊断 tp ≤4 格落差;play.submit 对租约失效一次重取+observe 对账,失败上抛 session-lost |
| D 切片感知恒空 | 石平台 289 块全为 STONE_FAMILY 候选,挤占 BLOCK_CANDIDATE_LIMIT=128;且 fill 在未加载区块静默无效 | 诊断平台用 dirt(无类别不跟踪);先 tp 加载区块再 fill;`execute if block` 而非 `data get block`(仅 block entity) |
| goto 到站位却"面对失败" | goto=宽松到达半径 2.5 格,≠交互站位;单格阻挡无效 | 站位阻挡须盖住 2.5 半径球;真实不可达用 5 高墙/全封闭;技能层以机会出生为准入 |
| 柱顶原木"第9次采到" | 柱顶与盖之间 log 层水平暴露,贴柱仰角射线掠过柱顶 | 全封闭才真阻断;该轮也证禁令合同(每组合恰 2 次) |
| R3C I08"上游 dropped connectors" | `[facing=east]` 非法块态→setblock 静默失败,连接器从未放置;真上游限制=1.21 重写缺 cable connector(#381 作者确认) | setblock 必须核回显;toms_diag(候选内只读反射 op)区分"连接器已链/终端聚合 0" |
| G4 b01/b02 石料预算耗尽 | 全新世界野地使南向站位不可达(旧世界场地被隐性清过) | 受控 fixture 必须连同站位环一起声明(清空+地板);石料不足补诚实 fail_at |
| IA 多例连败 | 工作区随世界而定;敌怪反复杀 Bob(give 落空) | safe() 标准化工作区(清空+铺板);注入/判定型套件用 peaceful 窗口并在结束时恢复 |
| G5 S01 失败×2 | ①场景无 minable 石(泥土不在感知类别=合法链不可挖)②树在 4 深坑③板式掩体 S 封口/顶盖 PlaceAction 准星错配(sent=true 打错面) | G5 选址判据:平地树+50 格露头石+缓坡;PlaceAction 支撑面匹配待修(place-diag 已留证) |
| V05 抖动 | 容器事务太快,并发窗口错过 | 用长导航占槽(deterministic) |
