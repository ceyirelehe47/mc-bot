# RESULT — MC-2A Graph Core R1.2 Durable Opportunity Birth

任务包: mc-bot-mc2a-graph-core-r12-durable-birth-taskpack-20260912.zip
(12 文件 sha256 与 SHA256SUMS 逐一核对通过)。

1. **SHA**: 分支 `experiment/mc2a-graph-core-r12-durable-birth`(从生产基
   `e52558209b7890128fb22fe172f77819dcb3e21a` 新建); R1.1 reviewed HEAD
   `e300f09fe20dfe943a1ee332b591c7420654f8c7`; 干净重放上游 `a029fa6a…`;
   本轮生产提交与证据提交 sha 见 00-meta/production-final-sha.txt 与
   00-meta/evidence-final-sha.txt。

2. **补丁应用与全部修正**: 0001 以 `git apply --recount -C1` 应用(假行号窄上下文,
   与 R1.1 同法); 0002 同参数因 preimage 一处多一空格被拒, 加
   `--ignore-space-change` 后仅剩 markOpportunityStale hunk 仍拒收(git apply 对
   oldpos=1 hunk 的 match_beginning 限制), 以 `--reject` 应用其余 hunk 后用 Edit 按
   补丁 +侧逐字落地该 hunk。配套修正 5 项(journal 绑定生命周期/文件名大写/gt7 cause/
   SHA 重生成/新测试文件入库)全部记录于 07-audit/scope-audit.md 与 lifecycle-order.md。

3. **birth 收据字段**(kind=resource_opportunity_birth, 18 字段): execution_id=""/
   world_id/dimension/opportunity_id/block_id/x/y/z/seen_x/seen_y/seen_z/status/
   blocked_reason/required_tool/last_seen_game_time/state_since_game_time/state_x/
   state_y/state_z/pickup_baseline——与 ResourceOpportunity 18 参构造逐字段对应,
   足以恢复精确身份与保守运行记录。

4. **birth durable-before-exposure 顺序**:
   `appendOpportunityBirth(journal,next)`(同步 fsync, 失败抛
   semantic_registry_failed_closed)→ `OPPORTUNITIES.put(key,next)` →
   `resourceOpportunityActionable(...)`; 启动侧 `reconcileOpportunityLifecycleReceipts(journal)`
   先于 `new TaskGraphStore`/`new BridgeKernel`/`new BridgeHttpServer`。两处顺序均由
   OpportunityBirthDurabilitySourceTest 源码契约锁定(02-build junit 385 内含)。

5. **LIVE-R12-1 的 A**: `ore_fefc057feb30_24947ca16ae04bb2`(装置格 (562,68,127))。

6. **A 的 birth 收据序列**: seq **909**(durable, 见 04/journal-receipts-A.txt)。

7. **Graph subject**: 原图 `graph-4f8553847a1b2ae206e5d6fa` 与重做图
   `graph-f332a00f0e91dca5…` 的 object_id 均为 A(回滚前 inspect/plan 实测)。

8. **语义快照哈希**: 回滚注入 before-A 与重启恢复后的对比见
   04/snapshot-hashes.txt(恢复后 22 机会、装置格唯一条目=精确 A、无 B)。

9. **恢复先于端点暴露**: 纯启动→即停(无任何 observe/tp/HTTP 查询)后持久化注册表已含
   精确 A——A 只能来自 journal 重放; 源码顺序由契约测试锁定(reconcile→graphs→kernel→http)。

10. **再观察不铸 B**: 阶段 C tp bot 矿旁再观察, 装置格 id 仍为 A。

11. **Graph 经 submit 达 DONE**: 原图重启后 FAILED|execution_failed(跨重启执行作废,
    R1.1 既定语义负分支, 与快照陈旧无关——inspect 实测, LIVE-R12-1.md 如实披露;
    runbook"still nonterminal"未覆盖该路径); 按 R1-3 先例同一机会重新成图
    (object_id 不变)→ run-next → submit → 挖矿拾取 →
    **DONE|postcondition_satisfied:durable_inventory_gain_receipt**;
    journal birth(909) < consumed(**955**)。

12. **LIVE-R12-2 收据序列**: C=`ore_fefc057feb30_07b497c525c74dfd`,
    birth(**960**) < stale(**962**)(05/journal-receipts-C.txt)。

13. **终态主导证明**: stone 替换经正常观察路径产生 durable stale 后, 注入含 C 的旧快照,
    纯启动即停重启后注册表无 C、装置格空(05/snapshot-hashes.txt 记录注入快照 sha 与
    复检 False)。

14. **LIVE-R12-3 legacy id**: `ore_0123456789abcdef0123456789abcdef`(32 hex 旧式)。

15. **升级前无 birth**: 注入后读 journal 断言该 id 收据数为 0(drivers 脚本内断言)。

16. **一次性收养**: 首启后 journal 恰 1 条 birth(legacy)(seq **969**), 语义行 id 不变。

17. **重启幂等**: 二次重启后仍恰 1 条 birth, id 不变。

18. **容量策略**: 容量满忽略新发现(`prior==null && OPPORTUNITIES.size()>=MAX_OPPORTUNITIES`
    return), `OPPORTUNITIES.remove(oldest)` LRU 零残留(源码契约双向断言); 终态移除仍是
    唯一释放路径; reconcile 总量护栏 fail-closed。257-ore 压测按 runbook 未执行(可选)。

19. **stale durable-before-removal**: markOpportunityStale 与 observeVisibleBlock 的
    staled 循环均收据先行(kernel→journal fsync→boolean)成功才移除, 失败保留条目返回
    false; 源码契约 + LIVE-R12-2 journal 序列(962 durable+出表)双证。R1 的 consumed
    先行未触碰、测试全绿。

20. **自动化**: JUnit **385/0** dev + replay(380 基线 + 5 新契约); GameTest **636/0 ×3**
    (dev 两轮 + 干净重放一轮, round1 为最终代码后独立补跑存档); Node **43/0**;
    BridgeCore **78**; Installer **11**; DSH 工具恰 **29**(dsh-plugin 零改动);
    SHA256SUMS **151** 项重生成; 干净 a029fa6 重放 **369 文件逐字节零 diff**
    (01-diff/ 两 manifest + replay-zero-diff.txt)。
    期间披露一台机器侧干扰: aibot-dsh-m0/scripts/test.sh 因本机无 python3 提前退出,
    其 evidence 目录残留 mc2a01 时代旧数字(77/37)——三套件以手动逐套实测为准(78/43/11),
    与 R1.1 交付数字一致。

21. **派发路径审计**: 07-audit/dispatch-path.md——graphRunNext→submit 仍为唯一物理
    派发路径, 本轮零触碰, 契约全绿。

22. **无范围蔓延**: 07-audit/scope-audit.md——production.patch 4 文件, §2 保留清单
    逐项核对, 无 Real Client/Agenda/Scheduler/BotView/第二生命周期库/schema 改动。

23. **冻结裁决**: 三门 LIVE 全 PASS、自动化全达标、零 diff 重放、修正全部留档。
    以本轮生产提交(00-meta/production-final-sha.txt)作为 **MC-2A Graph Core frozen
    baseline 候选**, 连同 `fca017b`(R1)+`e525582`(R1.1)构成完整冻结链, 提交外部审计
    最终裁决; 通过后进入 **MC-2A0.3 Body Backend Seam / FakePlayer mechanics extraction**。
