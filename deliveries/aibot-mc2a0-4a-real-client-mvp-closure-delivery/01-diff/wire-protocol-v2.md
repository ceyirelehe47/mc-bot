# Wire protocol v2（real_client 控制传输）

## 变更原因
v1 的 sensor snapshot 没有帧身份与游戏会话绑定:服务器用"observe 时刻"的 player.raycast() 与"最多 500ms 前"的客户端 crosshair 拼接比较,跨时间 pose 导致 LIVE birth 永远失败;且 session epoch 来自控制 TCP,同进程游戏重连可复用旧身份。

## v2 消息增量
- hello/welcome:结构不变,`protocol` 1→2;两侧任一不匹配即 `real_client_protocol_mismatch` 断连(mixed binaries fail closed,已由 RealClientWireSessionTest.protocolV1HelloIsRejectedFailClosed 验证)。
- heartbeat:新增必填 `game_session`(JOIN 生成 UUID)、`game_session_seq`(单调计数)、`frame_seq`(帧内单调);`yaw` 语义修正为 crosshair 真实视线(getHeadYaw,客户端 crosshair 由 fromPolar(pitch,headYaw) 计算)。
- execution(客户端状态):新增必填 `game_session`/`game_session_seq`,与 heartbeat 同一 incarnation 状态机。
- command(goto):新增可选 `face_x/face_y/face_z`(到达后 final-facing,自主朝向);其余操作命令不变。

## 服务端状态机(RealClientServerTransport.Session)
- 首条消息固定 (gameSession,gameSessionSeq);
- 同 seq 且 epoch 漂移 → 断连;seq 回退(迟到旧 incarnation)→ 断连;
- seq 前进(游戏重连)→ 接受并重置 sensor/executions/lastFrameSeq;
- frame_seq ≤ lastFrameSeq(重复/倒序)→ 丢弃,永不刷新快照。
