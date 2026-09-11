# MC-2A Graph Core Foundation — 交付结果(RESULT)

- 日期:2026-09-11(深夜)~ 09-12(凌晨)
- 交付分支:`experiment/mc2a-graph-core-foundation`(自 `experiment/mc2a02-tree-harvest-reliability@0bff936c748acac7794f2624a1389bfc8b005ef6` 新建)
- 任务包:`mc-bot-mc2a-graph-core-combined-taskpack-20260911`(SHA256SUMS 10/10 校验通过)

## 1. 精确 SHA 与提交结构

| 阶段 | SHA | 内容 |
|---|---|---|
| base | `0bff936c748acac7794f2624a1389bfc8b005ef6` | 构造基线(与任务书一致) |
| Stage A | `c91b8cd…`(全 `c91b8cda…` 见 00-meta/stage-a-sha.txt) | 树采集访问闭包(补丁 0001+2 修正) |
| Stage B | `d94e885…`(见 00-meta/stage-b-sha.txt) | 图核心基座+机会垂直切片(补丁 0002+1 修正) |

两阶段两提交(任务书硬性要求 §Mandatory 8),Stage B 基于已 PASS 的 Stage A 提交。
无 hard-reset / force-push。

## 2. 补丁应用与真实类路径修正记录(必答)

补丁 0001/0002 均 `git apply` 原样应用成功。真实编译/测试暴露的修正共 3 处,
全部最小化且不削弱权威/重启/后置条件不变量:

