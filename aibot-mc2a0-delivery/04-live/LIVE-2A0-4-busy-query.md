# LIVE-2A0-4｜busy query（实机 PASS）

## 流程
1. 提交持续普通任务：`executions/gather {"item":"minecraft:oak_log","count":64}`
   → execution 33683857-dedd-4dde-b748-4eb241e4e0b7 → state=running。
   （期间一次脚本异常导致 lease 过期、任务被桥自动 pause——release-pause 护栏的预期行为；
   随后 `resume` 回到 running 再继续验证。）
2. RUNNING 中三类查询全部成功：
   ```text
   view during RUNNING: RUNNING/gather bucket=0 safety=False paused=False
   inspect + inspect_local during RUNNING: OK
   ```
3. 查询后执行所有权不变：
   ```text
   after: same_exec=True state=running active_is_it=True user_paused=False depth=0
   ```
   同一 execution 仍为 active，无 pause/cancel/replacement，无第二个 mutation execution。
4. 收尾：`cancel` → cancelled，无残留。

结论：ordinary task RUNNING 时三类认知查询可用且不干扰执行（VIEW-15/16、READ-2/3）。
附带验证了 view 的 execution 段如实呈现 RUNNING/任务名/进度桶。
