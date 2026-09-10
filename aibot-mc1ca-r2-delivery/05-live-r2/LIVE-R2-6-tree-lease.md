# LIVE-R2-6｜自然树完整采伐 lease —— PASS

场景：3 棵 5 节橡树（z=46；z=36 原树被 farm 寻路挖掘合法清除——pathfinder 障碍挖掘，树不受保护，行为合法且如实记录）。

## 判据与证据
1. 自然树识别：nearest_tree 正确列出；gather prospect 选中并启动采伐 ✓
2. 整树连续采伐：RCON 方块快照实证 tree@0 连砍 4 节（y113..116）、tree@-10 连砍 5 节（全树，无残留）——无"砍 1–2 节留浮空干" ✓
3. 配额止浮空节 + lease fail-closed：首轮配额(40)恰在第 4 节满足终止 → 树@0 顶节 y117 残留；严格轮(46)新 gather **不捡浮空节**（砍根后 isNaturalTreeLog 否定 + lease 属于已结束的上轮任务），跨树砍 tree@-10 全树 + 远处 1 根天然 cherry_log——语义自洽实证 ✓
4. 跨树/跨种连续采伐：727 ticks 6 根（5 oak + 1 cherry，跨 48 格）phase=DONE ✓
5. R2-G family contract 正向实机验证：oak_log 配额 46 被 5 oak + 1 cherry 满足，bridge 判 completed 无 family/exact 矛盾 ✓
6. HOME structural log 不可砍：小屋原木墙全程完好（r2home integrity 恒定 missing=1 仅为 R2-7 地板洞）✓
7. NOT_RUN（实机）：SAFETY pause/resume 后同 gather 继续、新任务不继承 lease——由 GameTest `r2treeleasesurvivesselfremovalofrootonlyforownertask` 覆盖（600 全过）

注：DSH 将 family 满足标为"口径缺陷"，按 R2-G 设计（README：log 目标是 logs family 语义）此为预期行为，验收记录如实保留双方观点。
