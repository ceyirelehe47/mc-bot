# LIVE-2A01F-4｜automatic remote-read counter = 0（真实隔离服）

环境：隔离服 `mc-server-mc1ca`，world `world_play`，Bob（外部身体，owner=skyline_cc），
MC-2A0.1F jar `f4b2df81…`（PRODUCTION_SHA 13a490c 构建，replay 0-diff 同源）。
生产 `ExternalBodyRuntime` 以外部模式正常启动（每 server tick 驱动
`BridgeKernel.tick → observeJson → refreshCaches` + 周期 cognitiveSnapshot）。
时间窗：server tick 4271..4482（log `mc2a01f-live-server.log`，本地窗口 01:41–01:5x）。

## 前置状态

- 已注册结构 `live2a01`（baseline 4 cells，含 MC-2A0.1 会话留下的 1 处真实破坏
  (572,64,160)=air）与 `home`（旧结构）均由 registry 持久化恢复。
- Bob 出生在 (539,69,163)，距 live2a01 bounds ~33 格，**远超 VERIFY_RADIUS=16 验证包络**。
- 服务器重启使 StructureKnowledge 进程内存缓存清空（设计语义，重启即丢）。

## 阶段 1：远端自动窗口（>200 server ticks）

在不下任何操作命令的情况下，让生产 kernel 自然 tick 211 个 server tick，
期间连续请求 35 次 `POST /v1/view`（mc_view 的同一后端出口）+ `GET /v1/observe`
（自动语义缓存的对外出口）。原始输出见 `mc2a01f-live4-out.txt`。

断言全部 PASS：

```text
PASS semantic schema is bounded variant          → mc_spatial_semantics_v2_bounded
PASS no structure card in automatic observe carries integrity fields
     home/live2a01 卡字段= bounds,id,inside,kind,protected,snapshot_cells
     （无 integrity_* / repairable —— 自动路径的结构卡根本没有 current integrity）
PASS durable identity visible remotely           → live2a01 卡在 view 中,baseline_cells=4
PASS remote knowledge is NOT VERIFIED_LIVE       → UNKNOWN
PASS remote freshness is NOT LIVE                → UNKNOWN
PASS window covered >=200 server ticks           → delta=211,view_calls=35
```

view 卡实测：

```json
{"object_id":"live2a01","role":"HOME","knowledge":"UNKNOWN","freshness":"UNKNOWN",
 "summary":{"baseline_cells":4,
 "current_integrity":{"knowledge":"UNKNOWN","reason":"not_currently_verifiable"}}}
```

raw read = 0 的证明链（counter 在生产 JVM 内无对外出口，按 MC-2A0.1 同口径组合取证）：

1. 唯一递增 `INTEGRITY_RAW_READ_SCANS` 的代码点是 omniscient `observe()` 链
   （`structureJson → integrity → world.getBlockState`）；
2. 本 LIVE 实测自动缓存出口 `semantic_world.schema=mc_spatial_semantics_v2_bounded`
   且结构卡零 integrity 字段 = 自动路径确实走了 `observeBounded` 入口；
3. 源码级不可达：JUnit `automaticObservationUsesBoundedSemanticSnapshot` 断言
   MinecraftBodyBackend 源码不含 `SemanticWorldRegistry.observe(bot)` 调用；
4. 行为级直证：GameTest `mc2a01fAutomaticViewNeverTriggersRemoteStructureIntegrityRead`
   在真实 server 线程直接读 counter，45+ tick 自动驱动后增量恒为 0。

## 阶段 2：回包络合法 LIVE 恢复

`tp Bob 572 63 157`（回到验证包络内）后第一次 view：

```json
{"object_id":"live2a01","knowledge":"VERIFIED_LIVE",
 "summary":{"baseline_cells":4,
 "current_integrity":{"expected":4,"matched":3,"missing":1,"wrong":0}}}
```

合法 proof-before-read 路径重新获得 LIVE，missing=1 正是远端期间一直被隐藏的
那处真实破坏——证明远端的 UNKNOWN 不是"读取后隐藏"，而是"没有读取"。

## 结论：PASS
