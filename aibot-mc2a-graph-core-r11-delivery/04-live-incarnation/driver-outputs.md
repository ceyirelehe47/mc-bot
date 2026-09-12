# R11-1/R11-2 运行输出存档(驱动脚本 stdout,从执行记录誊录)

## LIVE-R11-1 首轮(r11_live1_incarnation.py,会话 A)

```
tp: Teleported Bob to 558.500000, 68.000000, 129.500000
place_iron: Changed the block at 560, 68, 129
incarnation_A=ore_3467bfcc851f_8ef944cd84a44b77
replace_stone: Changed the block at 560, 68, 129
A_terminalized: registry no longer contains A
replace_iron: Changed the block at 560, 68, 129
incarnation_B=ore_3467bfcc851f_0b063e1d160f4932
ASSERT A != B: OK
planned: graph-c69f8e550a0a47be4ed1eaf2 state= READY
run-next accepted: {"graph": {... "state": "RUNNING", ...}}
stop: Stopping the server
```

## LIVE-R11-1 最终装置(r11_live1e_incarnation.py,会话 E)

```
old_clear / place_iron (560,68,129 回到 A 原格)
incarnation_final=ore_3467bfcc851f_858f6b3b15f24d89
FINAL != A: OK; status= ACTIONABLE
tp_far: Teleported Bob to 530.500000, 68.000000, 129.500000
planned: graph-59da783ebe76fd8fece7a7c2 state= READY
run-next accepted: RUNNING
stop: Stopping the server
```

## LIVE-R11-1 重启断言(r11_live1_restart.py,会话 F)

```
graph_id= graph-59da783ebe76fd8fece7a7c2
A= ore_3467bfcc851f_8ef944cd84a44b77  B= ore_3467bfcc851f_858f6b3b15f24d89  cell= (560, 68, 129)
B_SURVIVES_RESTART: OK
A_NOT_RESURRECTED: OK
graph_state= SUSPENDED reasons= ['execution_outcome_unknown_no_replay']
GRAPH_NOT_TERMINALIZED_BY_A_RECEIPT: OK
```

(journal 断言因运行期文件锁改在停服后执行:journal-receipt-A.txt,seq 783。)

## LIVE-R11-2 成功轮(r11_live2_stale.py,会话 F)

```
incarnation_C=ore_ab3babe1251c_ac15e54bac7e4fc3
tp_far: Teleported Bob to 530.500000, 68.000000, 129.500000
planned: graph-973d0b7c12c54b8ce32b7716 state= READY
run-next accepted
remove_ore: Changed the block at 560, 68, 131
graph_state= STALE
nodes= [{"node_id": "n-mine-ac8542cce5b1", "state": "STALE", ...,
  "execution_id": "05940fdc-0710-44f7-9e72-3a15d985e3f2",
  "reason": "execution_failed_terminal_unsatisfied:...
  "subject_ref": {"object_id": "ore_ab3babe1251c_ac15e54bac7e4fc3", ...}}]
GRAPH_STALE_NOT_FAILED: OK
```

(journal 断言同上,停服后提取:journal-receipt-stale-exec.txt,seq 853。)
