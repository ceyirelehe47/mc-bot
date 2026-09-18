# G-LIVE-1 — 显式计划,零物理工作

判定：**PASS**

环境:隔离服(Stage B jar `13250a90…`)+ 桥 HTTP 直驱(等价于 DSH 工具的传输层)。
日志:`05-live-graph/server-graph-b.log`。

## 流程

1. RCON `setblock 550 68 132 minecraft:iron_ore` 制造真实机会;`GET /v1/observe` 感知
   注册(`resource_opportunities` 出现 `ore_1d40b871…`,ACTIONABLE)。
2. `POST /v1/graphs/opportunity?plan_key=glive-iron-1&ref=<当前视图 ref>` → **201**。

## 证据

```json
{"graph_id":"graph-c4dfbc8a938fdfd4ff871e95","state":"READY",
 "nodes":[{"node_id":"n-mine-1aeefa997540","state":"READY","operation":"mine_opportunity",
   "subject_ref":{"object_id":"ore_1d40b871…","world_id":"80980dea…","dimension_id":"minecraft:overworld"},
   "postcondition":"OPPORTUNITY_RESOLVED",
   "resource_claim":"opportunity|80980dea…|minecraft:overworld|ore_1d40b871…"}]}
```

- 图持久 READY:`world_play/aibot/task-graphs-bob.bin` 创建。
- 唯一机会 SpatialRef:**仅 world_id+dimension_id+object_id,无 x/y/z/seen_from 拷贝**(TG-REF)。
- resource_claim 可见(TG-13 声明建立)。
- 零 Minecraft 变异:`GET /v1/status active_execution=null`;矿石方块原位(seed 回显)。
- 无物理执行创建:status 无活动执行、无 journal 执行记录。
- 计划要求当前视图证据:传不在当前视图的 ref → 404 `evidence_ref_not_in_current_view`;
  非 opportunity 类型 ref → 400 `graph_plan_requires_opportunity_ref`(负路径验证)。
