# 05-regression — FakePlayer/回归与真实控制端口状态

## FakePlayer 模式 smoke（LIVE-G，2026-09-13 14:53-14:58）

隔离服务器 `mc2a04a-fake-server`（一次性世界、端口 25601、RCON 25602）：
环境 `AIBOT_EXTERNAL_BACKEND=server_fake_player`、`AIBOT_BRIDGE_PORT=8767`、同 token。

1. **real 控制端口 8766 不监听**：`netstat -an | grep :8766` 无结果（fake 模式下 RealClientServerTransport 从未启动；对照 8767 LISTENING）。证据：fake-server.log 启动行 + 本目录 netstat 输出。
2. Bob 由 runtime.json 持久化恢复（AIPlayerEntity fake player，`/aibot spawn` 报"名称已存在"证明已在场）。
3. `GET /v1/status`：`backend_kind=server_fake_player`（与 real 模式明确不同）、`body_ready=true`。
4. deliberate observe + lease。
5. `say` → completed `sent_to_aibot_panel_and_global_chat`。
6. `goto(allow_terrain_changes=true)` → completed `arrival_within_3_blocks_verified`（fake 后端语义：需显式允许挖路；与 real 后端的 `real_client_goto_mvp_disallows_terrain_changes` 互为镜像，二者均无 fallback）。

## real 模式能力门（LIVE-A，14:43）

- **duplicate real-client authority 拒绝**：第二个 socket 以同 token/body/player 发 v2 hello，服务器直接关闭连接（`server closed the connection`；服务器日志 `real_client_authority_already_connected`）。liveA-duplicate-authority-rejected.json
- **unsupported operation typed 409**：`gather` → `409 operation_not_supported_by_backend:gather`；`active_execution=null`，无 FakePlayer 接管、无 Task assignment。liveA-gather-typed-409.json

## Graph/Tree 冻结回归

- 7 个冻结文件（TaskGraphStore/BridgeKernel/ServerFakePlayerExecutionDriver/PhysicalExecutionDriver/BodyBackend/BridgeHttpServer/BridgeJournal）与基线 34fb49f **byte-identical**（06-audit/frozen-hashes.txt）。
- graphRunNext 派发块无 `real_client`/`server_fake_player` 分支（06-audit/scope-audit.txt）。
- 全部 643 GameTest（含全部冻结 Graph/Tree/认知语义测试）三轮 0 失败。
