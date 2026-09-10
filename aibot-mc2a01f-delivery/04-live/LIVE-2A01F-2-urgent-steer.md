# LIVE-2A01F-2｜urgent 事件经 steer 进入 running 中的当前 turn

环境：同 LIVE-2A01F-1（真实 DSH + 真实模型 + 隔离服 world_play）。会话 turn 6。

## 场景构造

1. 用户消息开启 turn 6（观察循环长 turn：连续 5 次 mc_observe，间隔 ~3s）。
2. turn 6 running 进行中，RCON 施加一次显著伤害（安全 fixture，不杀 bot）：
   `damage Bob 6 minecraft:generic` → health 11.5→5.5。
3. 身体 health_delta 监测产生 journal 事件 162 `damage`
   （previous_health 11.5 → health 5.5，delta 6 ≥ 4 → significant → **urgent**）。
4. plugin pump → `enqueueEvents(urgent)` → **`agent.steer()`**（EVT-TR-2）。

## 证据（session.v3.jsonl，seq 连续）

```text
seq 74   agent/inbox/spliced  target=next-turn   [用户消息 → turn 6 开启]
seq 76   agent/inbox/spliced  target=next-turn (claim)
seq 96   agent/inbox/spliced  target=next-step   [damage seq=162]   ← urgent 事件入队
seq 99   agent/inbox/spliced  target=next-step (claim —— 当前 turn 6 的下一个 step 消费)
seq 129  turn/end turn=6 completed               ← 同一 turn 继续到自然结束
```

关键点：
- 事件 162 在 turn 6 **running 期间**插入 **next-step** 队列（steer 通道）并在同
  turn 的下一个 step 边界被 claim——当前模型步序列立即看到，无需等 turn 结束。
- insert(96) 与 turn/end(129) 之间没有新 turn——不是 followup 的"下回合才见"。
- 插件 cursor 随后推进至 162（见 LIVE-1 的 cursor 文件，最终 164），无积压。

传输原语为 `steer()` 的锁定：DSH 的 inject/steer 在 running 状态下进入同一
next-step 队列、行为等价（源码 packages/core/agent-loop/src/agent.ts:137-147），
运行时不可区分；原语选择由本仓库源码（events.mjs urgent 分支调用 `agent.steer`）
与 Node 契约测试 `running urgent event steers`（记录 fake agent 的 `['steer',…]`
调用）共同锁定。

## 结论：PASS
