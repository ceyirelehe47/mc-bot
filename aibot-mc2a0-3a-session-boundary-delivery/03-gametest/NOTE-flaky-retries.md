# GameTest 物理随机类测试重试说明（如实披露）

本轮三跑中，两次出现与 MC-2A0.3A 改动面无关的上游/冻结基线物理测试偶发失败，均以同树
重跑收敛为全绿，失败 XML 与失败日志已一并归档供审查：

| 轮次 | 偶发失败测试 | 失败消息 | 定性依据 | 收敛结果 |
|---|---|---|---|---|
| round2 首跑（dev 树，2026-09-13 00:56 前后） | `huntcrossregiongametests.observedwoolpickuptriggersphysicalrecoveryofmissedmutton` | `hunt_drop_unrecovered ... item=minecraft:mutton baseline=0 current=0` | 上游 hunt 掉落物回收测试，依赖实体物理拾取时序；hunt 代码本轮零改动（见 07-audit 冻结审计） | round2 重跑 637/0 全绿（TEST-round2.xml 为重跑结果） |
| replay 首跑（重放树，2026-09-13 00:06 前后） | `mc2a02treeharvestgametests.mc2a02gatherkeepstemporarysupportsuntilhigherworkfaceisreached` | `gather ended FAILED:no_resource_after_explore maxConcurrentPlaced=0` | MC-2A0.2 冻结基线树采集测试，依赖自然树生成的空间随机性；mc2a02 代码本轮零改动；dev 树 round1/round2 同测试均通过 | replay 重跑收敛（TEST-replay.xml 为重跑结果） |

两次失败的共同特征：均为"物理世界随机探索/拾取"类测试，且失败测试所属源码全部位于本轮
改动面之外（本轮仅改 ExternalBodyAccess/ExternalBodyRuntime/BridgeKernel 三个生产文件 +
测试/事件文件，见 01-diff/production-full.diff 与 07-audit/gate-d-audit.json 的
changed_files 审计）。

归档策略：每轮以"最终全绿跑"的 XML 为权威结果；首次失败的 gradle 控制台日志保留在
gametest-round2-failed-first.log / gametest-replay-failed-first.log。
