# 自动化测试计数汇总（MC-2A0.5 最终）

| 套件 | 门槛 | 实测 | 证据 |
|---|---|---|---|
| JUnit dev round1 | ≥425 / 0 | **425 / 0 / 0** | junit-round1.log + junit-xml/ |
| JUnit dev round2 | ≥425 / 0 | **425 / 0 / 0** | junit-round2.log |
| JUnit clean replay | ≥425 / 0 | **425 / 0 / 0** | junit-clean-replay.log |
| GameTest round1（dev） | ≥643 / 0 | **643 / 0** | gametest-round1.log |
| GameTest round2（同生产字节） | ≥643 / 0 | **643 / 0** | gametest-round2.log + gametest-xml-round2/ |
| GameTest round3（clean replay） | ≥643 / 0 | **643 / 0** | gametest-round3-clean-replay.log |
| BridgeCore | ≥105 | **105 PASS** | bridgecore.txt |
| Node | ≥43 / 0 | **43 / 0** | node.txt |
| Installer | ≥11 | **11 OK** | installer.txt |
| Supervisor Python | ≥11 / 0 | **11 OK**（9+2 window_mode） | supervisor.txt |
| DSH tools | exactly 29 | **29**（未触碰 DSH 插件） | 08-audit/ |
| clean replay | byte zero diff | **src 0 diff** | clean-replay-zero-diff.txt |

JUnit 415→425：RealClientScreenWireTest 8 + RealClientBackgroundScreenSourceTest 5 −（重排）合计 +10。
GameTest 维持 643（MC2A04 tracker 契约内更新，计数不变）。

客户端实际构建：DLI 直调 java 客户端全程运行（LIVE A-F），mixin 注入日志见 03-live-input-isolation/client-full.log 对应 debug 日志（Mixing client.RealClientMouseMixin into net.minecraft.client.Mouse / RealClientKeyboardMixin）。

## 本轮修正清单（RESULT §2 素材）

1. **applier 锚点消歧**（任务包 PATCH_VALIDATION 自述未在完整 checkout 应用过）：`real_client_supervisor.py` 的锚 `'        "minecraft_server": minecraft_server,\n'` 在文件中出现 2 次（variables dict 与 launch dict），count=1 断言失败。修正=锚扩展为带 `if args.print_launch:` 下文的唯一片段（语义目标不变：launch dict 输出加 window_mode）。CHECK 后 APPLIED 21 文件。
2. **源码契约空格修正**（TASKBOOK §3 允许的"semantically identical formatting"）：供给 driver 统一无空格排版 `if(currentState.isAir()&&current>baseline)`，RealClientMvpSourceTest 旧锚含空格失配——锚同步为无空格版（2 处）。
3. **installer mixins.json 合并修正**（SOURCE_MAP §Installer behavior 预告的最小注册修正）：上游 a029fa6 已跟踪 `src/main/resources/aibot.mixins.json`，整文件新增机制 REFUSED。修正=apply_to_aibot.py 对该路径特判合并 client 数组（保序去重），其余冲突仍拒绝；test_installers 11 项仍全绿；replay 与 dev 树字节一致（0 diff）。
4. Yarn 映射修正：**零**（供给实现经映射预审后一次编译通过；clickSlot(syncId,slotId,button,SlotActionType,player) 参数序、Mouse/Keyboard 目标方法描述符、ScreenHandler.syncId public final 字段等全部正确）。
