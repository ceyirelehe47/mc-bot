# LIVE-R1-2 — 图机会成功具备持久成功收据

**结论：PASS**

## 流程

隔离服 Bob 旁 2 格放铁矿石（齐胸悬空、邻位可站）→ 显式 observe 注册为 ACTIONABLE 机会
`ore_fed47d71dd5c302187c43490fefa9ad1` → `POST /v1/graphs/opportunity?plan_key=r1c-iron-2&ref=<双重编码>`
→ `graph-8f67d2cefef8d11d9bae7732` READY → `POST …/run-next`（经 BridgeKernel.submit 的普通
execution `425d9d00`，operation=mine_opportunity）→ bot 采矿入包。

## 证据

- journal 帧 seq 456 `resource_opportunity_consumed`（fsync 后才返回）：
  `{"x":552,"y":68,"z":129,"world_id":"80980dea-…","resolution":"inventory_gain_proven","block":"minecraft:iron_ore","dimension":"minecraft:overworld","opportunity_id":"ore_fed47d71…"}` —— world/dimension/opportunity_id 精确结构化字段齐全
- 节点终态 `DONE | postcondition_satisfied:durable_inventory_gain_receipt` —— **DONE 理由来自持久收据而非注册表缺席**
- 语义机会已从 registry 移除（重启后快照亦无）
- 背包 raw_iron 增量证明（6→采集后增加）
- 物理执行即普通桥 execution（request_id=graphd-…，无任何绕过 submit 的路径）

见 `journal-resolution-extract.jsonl` 与 `graph-api-responses.json`。
