# MC-RCF-1-R1 复现
1. 生命周期假测试:python tools/rcf1_lifecycle_fake_tests.py(L01-L10,独立 NS)
2. 起停:python tools/rcf1_lifecycle.py start|stop|status server|client
3. C/N/I/A:环境在线后 python tools/rcf1_tests_c.py / _n.py / _ia.py
4. 构建:rcf1-rebuild-base gradlew build(代理经全局 gradle.properties)
5. 部署:build/libs/aibot-0.0.1.jar → rcf1-{server,client}/mods/
6. G4:python tools/rcf1_g4.py(当前 BLOCKED 于机会池刷新)
