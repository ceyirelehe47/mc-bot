# LIVE-R21-1｜HOME repair 真实闭环（含多格） —— PASS

环境：隔离服 D:\code\mc-experiment\mc-server-mc1ca（world_r2，
world_id 2eadb4ef-61bb-4657-ab6e-94d50141bbd1），R2.1 jar（含独立验收修复），
桥 8765，Bob 领租约驱动。**无 teleport/setblock 伪造 repair**（setblock 仅用于构造 fixture 损伤）。

## 单格场景（首轮）

- r2home baseline 自然演化后 `missing=1 wrong=0`（草蔓延的 dirt→grass 被等价 policy 吸收）。
- `mc_repair_home {"name":"r2home"}` → execution `7eb023c3` →
  **completed / `home_missing_only_repair_verified`**（5 ticks）。
- 复核：integrity 1.0 / missing 0 / wrong 0；oak_planks 64→63（1 格真实消耗）；
  历史 chest 未被删。

## 多格场景（复验轮，3 格）

- fixture：将 baseline 中三格结构块置空（26,113,26 = chest；27,113,26 与 30,113,28 = oak_log）；
  另在保护盒外放 chest 验证 extra 不删。
- 观测复核 `missing=3 wrong=0 repairable=true`，材料补足（oak_planks / oak_log / chest）。
- `mc_repair_home` → execution `71ab1708` →
  **completed / `home_missing_only_repair_verified`**。
- 逐格 RCON 复核：
  - `26 113 26 = chest`（命中）、`27 113 26 = oak_log`（命中）、`30 113 28 = oak_log`（命中）
  - `40 113 40 = chest`（盒外 extra，完好）
  - integrity 回升 **1.0 / missing 0 / wrong 0**
  - 材料真实消耗：chest 4→3、oak_log 16→14
- 期间另实测：材料不足时 typed `missing_material: minecraft:chest`（快速失败，不伪造完成）；
  baseline 格被非等价物块占据时 `home_repair_v1_conflicting_cells:1`（409 原子 fail-closed）。

## 证据

- executions：`7eb023c3`（单格）、`71ab1708`（3 格）、`550d09c2`（conflict 409）
- 原始日志：
  - 单格：`server-r21-live.log`
  - **3 格（17:08:45–17:08:48）**：`server-1708-multicell-repair.log`
    （从服务器归档 `logs/2026-09-10-5.log.gz` 提取；含 3 条 `event=place`
    ——chest@26,113,26 / oak_log@27,113,26 / oak_log@30,113,28
    ——与 `task_completed elapsed_ticks=65`）
  - fail-closed 两条：`missing_material: minecraft:chest`（归档同文件）；
    `home_repair_v1_conflicting_cells:1`（journal execution `550d09c2`）
- 逐格探测：`execute if block <pos> <block>` 全部命中（见上文坐标）
