# REPRODUCE — MC-RCF-1-R3C

## 0. 前提

- Windows;JDK21(D:\mc-server\jdk-21.0.12.1+1);Gradle 走
  D:\code\mc-experiment\.gradle-rcf1 缓存
- 密钥在 repo .secrets/(rcf1-bridge.token/rcf1-control.token/
  rcf1-rcon.pass),不入库
- 锁定上游=D:\code\mc-experiment\aibot @a029fa6

## 1. 全新源码树(fresh replay)

```bat
git clone D:\code\mc-experiment\aibot D:\mc-rcf1-r3c-replay\src
cd /d D:\mc-rcf1-r3c-replay\src
git checkout a029fa6a
git apply D:\code\mc-bot\aibot-dsh-m0\upstream-patches\r3-base-tree.patch
python D:\code\mc-bot\tools\rcf1_sync_overlay.py --build D:\mc-rcf1-r3c-replay\src
gradlew.bat remapJar --no-daemon -g D:\code\mc-experiment\.gradle-rcf1
```

(等价脚本:D:\code\mc-experiment\rcf1-r3c-replay-build.bat)

## 2. 全新部署目录

```bat
robocopy D:\code\mc-experiment\rcf1-server D:\code\mc-experiment\rcf1-server-r3c /E /XD logs crash-reports
del D:\code\mc-experiment\rcf1-server-r3c\world_rcf1\aibot\external-body-bob.journal
robocopy D:\code\mc-experiment\rcf1-client D:\code\mc-experiment\rcf1-client-r3c /E /XD logs debug baritone downloads cache
copy D:\mc-rcf1-r3c-replay\src\build\libs\aibot-0.0.1.jar D:\code\mc-experiment\rcf1-server-r3c\mods\
copy D:\mc-rcf1-r3c-replay\src\build\libs\aibot-0.0.1.jar D:\code\mc-experiment\rcf1-client-r3c\mods\
```

世界来源:rcf1-server/world_rcf1 的副本(journal 重置);身份核验:
observe 的 client_mod_jar_sha256 == d6ce2375…

## 3. 环境起停(唯一生命周期入口)

```bat
set RCF1_ROOT=D:\code\mc-experiment
set RCF1_RAW=D:\mc-rcf1-raw-r3c
set RCF1_LIFECYCLE_NS_ROOT=D:\mc-rcf1-raw-r3c\lifecycle
python tools\rcf1_lifecycle.py start server
python tools\rcf1_lifecycle.py start client
:: ... 全部正式矩阵 ...
python tools\rcf1_lifecycle.py stop all
```

## 4. 正式矩阵(全部在 fresh 部署执行)

```bat
python tools\rcf1_tests_ia.py            :: IA 28/29(I08 阻断)
python tools\rcf1_tests_c.py             :: C 5/5 LIVE
python tools\rcf1_tests_n.py             :: N 18/18
python tools\rcf1_tests_v.py             :: V10 离线 7/7
python tools\rcf1_lifecycle_fake_tests.py :: L 20/20(独立假命名空间)
python tools\rcf1_g4.py                  :: G4 五连
python tools\rcf1_mutations.py generate %RCF1_RAW%\ia-facts-r3c.json %RCF1_RAW%\g4-runs-r3.json %RCF1_RAW%\mutations-r3c
python tools\rcf1_mutations.py run %RCF1_RAW%\mutations-r3c
```

## 5. 最终判定(同一 CLI)

```bat
python tools\rcf1_checker.py judge-ia  D:\mc-rcf1-raw-r3c\ia-facts-r3c.json
python tools\rcf1_checker.py judge-g4  D:\mc-rcf1-raw-r3c\g4-runs-r3.json
python tools\rcf1_checker.py judge-all D:\mc-rcf1-raw-r3c\judge-all-manifest.json
```

期望:judge-g4 accept=true;judge-ia 拒于 I08 专属原因;
judge-all 7/11(g4/C/N/V/L/build/fresh 过;ia/i08/g5×2 如实拒)。

## 6. G5 复跑入口(解除阻断后)

```bat
python tools\rcf1_g5.py prep <run_id> s01
python tools\rcf1_g5.py act <run_id> goto "{...}" --note "..."
python tools\rcf1_g5_drive.py mine <run_id> --block minecraft:oak_log --near 92 94 -69 --need 5 --note "..."
python tools\rcf1_g5.py night <run_id>
python tools\rcf1_g5.py verify-morning <run_id>
python tools\rcf1_checker.py judge-g5 <g5r3-<run_id>.jsonl> s01
```

阻断解除条件(任一):
- 采伐链提速(见 RESULT §G5:goto-face 周期 ~30-60s/根 → 目标 <60s/根)
- 或场景声明改为贴近现成树木的开阔站位(基点坑洞已证感知死区)
