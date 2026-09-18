# LIVE-F2 — 支撑存活期间的 SAFETY 抢占与恢复

判定：**PASS**(安全语义+fail-closed 所有权),附一项已记录的继承性限制(非本轮回归,详见文末)。

环境:同 LIVE-F1。日志:`04-live-tree/LIVE-server.log`。

## A. 外部 pause/resume(位移为零)— 续接完整闭环 PASS

execution 09b10855(树 z=132),`POST /v1/executions/{id}/pause`:

```
tree_support_placed (547,68,132)               <- 支撑 #1 存活
task_paused {why=user_pause:external_pause, stack_depth=1}   <- 持支撑暂停
(3 秒)
task_resumed {stack_depth=0}
tree_support_placed (546,68,132)               <- 恢复后继续堆叠
tree_support_placed (546,69,132)
tree_workset_complete have=8/1                 <- 同 treeId/execution 续接完成
task_completed elapsed_ticks=242
```

零残留(fill glass 探测全 0)。**暂停时支撑账本原地保留、恢复后同事务继续**——与
MC-2A0.2 LIVE-4 语义一致且首次在"支撑存活"条件下验证。

## B. SAFETY 僵尸抢占(战斗位移变体)— 安全语义正确,事务按类型化债务 fail-closed

execution a798a0a2(树 z=128),支撑 #1 放置后 1 秒内 RCON 召唤贴身僵尸:

```
tree_support_placed (546,68,128)
task_paused {why=threat: HOSTILE, stack_depth=1}   <- 支撑存活时被 SAFETY 抢占
combat assigned (origin=SAFETY threat:HOSTILE) / 64t 击杀
task_resumed
task_failed {reason=tree_cleanup_debt:tree_access_target_changed_with_owned_supports}
```

- 安全目标全达成:hp 15/20 存活、无假成功、无静默泄漏、所有权记录完整。
- 终态 fail-closed 且 reason 类型化;现场保留:支撑 dirt 原位 + 顶部 2 根未采 log
  (玻璃标记法探测,事后确认)。
- **失败链条定性**(源码级):READY 时 `resetAccessTransient()` 清空 accessTarget(单目标
  事务完成语义)→ 战斗位移使 bot 离开支撑面 → 恢复后重入访问被
  `accessTarget==null + hasTemporarySupports` 误判为"目标变更"→ 债务。
- **非 Stage A 回归**:旧代码(补丁前)此场景进入 place→TREE_CLEANUP→remove→SURVEY→place
  的 D-1 无限振荡,材料耗尽后同样失败且更糟;访问规划器的重入/回爬分层推进是
  MC-2A0.2 轮已明示的下一轮延期项(见 mc2a02 RESULT §20)。

## 附:取证方法修正记录

RCON `execute if block … run say` 经 RCON 不回显(假阴性);必须用 `run seed`
(承接 R2 轮教训)。本轮 F2 取证以 `fill … replace` 计数法交叉验证。
