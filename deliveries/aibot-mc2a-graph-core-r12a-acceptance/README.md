# MC-2A Graph Core R1.2A Acceptance Repair

Evidence-only round. No file under `aibot-dsh-m0/` may change.

Generated outputs belong under:

- `00-meta/`
- `01-junit-dev/`
- `02-junit-replay/`
- `03-live-original-graph/`
- `04-history/`
- `RESULT.zh-CN.md`

Drivers:

- `drivers/r12a_phase1_plan_only_and_rollback.py`
- `drivers/r12a_phase2_restart_same_graph_to_done.py`

The phase-1 driver must contain no `/run-next`.
The phase-2 driver must contain no opportunity Graph plan call.
