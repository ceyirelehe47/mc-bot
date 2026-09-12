# LIVE-R11-1 — 同格同方块化身隔离(含重启对账)

**结论: PASS**(装置迭代 5 次,最终链路完整;前 4 次失败均为装置/地形问题,非生产缺陷,详见"失败迭代记录")。

## 装置

隔离服 `D:\code\mc-experiment\mc-server-mc1ca`(R1.1 jar `3fc60779…`,mods 已部署),
世界 `world_play`(world_id `80980dea-4a25-46fa-ab97-eeefcc8b4b39`),身体 Bob,
桥 8765。驱动脚本(归档于本目录 `drivers/`):`r11_live1_incarnation.py`(首轮)、
`r11_live1e_incarnation.py`(最终装置)、`r11_live1_restart.py`(重启断言)。

## 关键值(最终成功链,均在真实运行时/journal/启动对账上取得)

| 项 | 值 |
|---|---|
| 目标格 | `(560, 68, 129)` minecraft:overworld,iron_ore |
| incarnation_A | `ore_3467bfcc851f_8ef944cd84a44b77` |
| A 的终结 | 格替换为 stone → 真实 `resource_opportunity_stale` 收据(journal seq **783**,reason `externally_consumed_cell_replaced_with:minecraft:stone`),A 移出注册表 |
| 同格后续化身 | run1 `ore_3467bfcc851f_0b063e1d160f4932`(seq 791 收据)、最终 `ore_3467bfcc851f_858f6b3b15f24d89` |
| id 格式 | `ore_` + 12 hex 定位前缀 + `_` + 16 hex 随机化身段(共 33 字符,全小写) |

三个化身同定位前缀 `3467bfcc851f`、互不相同的化身段 —— **A != B 成立**(多轮重复)。

## 硬断言逐条

1. **A != B**:同一格同方块重铸新 id,三轮(r11 首轮 B/0b06…、悬空矿 C/f596…、最终 858f…)均成立。
2. **注册表含 B 不含 A**:语义快照 `world_play/aibot/external-semantics-bob.json` 重启前后
   均只有当前化身;A 从未复活(`A_NOT_RESURRECTED: OK`)。
3. **journal 仍含 A 的终态收据**:`journal-receipt-A.txt`(seq 783),历经 6 次服务器重启仍在。
4. **B 的图不被 A 的收据解析**:`plan(r11-incarnation-f)` 返回 **READY**(非 STALE/DONE),
   graph_id `graph-59da783ebe76fd8fece7a7c2`。
5. **重启后 startup 对账不删 B**:`B_SURVIVES_RESTART: OK`(会话 F,启动对账后注册表仍含 858f…)。
6. **重启后 B 的图保持 SUSPENDED**:`execution_outcome_unknown_no_replay`,非 DONE/STALE
   —— A 的旧收据(同格旧化身)没有把后来者的图终态化。

## 执行轨迹(最终链)

```
tp Bob 558 → setblock 560 68 129 iron_ore → 观察 → 858f… (ACTIONABLE, 镐已在包)
tp Bob 530(30 格外) → plan r11-incarnation-f → READY
run-next → RUNNING(走 seenFrom 接近分支) → 2s 后 RCON stop(优雅停服)
重启(会话 F) → 桥就绪 → B 存活 / A 未复活 / 图 SUSPENDED
收尾: setblock stone → 观察 → 终态化 858f… → setblock air(世界清场)
```

## 失败迭代记录(诚实披露)

| 轮 | 现象 | 根因(均为装置问题) |
|---|---|---|
| 1 | 图 FAILED `execution_failed` | 矿在脚齐高格(560,68,129 顶面暴露),到点 `no_reachable_work_pose`;且 Bob 无镐,机会 BLOCKED,派发 62ms 内拒绝 |
| 2 | 同上 | 矿放 (560,69,129) 悬空齐胸高——**无同层站面**:`HarvestCore.adjacentStandPos` 只接受与矿同层的可站邻格 |
| 3 | 同上(50 tick 后) | 观察半径内派发直接做工位扫描;30 格外走 50 tick 进入可见半径后同样触发扫描失败 |
| 4 | part B 脚本断言错格 | 驱动脚本硬编码 (560,68,129) 而矿在 (560,69,129),修正为从状态文件读 cell |

最终解:矿放回 A 原格(同层邻格 559/68/129 站面完整,RCON seed 探测证实)+ 镐 + 30 格外派发 +
2 秒停服。所有失败轮的图与收据保留在 journal/store 中作为诚实历史。

## 附注

- 空气格不可严格观察(R1 已知设计):终结化身必须用**可观察方块**(stone)替换,直接 setblock air 不触发销账。
-journal 文件运行期被服务器独占锁,收据提取在停服后完成。
