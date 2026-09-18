# LIVE-R2-3｜Dimension 同名隔离 + restart —— PASS

## 判据与证据
1. 两边同名同时存在：Overworld r2home (18,112,18→34,119,34) + Nether r2home (-8,89,-8→8,96,8)，同一 world_id 2eadb4ef-61bb-4657-ab6e-94d50141bbd1
2. 切维度后只查询当前维对象：双向互验——Nether 视图只有 Nether 条目（resource_opportunities_other_dimensions:4 佐证跨维度计数），Overworld 视图只列 r2home+digwall
3. restart 后两边仍存在：完整 stop→start（runtime_epoch 5ae4d694→94f50c1a），两侧 bounds/protected/inside/snapshot_cells 完全一致；r2farm 5 格 mask、4 条 BLOCKED opportunity、背包（wheat 5 等）全部延续
4. Nether 注册不能覆盖 Overworld：注册顺序 Overworld→Nether→回 Overworld 复核，Overworld bounds 原样（18..34/112..119/18..34）

## 证据文件
- registry v2 持久化文件（restart 停机窗口读取）：world_r2/aibot/external-semantics-bob.json——version:2、world_id、3 structures（含两个维度同名 r2home）、farm 5-cell mask、4 opportunities
- DSH 会话报告（r2 步骤B/C/D + Nether 终验）
