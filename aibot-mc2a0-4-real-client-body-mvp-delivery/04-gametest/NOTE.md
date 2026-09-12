# GameTest 计数(applier 原版代码 vs 最终修正版两轮全绿)

首轮(applier 应用后,lookAt 修复已含):
- round1(dev): 638 tests / 0 failures  token gametest-round1-token-20260913-4f7b2c9e
- round2(dev): 638 / 0                  token gametest-round2-token-20260913-8e3d1f6a
- replay:      638 / 0                  token gametest-replay-token-20260913-c5a9b2d4
- (build 内嵌第 4 跑: 638/0)

修正版复验(tracker 诊断+client auto-join 后,即最终生产字节):
- round1(dev): 638 / 0   token reverify-r1-token-20260913-k4m8x2p7
- round2(dev): 638 / 0   token reverify-r2-token-20260913-q7w3e9r1
- replay:      638 / 0   token reverify-replay-token-20260913-z9x5c8v2
- XML:round2 与 replay 原件在本目录;round1 XML 被后续运行覆盖,统计来自运行时解析输出
新增 GameTest:mc2a04BirthReplayAndTerminalReappearanceUseCorrectIncarnations(637→638)
