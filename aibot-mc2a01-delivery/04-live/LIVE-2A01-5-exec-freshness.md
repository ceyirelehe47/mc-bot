# LIVE-2A01-5｜execution + freshness semantics

环境同 LIVE-2A01-1。

## EXEC-1 实测

**IDLE（无活动执行，含刚终态后）**——多次采样：

```
execution: {"current_task": null, "paused_depth": 0, "progress_bucket": 100,
            "safety_active": false, "state": "IDLE", "user_paused": false}
```

注：`current_task: null` 是显式 null（不是字符串 "idle"，也不是残留的旧任务名——
修复前实测 DSH 场景 state=IDLE 时 current_task 仍显示上一个 gather）。

**RUNNING（gather cobblestone×16 真实执行中）**——连续采样：

```
execution: {"current_task": "gather", "progress_bucket": 0,  "state": "RUNNING", ...}
execution: {"current_task": "gather", "progress_bucket": 25, "state": "RUNNING", ...}
execution: {"current_task": "gather", "progress_bucket": 25, "state": "RUNNING", ...}
```

任务终态（completed）后回到第一段的 IDLE+null。

（对照：一次 cherry_log×1 的 gather 因背包已含配额而瞬时完成——提交即终态，
view 全程 IDLE+null，不出现假 RUNNING。）

## FRESH-1 实测（opportunity 三处一致）

RCON 在 Bob 视野内放 `iron_ore`，resident perception 自动注册机会（total 10→12，
新卡 freshness=RECENT——真实 game time，非 MC-2A0 的恒 UNKNOWN）。

同一机会（ore_7d9072bb…）：

```
view card freshness:    RECENT
inspect summary:        RECENT
inspect evidence:       RECENT
THREE-WAY CONSISTENT: True (none UNKNOWN)
```

三处的 freshness 均以**同一 snapshot gameTime** 为基准（kernel 把 cognitive.gameTime
传给 materialize），语义一致由 GameTest
`mc2a01OpportunityInspectFreshnessMatchesView`（view/summary/evidence 相等且为 RECENT）
锁定；RECENT→AGING→STALE 桶边界由 `freshnessOf` 单元语义 + view 卡实测（旧机会
first freshness=STALE）覆盖。

## 结论：PASS
