# MC-RCF-1-R2 交付结果

整体判定：**PARTIAL**（READY_FOR_REVIEW 不成立：G5/S 组 BLOCKED；其余门通过）
源码候选 C（完整 SHA）：`304da18010fa8e1ad1faa2442225ba00d59258d9`
证据提交 E：C 之后测试/runner 提交 `81aabc6926d4214ad608645bde1e3515aa672c68`（不改生产行为：测试装置/runner/文档）
审查起点：8626013b4a829900bc001f00349e3984130f7d21。分支：experiment/rc-foundation-v1。
两端实际加载产物：aibot-0.0.1.jar SHA-256 `fcc3e0d2a73eab0ae1dddf53…`（服务端/客户端 mods 一致，见 BASELINE.md）。

## 门与缺口
| 项目 | 状态 | 真实入口/证据 | 还缺什么 |
|---|---|---|---|
| 进程剩余故障与受控启动 | PASS | tools/rcf1_lifecycle.py；假进程 L01-L13 19/19（含 L11 孤儿 BLOCKED/L12 额外实例/L13 跨角色预算） | 实机长跑 60s 段为轮次内多次起停覆盖 |
| awareness/准星/机会边界 | PASS | RealClientOpportunityAdmission 拒绝矩阵(单测)+合法链 LIVE(face→机会出生→mine 全部 server_authoritative) | — |
| 主包进食与全部动作收尾 | PASS | A08 双槽位 completed claimed=1；全退出路径 finishAction(单测钉住)；I06 三阶段守恒 | — |
| 数量/组件/主体归因/Screen | PASS(1 缺口) | craft 完整批次+材料守恒；place 见证(interaction_witnessed)；I03 组件指纹；I04 双向四事务 | I08 Tom's 终端转移 BLOCKED(装置学网络未形成；服务端守恒验证正确拒绝假成功) |
| 主 checker 正反例与真实策略 V10 | PASS | tools/rcf1_checker.py 主入口；历史四反例 4/4 拒绝；自测 18/18；V10 经 rcf1_shelter 真实策略(离线 8/8) | V01-V09 LIVE 注入未单独跑(语义已由 IA 组同类负例覆盖) |
| 完整 C/N/I/A/L/V | PASS(C5 N18 IA23/26) | live/ 目录 JSON；单元 475/475 | C06/C07 以 I06/ledger 等价覆盖；C08 无共存环境如实未计 |
| 非计分诊断核心链 | PASS | 11/11 chain 全真 67.8s | — |
| G4 五次计分 | **PASS 5/5** | g4-runs.json；judge_g4 主入口 accept=true | — |
| G5 两次自然过夜 | **BLOCKED** | g5-s01.json | 洞内 seal place 连续失败(首个成功后续全败)；sheltered 判定 fail-closed 从未冒充；等待黄昏/策略驱动链真实 |
| fresh replay | PASS | D:\mc-rcf1-replay 全新树构建 fd479ceb…；475/475；核心链 11/11 PASS 67.1s；无台 craft+超距 place 负例拒绝 | run 目录复用既有隔离环境(新部署目录未复制,记为偏差) |
| 外部账户/发布安全 | 未确认 | 旧凭证未验证未使用；无 force push；无秘密入库 | 账户轮换由拥有者处理 |

## R01-R08 修复与复测
- R01 checker：judge_ia 必测集+数量重算+负例零效果+runs 注册表；judge_g4 唯一 run/完整链/独立事件链/限时/40位候选 SHA。四历史反例 4/4 拒绝(probe_checker_regressions.py)。V10 调用生产 rcf1_shelter。修复提交 59d7724。
- R02 机会边界：删除 sweepRegister 旁路；observe 全校验前置+准入核心；inspect-local 纯读；惰性剔除/容量淘汰写 durable 回执。单测 RealClientOpportunityAdmissionTest。提交 99d1471。
- R03 进食：EatAction 委托纯核心 RealClientEatDecisionCore；主包 PICKUP 后返回；A08 双根因修复(isUsing 连吃场景以数量减少为主证据+verify 有界等待计数同步)。提交 99d1471/48b7f5d/b3cba48。
- R05 全退出路径：tick 终态转移统一 finishAction；无 player 分支/格式错误/硬超时全收尾；cursor 有界回包+恢复债务；grid 残料回收。提交 99d1471/48b7f5d。
- R06 生命周期：按角色失败预算；stop 无记录扫命名空间(可归属接管/不可归属 BLOCKED)；preflight 额外实例扫描；假命名空间 NS 派生前缀。L01-L13 19/19。提交 8dce906。
- R07 归因：craft 完整批次乘积+材料守恒+同步等待窗口；place 服务器 UseBlockCallback 见证；move_items 源堆叠组件指纹。提交 198ee81/304da18。
- R04 装置：I06 三阶段真实注入+配方折算守恒；I08 真实终端网络(部署后网络未形成,如实 BLOCKED)；A10 先证破坏未拾取再同坐标阻断；N01 真实抬高台阶+轨迹采样；同 Session 查机会(新 Session 抢租约根因)。提交 2810f69/48b7f5d/be3f0b0。
- R08 交付：本目录+live 原始 JSON+BASELINE 完整哈希+REPRODUCE 命令。

## Checker 说明
最终报告入口 = tools/rcf1_checker.py（judge-ia/judge-g4 CLI 与函数同体）。历史四反例经 probe_checker_regressions.py 4/4 拒绝；正例与语义变异 18/18（rcf1_checker_selftest.py，经同一入口）；G4 计分 runs 经 judge_g4 accept=true。V10 调 tools/rcf1_shelter.py（G5 生产策略）。缺 run 注册/重复事件链/数量矛盾均拒绝。

## 实际计分结果
G4 五次（g4-runs.json）：b01/b02 橡木、b03/b04 白桦、b05 布局扰动（空包后真实 move_items 至 hotbar7）；全部 66.9-70.9s 内 11/11 chain 全真；judge_g4=ok。失败尝试保留：b04 首轮 craft 同步竞态（已修：completed 重算等待窗口）、b05 首轮扰动用木重复计数（已修）。
G5：S01 三次尝试失败链保留于 g5-s01.json；黄昏等待(518s 真实)、策略 plan、seal 首格成功后续失败；sheltered=false 未冒充。
fresh replay：全新树构建+核心链+负例（上文表）。

## 停止与续跑
时间窗口 12h 内完成至 G5；G5 BLOCKED 签名=洞内连续 place 失败（每格 25s 超时，reason 见 latest.log），一轮有效修复后仍失败。当前受管实例已在交付后安全停止（lifecycle stop all 核验）。下一最小动作：复现洞内 place 失败原因（站位/支撑面细节），修复后重跑 S01/S02。
最终远端 HEAD 见推送后本文件同目录 HEAD.txt（避免自指提交）。