| # | 修正 | 性质 |
|---|---|---|
| 1 | Stage A 契约测试 `TreeAccessContinuationSourceContractTest.genericPathPillar…`:PILLAR_UP 断言改为剥离块注释后匹配(javadoc 中的对比性提及非代码引用;既有 TreeHarvestSourceContractTest 的权威断言方向是反向的) | 契约断言形状(与上轮修正 #2 同类) |
| 2 | Stage A 新 GameTest `mc2a02GatherKeepsTemporarySupportsUntilHigherWorkFaceIsReached`:①复刻 LIVE D-1 触发几何——3 格土台高位冻结全树 proof 后撤台回地面(任务自驱动 acquire 与测试直调不同,需真实工位推进);②fixture 后 `getInventory().clear()`(保留身体背包跨批次持久,遗留原木会令 quota=1 一 tick 即 DONE——上轮 LIVE 教训②在 GameTest 的显现);③终态断言前置+失败路径 failAndDespawn 防泄漏 | 测试装置几何/清场 |
| 3 | Stage B `scripts/test.sh` 纯 JDK 编译集补入 `TaskGraphStore.java`(BridgeKernel/BodyBackend 新引用,MC-2A0 轮"kernel 依赖面变更须同步 .build 编译集"惯例) | 集成缺口 |

**Stage B 生产代码(Java/JS)零修正**——补丁作者的静态预验证+本轮前置 29 项 API
锚点审查兜住了全部签名面。

## 3. 自动化验证(任务书 §Automated validation)

| 套件 | 数量 | 结果 |
|---|---|---|
| JUnit(开发树) | 372(363 旧+2 Stage A+7 图) | 0 fail 0 error |
| JUnit(回放树) | 372 | 0/0 |
| GameTest round1/round2/replay | 634×3(633+1 闭包回归) | 0 fail |
| Node | 43(42+1,29 工具断言) | 0 fail |
| BridgeCore | 78 checks | 0 fail |
| Installer | 11 tests | OK |
| 干净回放 | 431→435 文件 | **逐文件 SHA-256 0 diff**(DIFF=[]/ONLY_IN_*=[]/exit=0) |

图 JUnit 七要素(任务书 §Graph JUnit must prove)全覆盖:
生产者幂等/声明冲突/completed+不满足≠DONE/重启 RUNNING→SUSPENDED/挂起不可分派/
reconcile 无重放证 DONE/取消释放声明(TaskGraphStoreTest 4 用例+契约测试 3 用例)。
机会物理执行器仍为既有 mine_opportunity:JUnit 源码契约(submit 区间+无 backend.start)
+ Node(稳定请求身份)+ 实机(服务器日志 mine_known_resource/origin_reason=external_dsh)。

## 4. LIVE 验收总表

| ID | 判定 | 关键证据 |
|---|---|---|
| LIVE-F1 高树支撑堆叠闭包 | **PASS**×3 | 支撑 #2 堆叠于 #1 之上→逆序清理→整树 8/1→零残留(三 execution 复证) |
| LIVE-F2 支撑期 SAFETY | **PASS**(弱读)+限制记录 | 外部 pause/resume 持支撑续接完成 ✓;僵尸抢占 pause/evade/resume 正确、位移离栈场景类型化债务 fail-closed(旧代码此场景为无限振荡,属下轮 access-planner 延期项) |
| G-LIVE-1 显式计划零物理 | **PASS** | 201/READY/SpatialRef 三元组无坐标/claim/持久化 bin/无执行/无变异+负路径 404/400 |
| G-LIVE-2 幂等+冲突 | **PASS** | 同 key 同 graph_id;异 key 409 `graph_resource_claim_conflict`;count=1 |
| G-LIVE-3 既有台账分派 | **PASS** | 普通 execution id+graphd- 稳定请求身份+服务器日志既有 mine 任务+无第二路径 |
| G-LIVE-4 DONE 证明 | **PASS** | completed 后节点 DONE 仅因 `postcondition_satisfied:opportunity_absent…`,该机会精确销账 |
| G-LIVE-5 不满足→STALE | **PASS(确定性装置)** | JUnit completed+UNSATISFIED⇒STALE(任务书明示"勿为制造而破坏生产世界");实机覆盖 SATISFIED/UNKNOWN 两方向+FAILED 实映射 |
| G-LIVE-6 重启零重放 | **PASS** | SUSPENDED 恢复/零任务重放/run-next 409/机会在则停留/世界证则无重放转 DONE |
| G-LIVE-7 取消释放 | **PASS** | CANCELLED/新 key 重声明 201/运行中图取消 409(图不拥有物理中止权) |
| DSH 实机会话 | **PASS** | 真实模型:connect→view→plan→inspect(同回合不结)→run_next(受理即结)→事件唤醒→终态 DONE 汇报;另完成 cancel+重规划;识别遗留恢复义务且未越权 |

LIVE 环境:隔离服 `mc-server-mc1ca`(Stage B jar sha256 `13250a90…`,
mods/aibot-0.0.1.jar)+ frozen DSH(5dda764+scratch-aibot-body 图工具插件,
deepseek/deepseek-v4.1-flash @ commandcode relay)。会话证据
`05-live-graph/dsh-session.jsonl.zstd`(208KB 多帧 zstd)+ graph 行提取。

## 5. 必答:是否有物理图路径绕过 BridgeKernel.submit

**否。** 图节点唯一物理出口是 `graphRunNext → submit(supplied, d.requestId(),
d.operation(), d.arguments())`;全仓 `backend.start(` 唯一调用点在既有 tick() 分派。
证据=源码契约测试区间断言+G-LIVE-3 实机(标准执行对象/标准任务日志)。

## 6. 图持久化路径/格式/生命周期(必答)

- 路径:`<world>/aibot/task-graphs-<bot>.bin`(实机 `world_play/aibot/task-graphs-bob.bin`);
  自定义二进制 `AIBODYGR1`/v1,全字段定界(图≤128/节点≤64/依赖≤16/参数 16KiB/文件 2MiB),
  tmp+fsync+原子替换;malformed fail-closed(503 清空拒绝运行)。
- 生命周期:显式 plan(TG-14 幂等,TG-13 声明)→ prepareDispatch 持久 RUNNING →
  submit 受理 → attachExecution → 物理终态(服务端线程)→ 后置条件三分支
  (SATISFIED→DONE/UNSATISFIED→STALE/UNKNOWN→SUSPENDED)/failed→FAILED/
  cancelled→CANCELLED/outcome_unknown→SUSPENDED;重启 RUNNING→SUSPENDED 零重放,
  reconcile 仅证不改;取消仅限非运行图并释放声明。
- TG-15:RUNNING/SUSPENDED 期间 run-next 一律拒绝(409),不静默改选 READY。

## 7. DSH 工具面

25→**29**(+mc_graph_plan_opportunity/mc_graph_inspect/mc_graph_run_next/
mc_graph_cancel),旧工具零移除(Node 断言+实机);plan/inspect 只读不结回合不占执行槽,
run_next 仅 accepted/running/paused 结回合(Node+实机双重验证);事件传输零回归
(BridgeCore 78+DSH 会话事件唤醒链)。

## 8. 范围遵守(Explicitly deferred)

未实现且未引入任何入口:全局 Agenda、后台 AutonomyScheduler、机会事件自动建图、
HOME/Farm/Safety 生产者、阻塞器惰性扩张、验证模板、情景记忆、架构规划器、
Iris 面;Cognitive View 的 scene_hash 未动(v0 认知缝=mc_graph_inspect)。
生产者接口 `createFragment` 为 private seam,留待后续确定性生产者。

## 9. 遗留与建议(下一轮)

1. 树访问事务重入(位移后回爬/同目标重入)仍为类型化债务 fail-closed
   (LIVE-F2 限制项,旧代码更糟;建议 access-planner 分层推进轮解决);
2. 机会 seen_from 不随 re-observe 更新(承接 MC-2A0.2 遗留);
3. G-LIVE-5 若需实机化,建议在受控 gametest 侧做"completed+仍存在"装置而非生产世界;
4. 图 list/inspect 路由当前不做 lease 校验(只读设计),若后续纳入敏感面可加 requireLease。
