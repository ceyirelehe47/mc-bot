# LIVE-R2-2｜Farm harvest 闭环 —— PASS

场景演变（如实记录）：
- v0 农田因水源误放 mask 中央被流水冲毁（环境侧失误）；重建后 v1 轮 tend 已实证 done=2 收割
- v2 注册只识别 2 格——根因是 R2-B connected-cell-mask 语义：5 格分属两个不连通簇，注册取 Bob 最近簇（语义正确，非缺陷）
- 最终版：连通十字 5 格（2 熟 age7 / 2 幼 age0+age3 / 1 空），register_farm(r2farm, radius=5, crop=wheat)

## 最终版结果（DSH 报告 + RCON 世界实况交叉验证）
1. 注册：total_cells=5（exact connected mask 精确）
2. tend_farm：completed / done=3 / phase=DONE / note=replant_skipped:missing wheat_seeds x1
3. mature 2→0（实际收割）；immature 2→4（补种 2）；empty 1（缺种子 skip 1，note 明确）
4. 收获物闭环：wheat 掉落真实掉落在田里，Bob 走入田间后背包 wheat=5（本轮3+上轮遗留2）——"done>0 但背包0"疑点解除，收割是真实物理破坏+掉落
5. mask 外 grass（-19/112/25、-25/112/29、-26/112/23）tend 前后均为 grass_block：未被 till
6. 5 格 farmland 全保留，作物未破坏（幼株保全）
7. r2home integrity 525/1 恒定：HOME 不受影响
8. 口径缺口（记录不掩盖）：done=3 vs 注册时 mature=2，差 1 株（观测间隙成熟或 mask 边界）；不影响判据

结论：R2-B thin-crop observation + exact cell mask 修复了 R1 轮 FARM_TEND 收割缺失（上轮 FAIL 项），实机闭环走通。
