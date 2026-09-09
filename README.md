# mc-bot：Minecraft + AI 同伴实验工坊

本地单人玩法环境下"给 Minecraft 找一个靠谱 AI 同伴"的系列实验仓库。每一代方案完整保留：安装、踩坑、根因分析与清理的全过程都在对应目录里。

## 当前世代：AIBot 外接大脑（aibot-dsh-m0）

**AIBot 当身体，DeepSeek Harness（DSH）当大脑**——服务端 Fabric mod 生成真实玩家实体 Bob，高层决策完全交给外部 DSH Agent，通过仅回环的 HTTP 桥（控制/查询 + 独立长轮询事件通道）连接。LLM 走 Command Code Goat 网关的 `deepseek/deepseek-v4-flash`。

```
DSH Agent (web UI / 游戏内聊天)
   ↕ 16 个 mc_* 原生工具 + 事件驱动回合
aibot-body 插件 (scratch overlay)
   ↕ HTTP 127.0.0.1:8765 (Bearer + 租约 + 幂等)
BridgeKernel / BridgeJournal (仅游戏线程)
   ↕
AIBot Task 状态机 + SAFETY 安全网 (1.21.3)
```

- `aibot-dsh-m0/` — 源码包：Java 桥内核（9 文件）+ 上游精确锚点补丁安装器（11 文件条目）+ DSH 插件（mjs/ts）+ 离线测试套件 + 交接文档
- `aibot-dsh-m0-delivery/` — M0 交付证据：基线/补丁构建日志、实机验收报告、DSH 会话 JSONL、服务器日志、crash 事故记录、总结论（VERIFIED/FAILED/NOT_RUN 逐项）

### 本代关键产出

1. **全链路打通**：冻结上游（aibot@a029fa6 + dsh@5dda764）真实编译、插件真实加载、实机验收（采原木→合成链全程独立背包核对）。
2. **修复交接包原始缺陷**：上游夜间自动任务撞外部护栏抛未捕获异常、整服崩溃且夜间重启必崩 → 精准修复（仅 4 个后台维持方法对保留身体短路，SAFETY 路径全放行），实战验证（Bob 死亡重生后自动躲苦力怕）。
3. **Windows 兼容修复**：测试竞态 + `kill('SIGTERM')` 不杀子进程的泄漏。
4. **游戏内直聊**：免 @ 聊天直达 DSH、回复广播聊天栏（用户实玩验证到石器时代）。

### 快速上手（本机复现）

```bash
# 身体侧:D:\code\mc-experiment\mc-server-aibot( mods: aibot jar + fabric-api + appleskin + vein_miner )
#   start.bat 设 AIBOT_EXTERNAL_BOT=Bob / AIBOT_BRIDGE_PORT=8765 / AIBOT_BRIDGE_TOKEN=<32+位随机>
# 大脑侧:D:\code\mc-experiment\dsh
#   pnpm install && pnpm run build
#   python3 <本包>/scripts/install_dsh_plugin.py --repo <dsh>
#   AIBOT_BRIDGE_TOKEN=<同值> AIBOT_BRIDGE_URL=http://127.0.0.1:8765 pnpm dsh web --patch <dsh>/scratch-aibot-body/cordis.yml
# LLM:~/.dsh/settings.yaml 配 llm-pi-ai providers.commandcode(baseURL/apiKeyEnv/models)
# 玩法:游戏聊天框直接说话;或 DSH web UI(http://127.0.0.1:3080)
```

## 历史世代（已归档认知）

- **mindcraft**（Node 外挂 bot Andy）：`!newAction` 让 LLM 写 JS 技能；Command Code 网关接入需修 `prompter.js` 按名路由缺陷。
- **mc_aiplayer**（服务端 mod）：LLM 仅规划 + 确定性状态机；面板聊天广播与免 @ 旁听是其源码改造点。**aibot-dsh-m0 的身体正是它的冻结 fork**。
- **MineAgent**：事件打断机制导致推理 loop 静默死亡（"一晚上不回话"根因），已移除。

详细部署参数、种子、坑与结论见 `aibot-dsh-m0-delivery/04-live-acceptance/LIVE-ACCEPTANCE-REPORT.zh-CN.md` 与 `aibot-dsh-m0/docs/`。
