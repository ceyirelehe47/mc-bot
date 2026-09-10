# LIVE-2A01F-3｜idle 终态事件经 steer 唤醒续作（不再 followup）

环境：同 LIVE-2A01F-1。会话 turn 2→3 与 turn 4→5 两组实证。

## 场景构造（第一组：turn 2→3）

1. 用户消息（turn 2）：提交 `mc_goto (566,64,151)`，"任务被接受后本回合就结束"。
2. Agent 调 mc_goto → accepted → `exec.concludeTurn()` → turn 2 结束，**Agent idle**
   （continuation 所有权移交独立事件消费者——plugin.mjs 设计语义）。
3. 身体走完（数秒）→ journal 事件 158 `execution/completed`
   （reason=arrival_within_3_blocks_verified）。
4. plugin pump → `enqueueEvents(agent.status='idle', autoWake=true, terminal)` →
   **`agent.steer()`**（EVT-TR-3）。

## 证据一（session.v3.jsonl）

```text
seq 28   agent/inbox/spliced  target=next-turn   [用户消息 → turn 2]
seq 30   agent/inbox/spliced  target=next-turn (claim)
seq 37   turn/end turn=2 completed               ← Agent idle（等身体事件）
seq 38   agent/inbox/spliced  target=next-step   [execution seq=158]  ← steer 入队并唤醒
seq 40   agent/inbox/spliced  target=next-step (claim)
seq 50   turn/end turn=3 completed               ← 被唤醒的续作 turn 完成
```

## 证据二（turn 4→5，长 goto 失败终态同样唤醒）

```text
seq 60   turn/end turn=4 completed               ← mc_goto accepted 后 idle
seq 61   agent/inbox/spliced  target=next-step   [execution seq=161 state=failed]
seq 63   agent/inbox/spliced  target=next-step (claim)
seq 73   turn/end turn=5 completed
```

## 判定

- 终态事件（completed / failed 都是 terminal）在 **idle + autoWake=true** 下插入
  **next-step 队列并立即唤醒新 turn**（DSH 语义：idle driver 收到 wakeup=true 的
  next-step 消息即开新 turn，packages/core/agent-loop/src/agent.ts:198-207）——
  这正是 steer 的行为特征；旧 followup 会插入 **next-turn** 队列（jsonl 中
  target=next-turn），本轮两组事件均为 **target=next-step**。
- 若是 inject（idle 不唤醒），事件会滞留 next-step 队列且无新 turn——与观测不符。
- cursor 在每次事件消费后推进（最终 sequence=164 与 journal 一致），无 followup
  backlog。

## 结论：PASS
