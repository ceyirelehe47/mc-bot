# LIVE-R1-1 — 真实敌对 Safety 位移下的树支撑重入

**结论：PASS**（最终 jar `79fa0bcb…` 复验 + 确定性 GameTest 回归双轨证明）

## 装置

隔离服 `mc-server-mc1ca`（world_play, Bob, 桥 8765）。13×13 石平台（y=66 地面）上人造 8 木高橡树
（干 530,67..74,121，冠 y=75），高位 4 格泥台完成 8 原木全量 acquire 后拆台送回地面。
`gather oak_log×1` 经桥提交；首个 TREE_ACCESS 支撑落下后在 bot 支撑列旁召唤真实僵尸
（近战可及高度）制造真实击退；位移证据出现后立即清除僵尸（其使命已完成，防止测试装置
层面的 emergency_entomb 升级干扰）。

## 关键证据链（server-r1c-i.log, execution 82707092，教科书完整链）

1. `tree_workset_acquired logs=8`（同一 treeId 全程不变）
2. 支撑 `(530,67)`,`(530,68)` 相继 PLACED —— 2 根存活支撑栈
3. `threat_detected HOSTILE → task_paused(threat)` —— gather 被 SAFETY 抢占
4. `fake_player_step (530,69)→(531,68) path_drop_down` —— **真实击退位移离栈**
5. combat 终局后 `task_resumed` —— 同一任务栈恢复
6. **`tree_support_reentered supports=2`** —— 重入：两根存活收据逐一核验
   （owner_execution + TREE_ACCESS + 精确位置 + 精确 blockId）
7. 邻柱重放爬升：`(531,67)`,`(531,68)` 两根新自有收据；`tree_access_support_reentry` 有界步/跳
8. 末根高位原木 `(530,74)` resolved —— 8/8 全收
9. 反向清理在两柱间交替完成：**11 placed / 11 removed，零脚手架**
   （残留扫描仅树基泥土 `(530,66,121)`，属地面本身）

## 最终 jar 复验（server-r1c-o.log）

真实位移 + 完整收割 + 零残留 ×1 轮（另有多轮位移后经"落自有顶续接/低位直收+有界清理"完成，
同为合法恢复路径）；正式重入分支在最终系列 jar 中亦实际触发（`tree_support_reentered supports=2`，
server-r1c-n.log execution 3a913dfc）。

## 判定项

| 要求 | 结果 |
|---|---|
| gather 被 SAFETY 暂停 | ✓ task_paused why=threat: HOSTILE |
| 同 execution/workset/owner 存活 | ✓ 全程同一 execution 与 treeId |
| 经既有自有支撑链返回 | ✓ reentered 事件+逐收据核验 |
| 无 target_changed_with_owned_supports | ✓ 全部运行零出现 |
| 无 pose_lost_with_owned_supports | ✓ 零出现 |
| 高位已提交原木完成 | ✓ 8/8（含位移后末根） |
| 支撑反向清理 | ✓ placed==removed |
| 零自有脚手架 | ✓ RCON 逐格扫描 |
| 未触碰外来支撑 | ✓ 全部 receipt 均本 execution |

## 已知边界（诚实记录）

- 击退把 bot 落点留在自有支撑顶时走"续接"子路径（无 reentered 事件）——补丁设计的合法子情形。
- 僵尸持续贴脸会触发 SAFETY emergency_entomb；自埋泥土若改写支撑格会得到
  `tree_access_reentry_support_conflict` 类型化债务（TREE-R1-4 fail-closed，实测一次，
  server-r1c-g.log 11:25:57）——属正确保守行为而非缺陷。
