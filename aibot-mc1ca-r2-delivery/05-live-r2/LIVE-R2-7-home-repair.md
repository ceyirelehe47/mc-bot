# LIVE-R2-7｜HOME capture/repair —— 部分 PASS（capture/conflict/extra 达成；missing 恢复 FAIL）

## 达成项
1. mc_capture_home 显式基线：成功 4 次（526/524/526/527 cells），integrity 检出精确（missing/wrong 分列）
2. conflict fail-closed：基线格 (32,112,26)=dirt 被游戏草蔓延机制变成 grass_block → repair 以 `home_repair_v1_conflicting_cells:1` 409 原子中止：progress 0、零放置、材料零消耗（不自动拆非空气错块）✓ 完全符合设计
3. extra 不删：额外放置的 chest (26,113,26) 经历 4 轮 repair/capture 全程完好（never_delete_extra_blocks）
4. 检测链：完好 capture → 破坏 → missing=1 / repairable=true 正确报出（含 3 格场景 missing=3）

## FAIL 项（真实失败，按任务第 6 节记录，未擅改生产语义）
**missing 恢复**：mc_repair_home 进入 BuildTask 后对全部目标（3/3 与 1/1 两种场景、目标距离 2.1~4.4 格、两个站位、材料齐备 oak_planks×32）一律 80-tick 空转后 `build_block_skipped target_timeout` → `structure_incomplete: matched=0/3 skipped=3`。服务器日志 Bob.log：held 全程未切换（materialSlot/equip 从未到达）、path_idle 全程 true、无 startPathTo/无 fail 日志 → 卡点在 BuildTask.build() 的静默 return 路径（ensureObservableWorkPose false 或 resolveBlock null，未最终定位）。**未 teleport/setBlock 伪造完成。**

## 附带发现（记录）
1. wrong 预检对自然方块变化过敏：基线含 dirt 格时草蔓延会持续制造 wrong（两次实证），使 repair 永久 fail-closed——符合 fail-closed 设计但实际可用性受限；环境侧 workaround 是把 bounds 内 dirt 统一为 grass 后重 capture
2. capture 只记录非空气格：受损后重 capture 会把损伤固化为合法基线（missing 永远检不出）——语义正确但操作时序敏感
