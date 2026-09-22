# COVERAGE (分母=原矩阵+R1L/V+R2要求;子条件不足不整行PASS)
| ID | 状态 | 证据 |
|---|---|---|
| C01-C05 | PASS | live/c-group-20260922-025645.json (5/5) |
| C06 | PASS(等价) | I06 三阶段 use/cursor 取消;C02 覆盖导航取消 |
| C07 | PASS(等价) | ledger 单测(TaskGraphStoreTest/BridgeJournal)+执行去重 |
| C08 | NOT_RUN | 无授权真人共存环境,如实不计 |
| N01-N06 | PASS 18/18 | live/n-group-20260922-034517.json (每类3次) |
| N-TERRAIN | PASS | default/false 零挖零放贯穿;true 显式拒绝(terrain_changes_not_supported) |
| I01-I05,I07 | PASS | live/ia-group(最新全量 23/26)+单项复验 |
| I06 | PASS | 三阶段守恒(pickup8/0 partial7/4 produced7/4 折算=8) |
| I08 | BLOCKED | 真实终端网络部署;mod 收货网络未形成;服务端守恒拒绝假成功(证据 latest.log+RESULT) |
| A01-A07,A09,A11,A12 | PASS | 同 IA JSON |
| A08 | PASS | 主包/快捷栏双槽位 completed claimed=1+hunger0->8 |
| A10 | PASS | window=true(先证破坏未拾取)→阻断→stale |
| L01-L13 | PASS 19/19 | rcf1_lifecycle_fake_tests.py |
| V01-V09 | 等价覆盖 | IA 组同类负例(V01/V02/V04 等在套件内);独立注入 runner 未单列 |
| V10 | PASS | 真实策略 rcf1_shelter 注入 6/6+checker一致性 |
| B01-B05 | PASS 5/5 | live/g4-runs.json; judge_g4=ok |
| S01,S02 | BLOCKED | g5-s01.json(失败链保留);S02 未跑(同阻塞) |
| FRESH-REPLAY | PASS | rebuild-fresh 构建+核心链+双负例(RESULT 表) |
| 历史四反例 | 4/4 拒绝 | probe_checker_regressions.py |
