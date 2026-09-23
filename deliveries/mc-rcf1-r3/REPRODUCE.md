# REPRODUCE — MC-RCF-1-R3

前置:.secrets/(rcf1-bridge.token / rcf1-control.token / rcf1-rcon.pass);
JDK 21;Python 3.12+。工作目录 D:\code\mc-bot。

```bat
:: 1. 构建与部署(从权威源码)
python tools\rcf1_sync_overlay.py
cd /d D:\code\mc-experiment\rcf1-rebuild-base
gradlew.bat remapJar -g D:\code\mc-experiment\.gradle-rcf1 --no-daemon -q
copy build\libs\aibot-0.0.1.jar D:\code\mc-experiment\rcf1-server\mods\
copy build\libs\aibot-0.0.1.jar D:\code\mc-experiment\rcf1-client\mods\

:: 2. 环境(唯一生命周期入口)
python tools\rcf1_lifecycle.py start server
python tools\rcf1_lifecycle.py start client
::   就绪核验:python -c "import sys;sys.path.insert(0,'tools');
::   import rcf1_env as E;print(E.status()['data']['server_mod_jar_sha256'])"

:: 3. 非计分诊断核心链
python tools\rcf1_g4.py --diagnostic

:: 4. 洞内连续放置小切片
python tools\rcf1_place_slice.py

:: 5. G4 正式五连(矩阵预写定:B01/B02 橡木、B03/B04 白桦、B05 布局扰动)
python tools\rcf1_g4.py
python tools\rcf1_checker.py judge-g4 D:\mc-rcf1-raw\g4-runs-r3.json

:: 6. IA LIVE 事实采集(事实版)
set RCF1_IA_FACTS=D:\mc-rcf1-raw\ia-facts-r3.json
python tools\rcf1_tests_ia.py
python tools\rcf1_checker.py judge-ia D:\mc-rcf1-raw\ia-facts-r3.json

:: 7. 成对语义变异(真实证据生成,同入口必须拒绝)
python tools\rcf1_mutations.py generate ^
  D:\mc-rcf1-raw\ia-facts-r3.json D:\mc-rcf1-raw\g4-runs-r3.json ^
  D:\mc-rcf1-raw\mutations-r3
python tools\rcf1_mutations.py run D:\mc-rcf1-raw\mutations-r3

:: 8. 离线组
python tools\rcf1_lifecycle_fake_tests.py
python tools\rcf1_checker_selftest.py
python -c "import sys;sys.path.insert(0,'tools');import rcf1_tests_v as V;V.v10_real_strategy();V.checker_consistency()"

:: 9. G5(LLM 驱动;场景声明 tools\rcf1_g5_scenes.json)
python tools\rcf1_g5.py prep s01 s01
python tools\rcf1_g5_drive.py mine s01 --block minecraft:oak_log --near 94 93 -72 --need 2 --note "..."
python tools\rcf1_g5.py dusk-baseline s01
python tools\rcf1_g5.py night s01
python tools\rcf1_g5.py verify-morning s01
python tools\rcf1_checker.py judge-g5 D:\mc-rcf1-raw\g5r3-s01.jsonl s01

:: 10. fresh replay(全新目录:上游+补丁+overlay)
git clone --no-hardlinks D:\code\mc-experiment\aibot D:\mc-rcf1-r3-replay\build-fresh
cd /d D:\mc-rcf1-r3-replay\build-fresh
git apply D:\code\mc-bot\aibot-dsh-m0\upstream-patches\r3-base-tree.patch
::   overlay 85 文件:从 repo aibot-dsh-m0/aibot-overlay/src 复制到 build-fresh\src 同相对路径
gradlew.bat remapJar -g D:\mc-rcf1-r3-replay\gradle-home --no-daemon -q
::   与部署产物逐项比较见 BASELINE.md(671 条目,0 class 差异)

:: 11. 停机(全角色范围核验)
python tools\rcf1_lifecycle.py stop all
```
