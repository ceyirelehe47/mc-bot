# LIVE-R11-3 — 真实依赖串 aX 截断 → 运行时 fail-closed

**结论: PASS**。

## 夹具

任务包自带 `live/MakeTruncatedDag.java`,以生产 R1.1 类编译运行
(`build/classes/java/main`),直接写入生产路径
`world_play/aibot/task-graphs-bob.bin`(已先备份为 `.r11-backup`):
节点 `a`、`aX`、`z`(dependency=`aX`),`z` 为最后节点、`aX` 是文件最后一个字段。

helper 自校验合法文件以 ASCII `aX` 结尾后才截断最后一个字节 `X`(`helper-stdout.txt`):

```
valid_bytes=1011
truncated_bytes=1010
final_valid_dependency=aX
final_truncated_prefix=a
```

SHA256(`sha256-fixtures.txt`):
- 合法(截断前,逐字节重建=畸形+`X`,与 helper 报告的字节数一致):`6d413189f3831f04bcc67a39ba94b4709a80b1b10618bab234bfa26cd423f260`
- 畸形(生产路径现存):`f73f4f3ca1cdd7267eb90650335ac93d4a399a8e6ba3505315f61766018d8042`

## 启动 fail-closed

畸形存储启动真实运行时(会话 G,`failclosed-startup.txt`):

```
java.lang.IllegalStateException: external_bridge_start_failed_closed
  at ExternalBodyRuntime.start(ExternalBodyRuntime.java:45)
Caused by: io.github.zoyluo.aibot.external.BridgeFault: task_graph_store_invalid
  at TaskGraphStore.load(TaskGraphStore.java:392)
```

- 崩溃点在服务器 afterSetupServer 阶段,桥从未绑定 8765。
- 畸形文件**未被静默改写**:崩溃后 sha256 仍为 `f73f4f3c…`(见上)。

## 恢复

备份逐字节恢复(`cmp` 通过,`restore-proof.txt`:store==backup,sha `63ce678c…`),
再次启动(会话 H)20 秒内正常出现
`AIBot external-body bridge bound to loopback port 8765`(`normal-start.txt`)。
