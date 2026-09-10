# LIVE-2A0-5｜DSH actual load（实机 PASS）

## 加载方式
真实 `pnpm dsh web --patch D:\code\mc-experiment\dsh\scratch-aibot-body\cordis.yml`
（deepseek-harness@5dda764 工作副本），环境含隔离服 AIBOT_BRIDGE_TOKEN/URL。
模型 `deepseek/deepseek-v4.1-flash`（Command Code Goat 网关；验收中完成的默认模型切换）。

## 工具计数（DSH-2）
`plugin.test.mjs` 断言 `tools.size === 25`（22 → 25，交付副本与 dsh 仓同步，37/37 node tests 通过）；
会话中 25 个 `mc_` 工具全部注册（register 循环 + 3 个认知工具）。

## 真实调用（session-5ec76c76，v4.1-flash 单回合四步，原始 JSON 见 dsh-session-2a0.jsonl）
```text
tool/call mc_connect {} 
  → {"attached":true,"owner":"dsh-session-3601d177…","lease_epoch":"0500f326…:3","event_sequence":561,…}
tool/call mc_view {}
  → {"schema":"mc.cognitive_view.v0","meta":{"generated_server_tick":37048,
     "scene_hash":"sha256:6ee3f33bf466a8cf539a6287e20a1521882634e42e36bd854fe7e6a08bfbba54",
     "encoded_bytes":10876},"scene":{"environment":{"day_phase":"DAY","local_light":14,…},
     "execution":{"current_task":"gather","progress_bucket":100,…},…}}
tool/call mc_inspect {"ref":"mc://2eadb4ef-…/minecraft%3Aoverworld/structure/digwall"}
  → {"schema":"mc.evidence.v0","ref":"…structure/digwall","detail":"baseline",
     "evidence":{"baseline_cells":0,"block_histogram":{},"missing_sample":[],
     "missing_sample_truncated":false,"missing_sample_unavailable_reason":"home_repair_plan_unavailable",
     "object_id":"digwall","wrong_count":0}}
tool/call mc_inspect_local {"radius":8,"detail":"blocks"}
  → {"detail":"blocks","observation_boundary":"same_strict_survival_raycast_as_perception_no_hidden_scan",
     "perception_policy_radius":8,"radius_effective":8,"radius_requested":8,
     "schema":"mc.local_view.v0",…}
```

LLM 选择了 view 中第一个 structure 卡片（digwall，未 capture 的 HOME → baseline 0 +
typed 的 `home_repair_plan_unavailable` 原因——语义正确）。以上为工具原始返回，
不采信 LLM 对结果的任何自述；正确性判定全部基于 JSON 字段本身。

## 过程记录（非阻断）
- 首次尝试时 dsh 进程环境携带的是正式服 token，mc_connect 被桥 401 拒绝
  （该轮会话 jsonl 同存档：工具已注册、桥联通，token 配置错误被正确 fail-closed）；
  用隔离服 bridge-token.txt 重启 dsh 后本轮四步全部成功。
- 证据：dsh-session-2a0.jsonl（完整回合）、dsh-session-tools.png（会话截图）、
  dsh-web-2a0.log（进程启动日志）。

结论：真实 DSH 插件加载 25 个 mc_ 工具（若只新增三项则为 25，符合预期），
三个新工具均被真实调用且返回真实认知视图数据。
