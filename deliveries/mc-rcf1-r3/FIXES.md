# FIXES — MC-RCF-1-R3

审查项 → 修复 → 实测证据。所有"根因"均有运行日志/事件流支撑,
排查假设与已证根因分开陈述。

## F04 进食消费归因(实现+LIVE)

- 修复:`ConsumableComponentEatWitnessMixin` 注入
  `ConsumableComponent#finishConsumption`(HEAD,服务端玩家,只读),
  `RealClientEatWitness` 有界环存证;`eatSnapshot` 完成条件加入
  `witness>=claimed`(旧条件 after==before-claimed 可被外部清物满足)。
  客户端核心终态回归(失败终态不再被后续 tick 变 COMPLETE)。
- 离线:RealClientEatWitnessTest 2/2;RealClientEatDecisionCoreTest
  新增 2 组(终态保持+外部移物无见证)。
- LIVE:A08 双槽位 PASS,回执
  `server_authoritative_food_consumed:...claimed=1:witness=1`;
  V04 外部移物 → `client_eat_timeout`/不完成。

## F05 读路径纯化(实现+实测)

- 修复:`opportunities()` 去除世界查询/惰性剔除/journal 写;
  `maintain()` 为唯一后台写路径(ready() 每 32 tick,TTL 12000+
  容量淘汰,先 journal 后内存);`inspectLocalJson` 有界
  (radius 过滤+24 条上限+(distance,id) 稳定排序+truncated 仅计
  半径内,不泄漏未见目标;schema mc.local_view.v1)。
- 实测:服务端日志启动时批量 `opportunity expire-ttl`(旧机会
  全部按 TTL 退场,durable 回执);inspxxx 调用不再产生 journal。

## F06 stop 全角色核验(实现+假进程)

- 修复:stop 在"already gone"与"记录实例已停"两路径都重扫命名空间,
  非零 → BLOCKED(untracked/same-role instances remain),零才报
  STOPPED(scope verified 0);STOP_FAILED 分支恢复 continue 语义。
- 实测:L07 修正后 19/19;新增 L14(记录实例停后同角色额外实例
  → BLOCKED + 额外实例存活 + 再 stop → clean)。本轮每次真实
  stop all 回执均含 scope verified 0。

## F02 G4 事实采集(实现)

- Chain 采集器:完整未截断回执、pre/after-logs/final 快照、
  运行时身份(两端 jar SHA + game_session + control epoch + commit)、
  事件区间;限时 720s 逐步执行。
- 判定所据均来自 g4-runs-r3.json 原始事实(judge-g4 无任何自报
  布尔输入)。

## F01 checker 事实重算(实现+成对变异)

- v3:按操作类型重算(craft delta vs 快照、eat witness/claimed/
  库存三方对账、move 总量守恒、容器双向守恒、mine 增益、
  place interaction_witnessed+oracle passed);身份缺失/跨 jar/
  重复 (case,attempt)/outcome_unknown 全拒绝。
- 真实变异:evidence/mutations-r3(11 项全拒,含 eat-witness-zero、
  five-runs-from-one、initial-nonempty、cursor-occupied、jar-mixed、
  unknown-receipt)。
- 诚实性漏洞自查修复:原木不足曾静默按 PASS 计入(b05 4/5 事件)
  → fail_at 强制;该修复后矩阵整体重跑(前一轮 5/5 作废)。

## F03 G5 runner(实现;G5 仍 BLOCKED)

- armed 后 harness 结构性禁 rcon;三值避难判定(unknown→不安全);
  植物液体不算围护;morning_progress_ok(空包/缺镐不过);
  两自然场景声明(rcf1_g5_scenes.json)。
- 洞内连续放置小切片 PASS(先上后下;R2 阻断点关闭):
  修复 1:站位扫描扩展到目标下一层对角环并排除头顶目标格;
  修复 2:支撑面心瞄准内缩 0.35(place-diag 实证 cross 解析到邻格)。
- G5 BLOCKED 实录(尝试链保留):
  尝试 1:场景基点嵌入树冠(94,96,-70=叶)→ 感知空;修正场景声明。
  尝试 2:prep 正常、两次 goto 后 Bob 被不明客户端侧活动移回 110 格
  外(客户端日志仅 goto-diag;未定位到提交源)→ 采伐未完成。
  以上为实测现象;"客户端游走根因"仍是待查假设,不是已证结论。

## 采伐链实测根因(G4/G5 通用)

1. player_pos 缺字段默认 (0,0,0) → goto 目标=世界原点,Bob 被派
   出 ~107 格(传感器 dist=106.87 与几何吻合)→ fail-fast 修复。
2. 挖断格留洞 + 平视射线穿洞 → 准星 MISS → 机会不出生死锁
   (补洞后立即出生的对照实验实证)。
3. 高枝(112)超出客户端准星触达(4.5 格)→ 触达过滤(y≤py+4)。
4. 近距对柱目标中心瞄准先命中更低格(几何推导+gotostance 对照)
   → 南向主站位(目标正南 3 格)+4 方位轮试。
5. mine 回执 stale 但物理完成(掉落拾取晚于判定窗口)→ 迟效对账
   (块空+库存增)与掉落走位拾取,回执保持 failed 不改写。

## IA 事实采集缺口(如实)

- rcf1_tests_ia 全场景接入事实层(RecordingSession 包装 submit/term)。
- 租约竞态系列修复(共享 owner、do() 内会话重建+FACTS 保留、
  A09 容器解析、facts_case attempt 参数)。
- 最后一轮结果见 evidence/ia-facts-r3.json 与 ia-run-r3c.log;
  若仍有用例缺快照,逐条列于 RESULT 附录(不补 true)。
