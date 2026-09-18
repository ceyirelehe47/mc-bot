# Gate C — invalid body-id 受控 fail-closed 启动结果

执行命令（隔离一次性服务器副本 mc-server-mc2a03a-invalid，全新生成世界，副本已删除）：

```text
python invalid_body_id_start.py \
  --server D:/code/mc-experiment/mc-server-mc2a03a-invalid \
  --java D:/mc-server/jdk-21.0.12.1+1/bin/java.exe \
  --log D:/code/mc-bot/.taskpack-mc2a03a-attach/invalid-body-id.log \
  --bridge-port 8767 --timeout 300
```

输出：`PASS invalid body id failed inside controlled runtime start boundary`

服务器 mods 中的 jar：aibot-0.0.1.jar sha256=
`baa1d616e202de0654bf87457c1d9d1531759ee1f32715336ac6cb526e43bdfb`（02-build/jar-sha256.txt）。
注入配置：`AIBOT_EXTERNAL_BOT=Bob`、`AIBOT_EXTERNAL_BODY_ID="invalid body id!"`、桥端口 8767。

## runbook 五项要求逐条核证（invalid-body-id.log）

| 要求 | 结果 | 证据行 |
|---|---|---|
| 含 `external_bridge_start_failed_closed` | ✓ | log:98 `java.lang.IllegalStateException: external_bridge_start_failed_closed`（ExternalBodyRuntime.start:52 抛出） |
| 含 `invalid_AIBOT_EXTERNAL_BODY_ID` | ✓ | log:106 `Caused by: java.lang.IllegalArgumentException: invalid_AIBOT_EXTERNAL_BODY_ID`（normalizeBodyId:35 ← configureBodyId:43 ← start:27） |
| 不含 `ExceptionInInitializerError` | ✓ | 全文 0 命中——类加载不校验 body id（CFG-1） |
| 端点绑定标记不出现 | ✓ | `external-body bridge bound to loopback port` 0 命中 |
| 桥端口不可达 | ✓ | 脚本 socket 探测 8767 断言通过 |

## 语义说明

- 异常链完整落在受控边界内：activateReservation 先行 → try 内 BOT_NAME 校验 →
  configureBodyId 抛 typed 原因 → catch 关闭 http/journal/registry →
  `IllegalStateException("external_bridge_start_failed_closed", cause)` 上抛，
  服务器 crash 退出（SERVER_STARTED 监听器传播），桥从未绑定。
- 预约保持语义：RESERVATION_ACTIVE 在校验前激活（静态状态），进程崩溃即整体失效，
  不存在"无效配置静默回落旧脑"的窗口。
- 服务器随后正常走 server_stopping 存盘路径（log:114-120），一次性副本已删除。
