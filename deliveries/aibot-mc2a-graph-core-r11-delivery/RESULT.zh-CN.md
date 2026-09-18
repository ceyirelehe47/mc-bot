# MC-2A Graph Core R1.1 Final Freeze — 交付结果(RESULT.zh-CN)

任务包:`mc-bot-mc2a-graph-core-r11-final-freeze-taskpack-20260912.zip`(SSH /root/download 取,SHA256SUMS 全部一致)。
结论:**三条 LIVE 硬门全 PASS,全量自动化达标,建议将本轮生产提交冻结为 MC-2A Graph Core 基线。**

## 20 问

1. **精确 SHA**:
   - 分支 `experiment/mc2a-graph-core-r11-final-freeze`,自 `experiment/mc2a-graph-core-r1-closure@079e16f187f8b245f4ed8fe3f20eab6bad0e1550` 新建;
   - 生产源基 `fca017b7266ac09d0de5b39f82ee60b6204e2d0e`(R1 生产提交,未 squash/重写);
   - 本轮生产提交:`production-final-sha.txt`;证据提交:`evidence-final-sha.txt`。

2. **补丁是否原样应用**:语义上原样; mechanically 用 `git apply --recount -C1` 应用。任务包 hunk 用假行号(头声明 `-1,7` 等)且窄上下文,默认匹配在极端偏移下失败;`-C1` 只减少匹配所需上下文行数,增删行与补丁逐字符一致(应用前已逐字节核对目标块唯一性)。无任何语义修正。此外两处**装置级最小修正**(任务书 §4 允许):
   a. `apply_to_aibot.py` 的 fabric.mod.json 锚点串登记 `MC2A03OpportunityIncarnationGameTests`(否则新 GameTest 静默不跑);
   b. 新 GameTest 末尾补清场(markStale+清方块;首轮曾因遗留活化身污染共享注册表,致后跑的 mc1caR2 批 6 测试连锁失败,已修复并三轮全绿)。

3. **新机会 id 格式与不抖动**:`ore_` + 12 hex 定位前缀(UUIDv3(worldId+dim+pos+block) 前 12)+ `_` + 16 hex 随机化身段(UUIDv4 前 16),全小写 33 字符。活动化身按"维度+精确坐标+方块"在活动注册表内精确匹配复用 id(findActiveOpportunityAt),所以重复感知 tick、语义快照持久化/重启恢复都不重铸 —— 实测 LIVE-R11-2 化身跨两轮派发 id 不变。

4. **遗留持久 id**:作为"遗留化身"原样有效,启动不重写(OPP-INC-3)。实机 journal 同时存留 R1 旧格式收据(seq 423/468,36 字符 id)与新格式收据,互不干扰。

5. **LIVE A/B id(同格 (560,68,129) 同方块 iron_ore)**:
   - A = `ore_3467bfcc851f_8ef944cd84a44b77`
   - 最终 B = `ore_3467bfcc851f_858f6b3b15f24d89`(同定位前缀 `3467bfcc851f`,化身后缀不同;中间还有一轮 `…_0b063e1d160f4932`)。

6. **A 的旧收据仍在但不影响 B**:journal seq **783** `resource_opportunity_stale`(reason `externally_consumed_cell_replaced_with:minecraft:stone`)历经 6 次重启仍在(`journal-receipt-A.txt`);B 的图 plan 返回 **READY**(非 STALE/DONE),graph-59da783ebe76fd8fece7a7c2。

7. **重启对账不删 B**:会话 F 重启后 `B_SURVIVES_RESTART: OK`(注册表仍含 858f…),`A_NOT_RESURRECTED: OK`。

8. **B 的图不被 A 证据终态化**:重启后 `SUSPENDED / execution_outcome_unknown_no_replay`,非 DONE/STALE。

9. **执行期同化身 stale → STALE**:LIVE-R11-2,graph-973d0b7c12c54b8ce32b7716 终态 **STALE**,node reason 前缀 **`execution_failed_terminal_unsatisfied:`**,非 FAILED/DONE。

