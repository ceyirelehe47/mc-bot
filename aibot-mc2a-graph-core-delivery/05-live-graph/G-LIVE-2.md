# G-LIVE-2 — 生产者幂等与声明冲突

判定：**PASS**

## 证据(接 G-LIVE-1 同一机会 ore_1d40b871)

1. **同 ref + 同 plan_key 重放**:
   `POST /v1/graphs/opportunity?plan_key=glive-iron-1&ref=<same>` →
   返回**同一 graph_id `graph-c4dfbc8a938fdfd4ff871e95`**,state READY(TG-14 幂等)。
2. **同机会 + 不同 plan_key**:
   `POST …?plan_key=glive-iron-2&ref=<same>` →
   **409 `graph_resource_claim_conflict:graph-c4dfbc8a938fdfd4ff871e95`**。
3. `GET /v1/graphs` → count=1,仅原 graph —— **无第二个存活图**(TG-13)。

## DSH 实机会话交叉验证

真实模型会话(见 `dsh-session-graph-extract.jsonl`)中,模型对同一机会先
plan(glive-dsh-1)→run(FAILED,坏矿场景)→cancel→再 plan(glive-dsh-2)成功,
序列同样遵循"声明释放后才可重规划"的约束(cancel 前的竞争计划会被 409 拒绝)。
