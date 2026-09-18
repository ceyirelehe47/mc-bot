# Gate A — 同进程物理会话替换集成证明（MC2A03ASessionBoundaryGameTests）

权威测试：`aibot-overlay/src/gametest/java/io/github/zoyluo/aibot/gametest/MC2A03ASessionBoundaryGameTests.java`
（生产提交 74bb4d8；batchId=`mc2a03a_session_boundary`，独立 bot 名 `Mc2a03aBot`）

三跑记录（03-gametest/TEST-*.xml，各 637 testcase / 0 failure，本测试 0.107s 完成）：
round1（dev 树）/ round2（dev 树）/ replay（干净重放树）。

## 与 runbook Gate A 十二项要求逐条对照

| # | runbook 要求 | 测试内断言（源码行号见生产提交） | 服务器日志证据（mc2a03a-log-excerpt-round1.log） |
|---|---|---|---|
| 1 | 真实 AIPlayerEntity 经 MinecraftBodyBackend 绑定 | `backend_kind=server_fake_player`、`body_session_epoch` 非空 | `Mc2a03aBot ... logged in with entity id 1517`、`LIFECYCLE bot_spawned` |
| 2 | 真实 goto 执行处于 running | `state=running`（kernel.tick 后） | `TASK task_assigned {name=move, origin=LLM_TOOL, origin_reason=external_dsh:3d3eba5f-...}` |
| 3 | despawn/spawn 同进程替换同名假人 | 直调 `AIPlayerManager.INSTANCE.despawn/spawn`（无重启、无生产管理命令） | `TASK task_cancelled {reason=cancelled:bot_despawn}` → `Mc2a03aBot left` → `entity id 1518` 重登 |
| 4 | 逻辑 body id 固定 | 替换前后 `body_id=mc2a03a-body` 相等断言 | — |
| 5 | 物理会话 epoch 轮换 | `firstSession != secondSession` 且均非空断言 | 实体 id 1517→1518 即物理载体更替的外部可见面 |
| 6 | 执行终态 `outcome_unknown / body_session_changed` | `state=outcome_unknown` + `reason=body_session_changed` 双断言（reason 串错即失败） | — |
| 7 | 旧租约吊销 | `control_active=false` | — |
| 8 | `needs_reconcile=true` | 断言 | — |
| 9 | 旧会话排队 local 查询异常完成 | 提交 `submitLocalQuery(4,"summary")` 后替换，断言 `isCompletedExceptionally()`（且入队时未 done） | — |
| 10 | 替换身体无自动重放 Task | `TaskManager.INSTANCE.getActive(replacement).isEmpty()` | despawn 行只出现一次 move 取消，1518 登录后无任何新 task_assigned 直到 deliberate say |
| 11 | journal 恰 1 条 `body_session_changed` + 2 条 `body_binding` | `journal.replay()` 按 kind 计数断言（!=1/!=2 即失败） | — |
| 12 | observe+新租约允许 deliberate 新会话 say | `needs_reconcile` 清除断言 + 新 claim + say `completed` 且 `body_session_epoch=secondSession` | `<Mc2a03aBot> mc2a03a-session-reconciled`（真实全服聊天广播） |

## 补充说明

- 会话围栏的读平面维度（RP-2/RP-3/RP-4：排队 inspect/local 查询取消、永不物化到替换
  身体、首个重建视图属于新会话）由 BridgeCoreTest 合成装置在 94 项检查中直接证明
  （02-build/bridgecore-direct.log 的 read-plane 三组断言），GameTest 覆盖第 9 项的
  真实 Minecraft 路径。
- epoch/instance 具体值说明：GameTest 使用临时 journal（finally 中删除），epoch 值为运行期
  生成字符串，测试断言"非空且轮换"，具体值不入证据；物理载体更替以实体 id 1517→1518 的
  日志为准。
- 未新增任何生产 despawn 端点；`ExternalBodyAccess.permitsLegacyOperation` 未被触碰
  （07-audit/gate-d-audit.json：MinecraftBodyBackend 与全部接缝文件零差异）。
