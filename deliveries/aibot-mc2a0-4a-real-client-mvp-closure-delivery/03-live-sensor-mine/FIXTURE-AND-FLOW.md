# LIVE-B/C/D — 同帧传感器 birth → Graph mine → 同格新 incarnation

## 隔离环境

- 服务器：`D:\code\mc-experiment\mc2a04a-live-server`（一次性世界 seed=-442585930，online-mode=false，端口 25599）
- 启动：`cmd /c start-live.bat`（环境 AIBOT_EXTERNAL_BOT=Bob / BODY_ID=bob / BACKEND=real_client / BRIDGE_PORT=8765 / REAL_CLIENT_PORT=8766 / REQUIRE_OFFLINE_UUID=1）
- 客户端：supervisor（real_client_supervisor.py，循环模式）直调 `java.exe` + DLI dump 的 classpath/main/args（参数数组、无 shell），配置 `mc2a04a-supervisor.json`，opt-in `AIBOT_REAL_CLIENT_AUTO_JOIN={minecraft_server}`
- Bob：offline UUID `faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b`（v3 `OfflinePlayer:Bob`），无任何 Microsoft 凭据
- fixture 前置（RCON，execution 开始前）：`doMobSpawning=false`、`doDaylightCycle=false`、`time set day`、`keepInventory=true`、Bob 抗性 5（免疫环境史莱姆误伤）、平台 `fill -5 -61 -5 11 -61 7 stone`
- 目标矿：`(7,-60,2) minecraft:iron_ore`（setblock，两层 air→ore 替换放置）
- Bob 初始位 `(3.5,-60,2.5)`，工具 `minecraft:iron_pickaxe` slot 0，背包 baseline：raw_iron=0

## LIVE-B：自主朝向 + coherent birth（2026-09-13 14:24）

1. HTTP `POST /v1/executions/goto`（参数 `x=3,y=-60,z=2,face_x=7,face_y=-60,face_z=2`）——goto 的最小 final-facing 扩展，普通客户端视角链 lookAt(矿心) 并等 crosshair 压住该格
2. 服务器同帧验证：权威眼睛位置 + 该 frame 的 look（`getHeadYaw`/pitch）重建 world raycast（OUTLINE/NONE），格级一致 + 距离 + 位置容差 + actual block + ore 分类
3. 14:24:25 `sensor diag birth id=rcore_0bd08c60709b_6a31ddbddf394fe5 pos={7,-60,2} block=iron_ore frameSeq=367 gameSession=661fc58f...`
4. journal `real_client_opportunity_birth` + `resource_opportunity_actionable` 各恰 1 条（35 秒重复心跳后仍为 1——duplicate frame 不重复 birth）
5. 负面探针：新 goto face=(-4,-60,-4) 转离铁矿 → crosshair MISS、diag reject `sensor_null_or_not_present`，birth 总数仍 1
6. `POST /v1/view` 暴露 EvidenceRef：`mc://6d190bc8-4de5-46f0-b724-1eddccd71bcd/minecraft%3Aoverworld/opportunity/rcore_0bd08c60709b_6a31ddbddf394fe5`

## LIVE-C1：birth restart replay（14:29-14:32）

1. RCON `stop` 优雅停服（世界+journal 保存）
2. journal 复核：birth/actionable 帧完整（opportunity_id/block/x,y,z/expected raw_iron/seen_from=(3,-60,2)）
3. 重启服务器 + supervisor 冷启动客户端 → 新游戏 incarnation `78711a2c-...`（≠ 661fc58f）
4. deliberate `GET /v1/observe` → `POST /v1/view` 重新暴露**同一** incarnation `rcore_0bd08c60709b_6a31ddbddf394fe5`（journal replay 恢复，非新建）

## LIVE-C2：Graph mine 全链（14:33-14:35）

1. 新 lease（owner mc2a04a-liveC）
2. `POST /v1/graphs/opportunity?plan_key=mc2a04a-iron-1&ref=<EvidenceRef>` → `graph-119777f514c1b06a26457818` state=READY（node n-mine-1d7467ba0813 operation=mine_opportunity）
3. `POST /v1/graphs/{gid}/run-next`（X-Request-Id）→ execution `e0790458-0529-49f0-a628-35cdb6c30657`（admitting game session 78711a2c）
4. 普通客户端走近（walkTo）、选 slot 0 铁镐、lookAt(矿心)、`attackBlock` + 每 tick `updateBlockBreakingProgress` + swingHand
5. 目标格 iron_ore → air（fill-replace 探针替换 1 格成功证明）；物理掉落 raw iron 被拾取（Slot 1 `minecraft:raw_iron`，铁镐 components damage 0→1）
6. 服务器双证明：exact cell gone AND inventory family 0→1 → 终态 `completed reason=server_authoritative_block_and_inventory_gain_verified:0->1`
7. durable `resource_opportunity_consumed`（payload baseline=0 current=1 resolution=inventory_gain_proven）
8. `GET /v1/graphs/{gid}` → state=**DONE**（node DONE，postcondition 由 durable consumed 满足）

## LIVE-D：同格新 incarnation（14:36）

1. `setblock 7 -60 2 iron_ore` 重放同格矿
2. goto(face=矿) 自主重新对准 → 14:36:31 新 birth `rcore_0bd08c60709b_91c3593e05af43ca`（同 location hash 0bd08c60709b、**不同 incarnation** 91c3593e≠6a31ddbd，frameSeq=516）
3. `POST /v1/view`：新 id 在场、旧 id 不再出现（旧 EvidenceRef 未恢复 actionable）

## 明确未做（禁项遵守）

- 执行开始后无 `/setblock air`、无 `/give`、无 teleport 充当动作或证明
- 客户端 `completed` 仅进度提示；成功仅由服务器 cell+inventory 证明与 durable receipt 决定
