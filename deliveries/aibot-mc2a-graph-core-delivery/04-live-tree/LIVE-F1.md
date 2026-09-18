# LIVE-F1 — 高树支撑堆叠访问闭包(D-1 修复实机验证)

判定：**PASS**(三连复证)

环境：隔离服 `mc-server-mc1ca`(Stage A jar `ad619df9…`,1768495B,外部模式 Bob)+ 桥 HTTP 直驱。
日志:`04-live-tree/LIVE-server.log`(server-graph-a.log)。

## 场景

石台场地(540-552,67,124-136)+ 8 格自然树(泥柱基+冠层 4 叶)+ 铁斧 + 24 泥土,
`POST /v1/executions/gather minecraft:oak_log count=1`(quota=1 整树事务)。

## 证据(execution 1f47d047,第一棵树 z=130)

```
tree_workset_acquired logs=8 (frozen proof 全树)
tree_support_placed (546,68,130)   <- 支撑 #1
tree_support_removed (546,68,130)  <- 一次访问重路由(非振荡)
tree_support_placed (546,68,130)   <- 重放 #1
tree_support_placed (546,69,130)   <- 支撑 #2 堆叠于 #1 之上(向上推进)
tree_support_removed (546,69,130)  <- 逆序清理(先高处)
tree_support_removed (546,68,130)
tree_workset_complete have=8/1     <- 整树一次事务完成
```

- 全部支撑事件 `execution=1f47d047…`(exact owner),`block=minecraft:dirt`。
- 终态 `completed reason=inventory_family_or_exact_quota_verified:accepted=8`。
- 零残留:`fill … air replace minecraft:oak_log/dirt` 全列 0 命中;背包 oak_log×8。
- 复证:execution d5eb489a(z=134 树)、d1d319c2(z=124 树,含 68/69 两级堆叠)同模式完成。

## 判定依据

旧 D-1 缺陷形态为"同格 placed→removed 每秒振荡 ~60s 不向上推进直至材料耗尽 fail-closed"。
本轮三棵树均呈现"放置→(至多一次重路由)→向上堆叠→逆序清理→整树完成",振荡消除。