10. **决策所用结构化收据字段**:journal seq **853** — `kind=resource_opportunity_stale`、`execution_id=05940fdc-0710-44f7-9e72-3a15d985e3f2`(与 STALE 节点一一对应)、`opportunity_id=ore_ab3babe1251c_ac15e54bac7e4fc3`、`world_id=80980dea-…`、`dimension=minecraft:overworld`、payload{x=560,y=68,z=131,block=iron_ore,reason=externally_consumed_or_stale}。

11. **普通失败仍 FAILED**:5 张真实对照图(r11-incarnation-b/c/d/e、r11-stale-exec 第 1 次)全部 `FAILED / execution_failed`(`ordinary-failure-contrast.txt`);JUnit 同分支覆盖。

12. **截断用合法依赖 DAG**:节点 `a`、`aX`、`z`(dependency=`aX`),`z` 为最后节点,`aX` 为文件最后字段 —— 由任务包 `MakeTruncatedDag.java` 以生产类直写生产路径。

13. **合法文件尾字节 `aX`**:helper 自校验通过,stdout `valid_bytes=1011 / final_valid_dependency=aX`,合法 sha `6d413189…`。

14. **只移除最后字节 `X`**:截断后 1010 字节,`final_truncated_prefix=a`,畸形 sha `f73f4f3c…`。

15. **fail-closed 启动错误**:`java.lang.IllegalStateException: external_bridge_start_failed_closed`,Caused by `BridgeFault: task_graph_store_invalid`(TaskGraphStore.load:392);畸形文件未被静默改写;备份恢复后正常启动(桥 8765 绑定)。

16. **自动化计数**:
    - JUnit **380**/0(dev)× **380**/0(replay) = 基线 378 + 2 新测试;
    - GameTest **636**/0 × 3(round1/round2/replay)= 基线 635 + 1 新测试;
    - Node **43**/0;BridgeCore **78**;Installer **11**;重放 **460 文件零 diff**(排除 .git/build/run/.gradle,与 R1 的 436 差异仅为本清单脚本的收录口径,两清单同脚本同口径比较)。

17. **DSH 工具数**:恰 **29**(12 显式 mc_* + 14 operation 循环 + pause/resume/cancel),dsh-plugin 零改动。

18. **派发路径审计**:`graphRunNext → BridgeKernel.submit` 仍是唯一物理派发路径;本轮全部图回调均带 `dispatch_request_id=graphd-…`;无 backend.start 直调;源码契约测试绿(详见 `07-audit/dispatch-path.md`)。

19. **无范围蔓延**:无 Agenda/后台 Scheduler/自治 producer/Real Client/BotView/坐标墓碑代;九状态模型、收据权威、启动对账顺序、树安全重入实现与 R1 LIVE 行为全部保持(详见 `07-audit/scope-audit.md`)。

20. **冻结建议**:**建议**将 8df1ca3 + R1 闭包(fca017b)+ 本轮 R1.1 生产提交冻结为 **MC-2A Graph Core frozen baseline**,允许进入 `MC-2A0.3 Body Backend Seam / FakePlayer mechanics extraction`。

## 诚实记录

- LIVE-R11-1 装置迭代 5 次(详见 `04-live-incarnation/LIVE-R11-1.md` 失败迭代表):脚齐高矿无可站工位、无镐机会 BLOCKED 秒拒、悬空矿无**同层**站面(adjacentStandPos 只查同层)、part-B 脚本硬编码错格。全部为装置/地形问题,非生产缺陷;失败轮的图与收据按诚实历史保留。
- 残留状态:隔离服图存储含 R11 门图历史(r11-* 共 7 张:5 FAILED 对照+1 SUSPENDED+1 STALE);世界矿石夹具已清场(最后化身已用 stone 终结后清 air);journal 全程未手工写入。
- journal 文件运行期被服务器独占锁,收据提取统一在停服后完成。
