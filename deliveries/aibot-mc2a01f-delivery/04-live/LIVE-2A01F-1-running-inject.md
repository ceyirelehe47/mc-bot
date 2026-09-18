# LIVE-2A01F-1｜running routine event 经 inject 在同一长 turn 可见

环境：真实 DSH web（本地 checkout 5dda764 + 本轮 scratch-aibot-body 插件）+ 真实模型
（deepseek/deepseek-v4.1-flash，commandcode relay）+ 隔离服 world_play（Bob，
MC-2A0.1F jar f4b2df81…）。会话 `session-23315a0d…`（turn 7）。

## 场景构造

这正是真实试玩暴露的问题形态：Agent 处于一个多步长 turn（连续 5 次 mc_observe、
步间隔 ~4s），期间身体侧产生 ordinary deliverable 事件。旧 followup 实现里该事件
会进入 next-turn 队列、当前 turn 永远看不到并形成 backlog。

1. 用户消息开启 turn 7（观察循环，5 次 mc_observe，间隔 ~4s）。
2. turn 7 running 进行中（第 3–4 次观测之间），RCON 在 Bob 视野内放置两块铁矿：
   `setblock 489 69 122 iron_ore` + `setblock 488 70 123 iron_ore`。
3. 身体 perception（10t 周期）观察到矿 → registry 注册 opportunity →
   推送 journal 事件 163/164 `resource_opportunity_actionable`
   （IMPORTANT、非 urgent → **routine 类**）。
4. plugin pump → deliver → `enqueueEvents(agent.status='running', routine)` → **`agent.inject()`**。

## 证据（session.v3.jsonl，seq 连续）

```text
seq 160  agent/inbox/spliced  target=next-step  [resource_opportunity_actionable seq=163]
seq 161  agent/inbox/spliced  target=next-step  [resource_opportunity_actionable seq=164]
seq 166  agent/inbox/spliced  target=next-step (claim —— 同 turn 的下一个 step 边界消费)
seq 168  user/message  source.kind=plugin（事件消息进入该 step 的模型输入）
seq 169  user/message  source.kind=plugin（第二条）
seq 177  turn/end turn=7 completed        ← 同一个 turn 自然结束，无新 turn
```

关键点：
- 事件进入的是 **next-step** 队列（inject 与 steer 的 DSH 通道），不是 followup 的
  next-turn 队列；insert(160/161) 与 claim(166) 之间**没有 turn/begin**——没有借新
  turn 兜底，事件就在 turn 7 内部到达模型。
- 模型在 turn 7 的最终汇报原文（webui 实录）：

  > "2. 视野内新出现两处铁矿（本回合唯一的"新矿物"）。在第 4 次和第 5 次观测之间，
  > 桥接层推送了两条 resource_opportunity_actionable 事件，随后一次观察确认：
  > 两者 status: ACTIONABLE，required_tool: minecraft:stone_pickaxe……"

  ——模型在同 turn 内明确叙述事件到达的时点（第 4、5 次观测之间），
  running+routine→inject→同 turn 后续模型步可见的完整链条实证。

## backlog = 0

事件全部消费后插件 cursor（`aibot-dsh-state/dsh-session-5caba4c8….json`）：

```json
{"version":1,"epoch":"fdb19d84-…","sequence":164}
```

与桥 journal `last_sequence=164` 一致——无未投递积压、无 next-turn 队列残留。

## 结论：PASS
