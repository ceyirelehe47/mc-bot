# G-LIVE-3 — 经既有执行台账分派

判定：**PASS**

## 证据(graph-c4dfbc8a…,execution 89654840)

`POST /v1/graphs/graph-c4df…/run-next`(X-Request-Id 幂等头)→ **202**:

```json
{"execution":{"execution_id":"89654840-052a-4878-b6a3-08cda1ae650e",
  "request_id":"graphd-1bce6f74bf6bd…","operation":"mine_opportunity","state":"accepted"},
 "graph":{"state":"RUNNING","nodes":[{"state":"RUNNING","execution_id":"89654840…"}]}}
```

- 图节点 RUNNING,绑定**普通桥执行 id**(与 `mc_mine_opportunity` 等既有工具同一台账:
  `GET /v1/executions/89654840` 返回标准执行对象,state 流转 accepted→completed)。
- 服务器日志:`task_assigned name=mine_known_resource … origin_reason=external_dsh:89654840…`
  —— 即既有 mine_opportunity 物理执行器,**无第二执行路径**。
- dispatch_request_id 呈 `graphd-<sha>` 稳定身份,作为 submit 的 request 参数获得既有
  请求幂等语义。
- 终态经既有 DSH 事件传输投递(execution 事件,G-LIVE-4/DSH 会话中事件唤醒模型复核)。

源码契约佐证:`TaskGraphBridgeSourceContractTest` 断言 run-next 调
`submit(supplied,d.requestId(),d.operation(),d.arguments())` 且
`graphRunNext` 区间不含 `backend.start(`;BridgeCoreTest 78 项既有传输回归全绿。
