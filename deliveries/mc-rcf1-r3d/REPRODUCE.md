# MC-RCF-1-R3D — BASELINE / COVERAGE / REPRODUCE

## BASELINE

- 起点基线:`e9217c62e595a752daeba5ecac2280df860aeba8`(任务包核对值,
  本地=远端一致后开工)
- 候选 C:**本交付 HEAD(见 git log;含全部行为改动)**:
  - R01:`tools/rcf1_mine_skill.py`(核心)+`rcf1_g5_drive.py`/
    `rcf1_g4.py` 适配层+`play.py` 租约恢复+`rcf1_d_diag.py`
  - R02/R03:`rcf1_checker.py` v4+`rcf1_checker_selftest.py`+
    `rcf1_mutations.py`+`rcf1_facts.py`/`rcf1_tests_ia.py`(对账层)
  - I08:`toms_diag` 只读诊断 op(BridgeKernel/driver/compat 三层)
  - mod 测试:`RealClientMvpSourceTest` 期望集纳入 toms_diag
- 构建输入:上游 `aibot@a029fa6` + `aibot-dsh-m0/upstream-patches/
  r3-base-tree.patch` + overlay(repo 权威)→ `rcf1_sync_overlay.py
  --build D:/code/mc-experiment/rcf1-rebuild-base` →
  `gradlew.bat remapJar --no-daemon -g D:/code/mc-experiment/.gradle-rcf1`
- 运行身份:client/server mod jar 均为 `29d160c37d813b75…`
  (observe/status 双端读取一致;fresh-deployment.json 有完整 64 位)
- 世界:全新生成 seed=178000000001(level.dat sha 见 fresh 部署清单);
  绝非旧运行目录复制(72 条依赖逐条 sha256 入账)

## COVERAGE

| 组 | 结果 | 证据 |
|---|---|---|
| D01–D06(+b) | 8/8 | evidence/d-slices/g5r3-r3d-d0*.jsonl |
| 非计分链/封口 | ✓ | evidence/diag/g4-diag-r3.json, place-slice-r3.jsonl |
| G4 B01–B05 | 5/5 accept | evidence/g4-runs-r3.json |
| IA I01–I08/A/V | 28/29(I08 阻断) | evidence/ia-facts-r3d.json |
| C01–C07(+C08 NOT_RUN 带理由) | 7/7 | evidence/tests-c.jsonl + c-group/ |
| N01–N06 ×3 | 18/18 | evidence/tests-n.jsonl |
| V01–V10 | 13/13 | evidence/tests-v.jsonl |
| L01–L14 | 20/20 | evidence/lifecycle.jsonl |
| JUnit / Python | 479/479, 82/82 | evidence/build-report.json |
| 变异 | g4 组全拒+正例受;ia 组 16 INCONCLUSIVE | evidence/mutations/ |
| I08 | BLOCKED(上游 #381;8 拓扑+toms_diag 反射) | evidence/i08-blocked.json |
| G5 S01(×2)/S02 | 失败/失败/未跑 | evidence/g5/*.jsonl |
| judge-all | 12 门:7 绿 + 5 项如实不过(ia/g5_s01/g5_s02/i08/mutations) | evidence/judge-all-manifest.json |

## REPRODUCE(相对仓库根;RCF1_ROOT 等按本机部署路径)

```bat
:: 环境(唯一生命周期入口;单 server+单 client)
set RCF1_ROOT=D:\code\mc-experiment
set RCF1_SUFFIX=r3d
set RCF1_RAW=D:\mc-rcf1-raw-r3d
set RCF1_LIFECYCLE_NS_ROOT=D:\mc-rcf1-raw-r3d\lifecycle
python tools\rcf1_r3d_deploy.py --build-jar <新构建jar>   :: 空目录安装
python tools\rcf1_lifecycle.py start server
python tools\rcf1_lifecycle.py start client

:: 离线
python tools\rcf1_mine_skill_tests.py     :: 12/12
python tools\rcf1_checker_selftest.py     :: 50/50

:: LIVE 矩阵(全部在 r3d 干净部署执行;D 切片/非计分链为 verifier
:: 复核后在同部署重跑的记录)
python tools\rcf1_d_diag.py all           :: D01-D06
python tools\rcf1_g4.py                   :: 5/5
python tools\rcf1_tests_ia.py             :: 28/29(I08 BLOCKED)
python tools\rcf1_tests_c.py              :: 7/7
python tools\rcf1_tests_n.py              :: 18/18
set RCF1_V_LIVE=1 && python tools\rcf1_tests_v.py   :: 13/13
set RCF1_LIFECYCLE_NS_ROOT=D:\mc-rcf1-raw-r3d\lifecycle-fake
python tools\rcf1_lifecycle_fake_tests.py :: 20/20

:: 判定
python tools\rcf1_mutations.py generate %RCF1_RAW%\ia-facts-r3d.json %RCF1_RAW%\g4-runs-r3.json %RCF1_RAW%\mutations-r3d
python tools\rcf1_mutations.py run %RCF1_RAW%\mutations-r3d
python tools\rcf1_checker.py judge-all %RCF1_RAW%\judge-all-manifest.json

python tools\rcf1_lifecycle.py stop all
```

正反对照:F 族变异(单因素)由 `rcf1_mutations.py generate` 从真实
证据生成(29 个),selftest 另含 18 个 F01–F07 边界用例(50/50)。
