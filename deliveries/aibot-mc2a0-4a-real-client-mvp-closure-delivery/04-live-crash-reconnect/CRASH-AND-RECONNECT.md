# LIVE-E/F — RUNNING 强杀进程树围栏 + supervisor 冷重启零重放

## LIVE-E：RUNNING mutation 中强杀实际客户端进程树（14:40-14:41）

前置：
- 游戏 incarnation `458d26a0-...`，supervisor state 指向实际客户端 JVM PID（见 liveE-pre-kill.json）
- 通道清空后提交长距 goto：`POST /v1/executions/goto {x:3,y:-60,z:56}` → execution `2b09837f-9b07-43f2-9886-20eb049deb19`
- kill 前 0.4s 采样：state=**running**、progress≈0.23（真实位移进行中，start→target 25.6 格）

强杀：
- `taskkill /PID <实际客户端 PID> /T /F`（整棵进程树；exit code 0）
- 未发送任何 cancel/pause/release；非 /kick、非停服、非死亡

围栏时间线（liveE-fence-timeline.json，0.7s 内完成）：
- t=0.03：ready=true、control_active=true、ex=running（服务器尚未感知）
- **t=0.73 起**：`body_ready=false`、`needs_reconcile=true`、`control_active=false`、
  ex=`outcome_unknown`、reason=**`body_session_changed`**，持续稳定

附加取证（liveE-fence-extras.json）：
- 旧 lease renew → 409 `control_lease_invalid`；旧 lease submit → 409 `control_lease_invalid`
- view → 503 `body_not_ready_or_server_tick_stale`（cached cognitive view 已失效，重建前不可用）
- inspect-local（带死 lease）→ 503（read plane 已失效）
- 事件流 seq=92：`body_session_changed {reason: physical_body_unavailable, previous: {body_session_epoch: 458d26a0...}}`——urgent 事件经事件通道送达（DSH/event client 同通道）
- journal 落盘 `execution ... state=outcome_unknown reason=body_session_changed progress=0.3131`（durable receipt）

## LIVE-F：supervisor 冷重启、新游戏 incarnation、零重放（14:41-14:43）

1. supervisor（循环模式）检测进程退出 → 退避后拉起新客户端（新 PID，见 supervisor-state 序列）
2. Bob 重新 JOIN → 新游戏 incarnation `56284669-...`（≠ 458d26a0；同 offline UUID 但 epoch 必换）
3. **无命令观察窗（12 秒 × 2s 采样）**：位置恒 `(3.59,-60,34.80)` 不动、`active_execution` 恒 null——旧 goto 零 command 重放、零移动（liveF-no-replay-window.json）
4. deliberate `GET /v1/observe` → 新 lease（owner mc2a04a-liveF）
5. 新工作链全部成功：
   - `say` → completed `client_chat_packet_sent`
   - 短 `goto` → completed `server_authoritative_arrival_within_2_5_blocks`
   - mine smoke（对 LIVE-D 的 active incarnation 直调 `mine_opportunity`）→ execution `2ee07489-...` completed `server_authoritative_block_and_inventory_gain_verified:1->2`（raw_iron 1→2，铁镐 damage 1→2，同格再次物理破坏+拾取）
6. 旧 execution 2b09837f 保持 outcome_unknown（未恢复、未重放）
