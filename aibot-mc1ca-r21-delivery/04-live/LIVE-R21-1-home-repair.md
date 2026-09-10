# LIVE-R21-1｜HOME repair 真实闭环 —— PASS

环境：隔离服 D:\code\mc-experiment\mc-server-mc1ca（world_r2，world_id 2eadb4ef-61bb-4657-ab6e-94d50141bbd1），
新 jar（R2.1）部署后重启，桥 8765，Bob 领租约驱动；无 teleport/setblock 伪造 repair。

## 场景

- r2home（526 格 baseline，bounds 18,112,18–34,119,34）自然演化了数日：
  - 大量 baseline dirt 格被草蔓延为 grass_block；
  - R2 轮遗留 1 格真 missing（26,112,26，baseline grass_block，被挖空）。
- 这正好同时覆盖 R21-A1（1 missing）与 R21-A5（dirt↔grass）。

## 执行与结果

1. `mc_repair_home {"name":"r2home"}` → execution `7eb023c3`，
   **completed / `home_missing_only_repair_verified`**（5 ticks）。
2. observe 复核：`integrity=1.0 missing=0 wrong=0 repairable=false`。
3. 材料真实消耗：oak_planks 64 → 63（1 格放置）。
4. 等价不覆盖：baseline 为 grass_block 的 33/34 列、以及 baseline 为 dirt 的
   (33,113,24) 等格全部保持原样，草蔓延未被强行回改（wrong=0 而非回写）。
5. R2 对比：R2 轮同世界同结构因 1 格 grass 漂移持续 `home_repair_v1_conflicting_cells:1`
   永久 409 拒绝；R2.1 等价 policy 后预检通过、只补真 missing。
6. 3 missing 变体：追加实测 `setblock 33 113 24 air`（baseline dirt）→ repair → completed，
   **补回的是 baseline 原始 id（dirt）**，dirt 33→32 消耗；grass 邻格不动（见 LIVE-R21-2）。
7. extra 块保持：r2home 内 (26,113,26) 的历史 chest 在全部 repair 轮次后仍为 chest。

## 证据

- 桥 execution：`7eb023c3-05cf-4b37-8f71-941062dcde30`（completed）
- 服务器日志：本目录 server-r21-live.log（task_completed build elapsed_ticks=5）
- 现场 RCON 复核：integrity 1.0 / missing 0 / wrong 0；`execute if block` 逐格探测
