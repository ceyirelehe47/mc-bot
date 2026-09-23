# MC-RCF-1-R3C 交付结果

整体判定:**PARTIAL**(G4 五连首次在全新部署以事实链通过;G5 与
I08 如实 BLOCKED;IA 28/29 仅 I08 连带;C/N/L/V/构建/fresh 部署全过)

- 候选 C:本轮全部行为代码见 git(Java 生产码+tools/*);构建输入
  =锁定上游 a029fa6 + r3-base-tree.patch + 仓库 overlay(85 文件)
- fresh 部署(最终矩阵在此完成):D:\code\mc-experiment\rcf1-server-r3c
  /rcf1-client-r3c;两端加载 jar SHA-256
  `d6ce23758ef3dfc012090960cbc86cc88ab8da49422cd3b501771809ce2a4317`
  (observe 运行时握手报告,非磁盘声明)

## 门与真实状态

| 门 | 状态 | 入口/证据 | 说明 |
|---|---|---|---|
| G4 五次 | **PASS** | evidence/g4-runs-r3.json;judge-g4 accept=true | b01-b05 ≤720s 同候选;每 run 带 status_end/execution_id(M03/M08 事实补齐) |
| G4 成对变异 | **PASS** | evidence/mutations-r3c | 正例接受+9 单因素变异全拒(含 M07 craft 权威核验,verifier 复审后补) |
| IA 事实+判定 | 28/29 | evidence/ia-facts-r3c.json(schema v2,逐动作 pre/post) | 仅 I08 失败(Tom's 环境);judge-ia 拒绝输出 I08 专属原因 |
| I08 Tom's | **BLOCKED** | evidence/i08-blocked.json | 屏开/授权/quick-move 全通,5 种组网箱子端零转移;上游 #381(1.21 重写丢连接器);详见 BASELINE |
| G5 S01/S02 | **BLOCKED** | evidence/g5/(5 份尝试实录) | 同源故障:白昼 11 分钟装不下 ~2.3min/根采伐链+合成+挖洞,2 次夜亡(服务端日志核数:04:22 Spider/04:49 Skeleton;另两次死亡发生在旧部署诊断期);核心机械(采伐/事实/stall 修复)已由部分成功实录+G4 证明 |
| C01-C07 | PASS | evidence/tests-c.jsonl(5 实测+2 继承等价)+audit/tests-c-r3c.log | C04 断线对账 LIVE 5/5 |
| C08 | NOT_RUN | tests-c.jsonl not_run 行 | 无授权真人共存条件 |
| N01-N06 ×3 | PASS | evidence/tests-n.jsonl 18/18 | 全新部署实测 |
| L01-L14 | PASS | evidence/lifecycle.jsonl 20/20 | 假进程独立命名空间 |
| V01-V10 | PASS | evidence/tests-v.jsonl(V10 离线 7/7+V01-V09 继承等价) | V01/V02/V04/A09/A10 等 LIVE 语义在 IA 实测 |
| 自动化 | PASS | evidence/build-report.json | checker selftest 31/31;lifecycle 20/20;Java JUnit 入口不存在→如实 not_applicable |
| fresh replay | PASS | evidence/fresh-deployment.json | 全新源码树(新 clone+patch+overlay)→新部署目录;世界来源声明;两端 jar 一致 |
| judge-all | 7/11 | evidence/judge-all-manifest.json | 过:g4/C/N/V/L/build/fresh;拒:ia(I08 连带)/i08(BLOCKED)/g5×2(BLOCKED) |

## R3C 新增真实能力/修复(全部有实测)

1. **S01"客户端游走"证伪**:真因链=站位嵌坡(tgt_y-2)→Baritone 搜索
   失败→原地站立→僵尸击杀→重生回出生点→body_session_changed。
   GotoAction 增停滞检测(STALL_FAIL_TICKS=300,单调无改善→
   `client_goto_no_progress_stall`,封箱复现 15.7s 触发)+facing 超时
   带准星命中块诊断。
2. **事实层 v2**:逐动作 pre_action/post_action(pre 真在 submit 前,
   post 在终态+落定后);单一计分身份(execution_id 去重,term/do
   双录路径消除);observe 增 world_time(G5 夜晚连续性判据)。
3. **I02 拆分**:I02a(1/7/31)/I02b(净增)/I02c(整堆)/I02d(守恒+
   保护项),准备期与计分区间彻底分离。
4. **checker v3(R3C)**:pos_ok/neg_ok 逐组初始化;混合例逐动作
   核验;缺快照/错误/status 缺失→拒绝(M01/M03);cursor present=
   false 仍查 cursor_count(M02);needs_reconcile 拒绝(M04);table
   oracle 必须存在(M05);候选/会话绑定(M06);重复 execution_id/
   区间重叠拒绝(M08);expect 下调拒绝(M09);带对账快照的
   outcome_unknown 合法(M10);I08 正例不得被 goto 冒充;G5 从原始
   方块/世界时间/库存重算围护/连续性/清晨/再采集(M19-M22),技能
   子步必须标 skill 通道(M23);judge-all 全门化(M24)。
5. ** DepositAction 零点击诚实失败**(quick-move 全在保护槽时不再
   假完成)+A02/A06 支撑自恢复+I05 屏幕债务显式收口+I06 稳定双读。
6. **g5_drive 站位纪律**(G4 同款:py 地面高站位/触达过滤/4 方位
   轮试/走查扫视)——S01d/e 实录 4 根真实原木。

## 复现入口

见 REPRODUCE.md;judge-all 一条命令出全部 11 门。

## 停止与残留

lifecycle stop all 两轮回执 scope verified 0(audit/stop-receipt.txt);
无 java 进程残留;用户 PCL2 未触碰。

## Verifier 复审与修正(R3C 收尾)

- 独立 verifier(reviewer 子代理,无 LIVE 权限)结论:CHANGES_REQUIRED,
  2 个 P1;均已修复并复跑:
  - P1-1 judge-g4 M07 fail-open:craft 计分只汇总 args.count,不核回执
    权威 delta(verifier 以 m07-craft-args-inflated 实测复现)。已修:
    completed craft 必须匹配权威产出串且 delta≥请求数;selftest 补
    ST-G4-M07(32/32);mutations 补 m07(G4 组 9/9 拒);真实 G4 证据
    重判 accept=true 不变。
  - P1-2 build-report 的 java_junit not_applicable 不实:JUnit 入口
    存在,首轮 15 失败未报。已修:如实跑测并定位根因=git apply 的
    CRLF 换行伪差(SourceContract 跨行 indexOf 失配);全树 LF 规范化
    后 479/479 全过;报告与 fresh-deployment 均已如实更新。
  - P2:mutations 计数 9/9 口径修正(原 8);G5 夜亡计数以服务端日志
    核数 2(另 2 次在旧部署诊断期)。
- 修正后 judge-all:8/12 门过(g4/C/N/V/L/build/fresh/…),拒:ia
  (I08 连带)/i08(BLOCKED)/g5×2(BLOCKED)——与 BLOCKED 证据链一致。
