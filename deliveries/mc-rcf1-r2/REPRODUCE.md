# REPRODUCE
1. 生命周期假测试: python tools/rcf1_lifecycle_fake_tests.py (L01-L13 19/19)
2. 单元: cd /d D:\code\mc-experiment\rcf1-rebuild-base && gradlew test (475/475)
3. checker: python tools/rcf1_checker_selftest.py (18/18); python tools/probe_checker_regressions.py (4/4 拒绝)
4. V10+V 离线: python tools/rcf1_tests_v.py (8/8, V01-V09 NOT_RUN 需 LIVE)
5. 起停: python tools/rcf1_lifecycle.py start|stop|status server|client
6. C/N/IA: python tools/rcf1_tests_c.py / rcf1_tests_n.py / rcf1_tests_ia.py (环境在线)
7. G4: python tools/rcf1_g4.py (五次矩阵); 判定 python -c "import json,sys;sys.path.insert(0,'tools');import rcf1_checker as K;d=json.load(open(r'D:\mc-rcf1-raw\g4-runs.json',encoding='utf-8'));print(K.judge_g4(d['runs']))"
8. G5: python tools/rcf1_g5.py s01|s02 (当前 BLOCKED)
9. fresh replay: robocopy rcf1-rebuild-base → 新目录(XD build .gradle) + gradlew remapJar test + 部署重跑 rcf1_g4.py --diagnostic
