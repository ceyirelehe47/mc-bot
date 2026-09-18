# MC-2A0 交付环境与构成口径

## 精确基线
- repo: ceyirelehe47/mc-bot
- base: `d3699fb3b0b6707984aef8ca5d6479dc5f778b1a`（experiment/mc1ca-structure-semantics，R2.1 已验收冻结点）
- 目标分支: `experiment/mc2a0-cognitive-view`（从 base 直接创建，无 merge/reset）
- AIBot 冻结上游: `zoyluoblue/mc_aiplayer@a029fa6a3760fd0f83834c104051b041d986da60`
- DSH 冻结基线: `deepseek-ai/deepseek-harness@5dda764`（scratch-aibot-body 插件为工作副本）

## 开发/验证环境
- Windows 10 (win32 10.0.26200)、Git Bash、JDK 21（D:\mc-server\jdk-21.0.12.1+1，JAVA_HOME）
- Node v22.19.0（node --test）、Python 3.13（installer/驱动脚本）
- 开发工作区: D:\code\mc-experiment\aibot（a029fa6 + R2/R2.1/MC-2A0 应用态，未提交为正常交付形态）
- 重放工作区: D:\code\mc-experiment\aibot-replay-2a0（clone a029fa6 → installer --apply，与开发工作区逐文件 0 diff）
- 隔离实机服: D:\code\mc-experiment\mc-server-mc1ca（level=world_r2 复用，25565/RCON 25575/桥 8765，
  token 只存服务器目录 bridge-token.txt，未入库）

## 交付构成口径
- `01-diff/`：installer 生成的 patch.diff 基线 + replay-verify.log（48 文件 0 diff）+
  replay-compile-test.log / replay-gametest.log（重放产物全套测试）
- `02-build/`：gradle clean test（JUnit 357/0 汇总）+ node-tests.log（37/37，交付副本 dsh-plugin 目录运行）
- `03-gametest/`：最终状态后重新收集的两轮完整 run1/run2（XML+gradle log，各 623 tests / 0 fail；
  此前一轮证据被后续 `gradle clean` 清除，已按最终源码状态重跑生成，未沿用旧文件）
- `04-live/`：LIVE-2A0-1..5 各自的验收记录 + server-2a0.log（隔离服全程日志）
- `05-audit/`：view-read-surface / hidden-observation-review / mutation-diff-review
- `RESULT.zh-CN.md`：23 项必答

## 密钥纪律
COMMANDCODE_API_KEY 与 AIBOT_BRIDGE_TOKEN 均不入库：交付物中仅出现脱敏引用；
桥 token 只存在于隔离服本地 bridge-token.txt 与进程环境。
