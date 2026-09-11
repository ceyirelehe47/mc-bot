# 审计 — 图持久化与 fail-closed(任务书强制审查点 1)

## 持久化路径/格式

- 路径:`<world>/aibot/task-graphs-<bot>.bin`(与桥 journal 同目录、相互独立的有界存储)。
  实机:`world_play/aibot/task-graphs-bob.bin`(G-LIVE-1 创建)。
- 格式:自定义二进制,魔数 `AIBODYGR1`(US-ASCII 9B)+ version int32 + 图计数 +
  每图(定长界字符串:graph_id≤96/producer_key≤400/plan_key≤120/producer_kind≤80,
  createdAt/updatedAt int64,节点 1..64 个:state≤32/operation≤80/arguments≤16384/
  resourceClaim≤400/SpatialRef 三段各≤160/postcondition≤80/executionId≤160/
  dispatchRequest≤160/reason≤512/attempt int32/依赖 0..16 个各≤96)+
  尾部定界(读到 EOF 必须为 -1,拒绝 trailing bytes)。

## 边界与 fail-closed

- 容量:MAX_GRAPHS=128、MAX_NODES=64、MAX_DEPS=16、单串/文件 2MiB
  (`graph_store_size_limit`),全部读取侧强制。
- 读取校验:魔数/版本/计数界/重复 id/重复 producer key/DAG 校验(validateDag 重放)、
  状态枚举 valueOf(非法枚举名 → 异常)。
- **malformed 一律 fail-closed**:任何读取异常 → 清空内存态 → `BridgeFault(503,
  task_graph_store_invalid)` → 外部模式启动失败(不静默降级、不带病运行)。
- 写入:tmp 文件 + fsync(FileChannel.force) + 原子 move
  (ATOMIC_MOVE 不可用时回退 REPLACE_EXISTING);超限即弃 tmp 抛错。
- 重启语义:load() 内 RUNNING→SUSPENDED(`restart_revalidation_required_no_replay`,
  确定性 JUnit 真文件重载覆盖)+ reconcile 仅由服务端线程 tick 驱动、仅可证 DONE。
- 控制字符/空白 id 拒绝(boundedId),杜绝二进制注入存储。

## 实机证据

G-LIVE-1(创建即持久化)、G-LIVE-6(6 次停启循环中图状态字节级保持,SUSPENDED
恢复、零重放、世界证明转 DONE)、G-LIVE-7(取消/释放/重声明跨重启正确)。
