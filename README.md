# mc-bot：Minecraft + AI 同伴实验仓库

**目标：不作弊通关原版 Minecraft（击杀末影龙）。**

当前模式：**LLM 直控**——会话内 AI 直接通过 HTTP 桥（127.0.0.1:8765，Bearer + 租约 + 幂等）操控 AIBot 身体（Bob），边玩边完善 bot 能力。DSH 外接大脑方案已归档（`aibot-dsh-m0/`），不再参与运行。

## 目录结构（本仓库 = 档案 + 工具集）

- `aibot-dsh-m0/` — DSH 世代源码包：桥内核 Java 9 文件 + 上游补丁安装器 + DSH 插件 + 测试套件（已归档，桥协议定义仍权威）
- `deliveries/` — 历代交付证据：mc1ca（DSH 世代）→ mc2a-graph-core（图核）→ mc2a0.x（Real Client 感知/动作，最新至 8r1）
- `taskpacks/` — 任务包与源码 zip（不入库）
- `tools/` — 活工具：`bridge_exec.py`（桥协议样例：lease → executions → poll）、`rcon.py` 等
- `archive/experiment/` — 实验散件按批次归档：mc205 / mc2a0 … mc2a07、graph-core（r1c/r11/r12/r21）、build-logs、dsh、jars、patches、tools-audit
- `.secrets/` — 活密钥（桥 token、API key、RCON 密码；绝不入库）

## 活跃工作区（D:/code/mc-experiment，未迁移，路径被脚本/配置硬编码）

- `aibot/` — 身体 mod 源码（HEAD `a029fa6a`，zoyluoblue/mc_aiplayer 冻结 fork）
- `mc-server-aibot/` — 主服务器（Fabric 1.21.3，`start.bat` 设 `AIBOT_EXTERNAL_BOT=Bob`、桥 8765；token 见 `.secrets/` 与其 `bridge-token.txt`）
- `mc2a07-prod-client/` — Real Client 生产客户端（屏幕级驱动世代）
- `mc2a0x-work/`、`aibot-replay-*` — 各轮 worktree / replay 树
- `dsh/` — DSH 源码（直控路线已弃用，保留备查）

## 桥协议速查（v1，仅 loopback）

```
POST /v1/lease            X-Owner-Id: <owner>            → data.token（控制租约）
GET  /v1/observe          X-Control-Token: <lease>       → 观察（重启对账解锁）
POST /v1/executions/<op>  lease + X-Request-Id + JSON    → data.execution_id
GET  /v1/executions/<id>  lease                          → state/progress/reason
POST /v1/lease/renew      lease（>15s 续租）
DELETE /v1/lease          lease（终态释放）
```

operations（14）：goto / gather / craft / smelt / eat / set_base / deposit / say / register_home / register_farm / tend_farm / capture_home / repair_home / mine_opportunity；控制：pause / resume / cancel。

## 世代史（详见 deliveries/ 各 RESULT.zh-CN.md）

1. mindcraft / mc_aiplayer / MineAgent —— 已归档认知
2. mc1ca —— AIBot 身体 + DSH 大脑，全链路打通
3. mc2a-graph-core —— TaskGraph 持久图核
4. mc2a0.x —— Real Client 身体：后台原生屏幕、屏幕所有权、全向感知（8r1 冻结基线）
