# DSH event transport audit（MC-2A0.1F）

## 审计对象

PRODUCTION_SHA=13a490cb4ce3341aa09beeecc1e0e7b688ff89e2
审计范围：`aibot-dsh-m0/dsh-plugin/src/`（Minecraft Body 事件传输路径）。

## followup 移除（EVT-TR-5 / EVT-5）

```text
$ grep -rn "\.followup(" aibot-dsh-m0/dsh-plugin/src/
（0 命中）
```

src/ 下 Body 事件传输路径不含任何 `.followup(` 调用。Node 契约测试
`body event transport source contains no followup call` 逐文件扫描 src/ 目录锁定
（回归即红）。三个 fake agent（events/plugin/http-integration 测试）**不再提供**
followup 方法——任何回归的 followup 调用会以 TypeError 形式立刻失败。

## Transport matrix（EVT-TR-1..4）

`enqueueEvents`（src/events.mjs）实现与任务矩阵逐项对应：

| Agent 状态 | autoWake | 事件类 | 动作 | 锁定测试 |
|---|---:|---|---|---|
| any | false | any | `inject`（不唤醒） | autoWake false injects without waking；http-integration autoWake 场景 |
| running | true | urgent（6 类） | `steer` | running urgent event steers |
| running | true | routine | `inject`（同 turn 下一 step 边界可见） | running routine event injects and never followups |
| idle | true | wake-worthy（shouldDeliver 通过集） | `steer`（唤醒续作） | idle wake-worthy event steers instead of followup |

urgent 集合（URGENT_KINDS）= death / damage(经 shouldDeliver 显著伤害过滤) /
player_message / survival_alert / control_lost / body_changed——与任务 EVT-TR-2
的最低集一致。wake-worthy 谓词 = `shouldDeliver` 通过集：pump 只把 IMPORTANT /
terminal execution / safety_preempted paused / significant damage 送进
enqueueEvents，到达即 wake-worthy，idle 分支无需第二套分类。

## durable ordering 不变（EVT-TR-6 / EVT-6）

journal → ingress(inject/steer) → `ctx.sessionPersistence.flush()` 成功 → cursor
推进的顺序链未被触碰：`deliver` 闭包（plugin.mjs）仍先同步 enqueueEvents 再
await flush；`pumpEvents` 仍只在 deliver resolve 后 `store.save(cursor)`；失败
路径 cursor 原地重试（不 ack-and-drop）。既有 Node 契约（cursor after delivery+
flush / delivery failure leaves cursor unchanged / gap resync notice / successful
say does not self-wake / plugin-attributed game text untrusted）全部保持绿色。

## 与真实 DSH 语义的匹配（5dda764 源码核验）

- `inject` = send('next-step', wakeup=false)：running 时在最近 step 边界进入模型
  输入；idle 时滞留队列不唤醒（packages/core/agent-loop/src/agent.ts:137-147）。
- `steer` = send('next-step', wakeup=true)：running 时同上；idle 时立即开新 turn
  并把消息作为该 turn 首个模型步输入（agent.ts:198-207、inbox claim 清队语义）。
- `followup` = send('next-turn', wakeup=true)：next-turn 整 turn 队列——本轮对
  Body 事件禁用。
- 三个方法均为同步 void、无返回 Promise（无 unhandled rejection 面）。

LIVE-2A01F-1/2/3 的 session jsonl（`04-live/dsh-session-event-flow.txt`）实证
next-step 通道在三种场景下的行为与本节源码核验一致。
