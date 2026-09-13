# 自动化测试计数汇总（MC-2A0.4A 最终）

| 套件 | 门槛 | 实测 | 证据原件 |
|---|---|---|---|
| JUnit dev round1 | ≥403 / 0 | **415 / 0 / 0** | junit-round1.log + junit-xml/ |
| JUnit dev round2（同生产字节） | ≥403 / 0 | **415 / 0 / 0** | junit-round2.log |
| JUnit clean replay | ≥403 / 0 | **415 / 0 / 0** | junit-clean-replay.log |
| GameTest round1（dev） | ≥638 / 0 | **643 / 0** | gametest-round1.log |
| GameTest round2（同生产字节） | ≥638 / 0 | **643 / 0** | gametest-round2.log + gametest-xml-round2/ |
| GameTest round3（clean replay） | ≥638 / 0 | **643 / 0** | gametest-round3-clean-replay.log + gametest-xml-round3/ |
| BridgeCore | ≥105 | **105 PASS** | bridgecore.txt |
| Node | ≥43 / 0 | **43 / 0** | node.txt |
| Installer | ≥11 | **11 OK** | installer.txt |
| Supervisor Python | ≥9 / 0 | **9 OK** | supervisor.txt |
| DSH tools | exactly 29 | **29**（12 static + 14 operations + 3 control） | 06-audit/dsh-tools.json |
| Clean upstream replay | byte zero diff | **src 树 0 diff** | clean-replay-zero-diff.txt（diff -rq，空输出） |

GameTest 计数从 638→643：新增 5 个 MC2A04 同帧传感器测试（stale/位置漂移/视线错拍/伪报块/无会话）+ 主测试内嵌重复帧断言。
JUnit 从 403→415：RealClientWireSessionTest 9 项 socket 契约 + 源码契约 3 项新锚（game-session、同帧重建、wire v2 fail-closed）。

## Stage A 基线环境差异记录（保留首次失败证据）

- 基线 JUnit 403/0（stageA-baseline-junit.log，首次即绿）。
- 基线 GameTest 前两轮各 31 失败（stageA-baseline-gametest-envfail.log / -r2-）：**环境变量错值**——GameTest 契约要求 `AIBOT_EXTERNAL_BOT=Mc1caBot`（reserved 名单匹配，见 MC2A0/MC2A01 测试源码注释"run with AIBOT_EXTERNAL_BOT=Mc1caBot"），首轮误用 `Bob` 导致 31 个 Mc1caBot 系 fixture 测试 fail closed。两轮失败集逐名对比 100% 一致（31/31 交集），确定性环境差异，非代码回归。
- 第三轮清场复跑仍 31（同为错值）；改用正确值后 **638/0 全绿**（stageA-baseline-gametest-r4-pass.log）。

## 运行命令（均为实际执行）

```bash
# JUnit / GameTest（dev 树 D:\code\mc-experiment\aibot，34fb49f installer 应用 + 本轮 overlay）
export JAVA_HOME=D:/mc-server/jdk-21.0.12.1+1
./gradlew test --console=plain                                        # exit 0
AIBOT_EXTERNAL_BOT=Mc1caBot AIBOT_BRIDGE_TOKEN=<token> ./gradlew runGameTest --console=plain   # exit 0

# clean replay（冻结上游 a029fa6 重新 clone + 同一 installer 应用）
git -c core.autocrlf=false clone .../aibot aibot-replay && git checkout a029fa6
python aibot-dsh-m0/scripts/apply_to_aibot.py --repo aibot-replay --apply   # APPLIED
diff -rq aibot/src aibot-replay/src                                          # 0 行输出 = byte-for-byte

# 离线四套件（aibot-dsh-m0）
javac -d .build <core 9 文件> && java -cp .build ...BridgeCoreTest   # 105 PASS
node --test dsh-plugin/test/*.test.mjs                               # 43/0
python scripts/test_installers.py                                    # 11 OK
python scripts/test_real_client_supervisor.py                        # 9 OK
```
