# MC-RCF-1 结果

结论：**PARTIAL — native foundation passed (G0–G3); G4 core-chain in progress, G5 overnight not run**
分支：experiment/rc-foundation-v1
最终远端 HEAD：见 git log(交付时推送)
源码候选 C：本分支 HEAD;证据提交 E：同分支(文档/测试脚本提交,未改生产源码语义)
客户端/服务端产物：aibot-0.0.1.jar(部署双侧同哈希;构建源=rcf1-rebuild-base 干净重建树)
审查 snapshot：61aa6a85296d5722a80fc935bcc6ca53bf8bfec5
实际开发祖先：无父孤儿分支(旧历史含已暴露未轮换密钥,不带入;磁盘保留)

## 门状态
| 门 | 状态 | 证据入口 | 解释 |
|---|---|---|---|
| G0 | PASS | DECISIONS.md G0 节;D:/mc-rcf1-raw/ | 孤儿分支;installer 字节级重建;空目录构建+隔离 smoke(入服/观察/say/重连+新 epoch);基线 605/650 |
| G1 | PASS | D:/mc-rcf1-raw/c-group/c-group-20260920-214823.json | C 组 LIVE 5/5(C01/02/03/04-lite/05);useKey 清理/错误分类/幂等/租约互斥 |
| G2 | PASS | D:/mc-rcf1-raw/n-group/n-group-20260921-001541.json | N 组 18/18(6 场景×3,零世界改动);terrain 三态(true 拒/false 行/缺省零挖零放) |
| G3 | PASS | D:/mc-rcf1-raw/ia-group/ | I/A 组 12/12;原生 2x2/3x3 合成/精确放置/进食/move_items;服务端合成捷径已删除 |
| G4 | IN_PROGRESS(中断) | 本文件"G4 状态" | 链路组件全部就绪并通过 I/A 组;五次矩阵未开始(用户指令交付) |
| G5 | NOT_RUN | - | 依门序未到 |

另:生命周期治理门(用户 2026-09-20 指令)PASS:假进程 8/8+实机验证,唯一入口 rcf1_lifecycle。

## G4 状态(中断点)
已完成准备:expectedItem 石类按真实掉落(cobblestone)修复;pathTo 双模式(GoalNear 接近/GoalBlock 精确,修树冠目标无路径);出生点(8,107,-1)自然橡树资源确认(叶机会→暴露树干→挖干路径验证中)。
未完成:G4 五次矩阵(两橡木/两第二树种/一非默认快捷栏);A09/A10(mine 目标绑定负例)随 G4 验证。
已知风险:测试世界夜晚敌对重生频繁,fixture 需每次设白天+竞技场中心清怪(已在 I/A 组脚本内固化);G5 过夜本身不使用该 fixture。

## 实际结果(已证)
- 导航:Baritone v1.12.0 官方资产(sha256 b3b36aa3…三方核验);allowBreak/allowPlace/allowInventory/chatControl 强制 false;18/18 场景零地形改动;普通场景 5-12s。
- 原生合成:2x2(双木种)/3x3(木镐+石镐)全真实点击链,server_authoritative_native_craft 报增量;无台 3x3 明确拒绝。
- 放置:指定物品+精确格+支撑面语义+正交站位;块身份+库存消耗核验;泥土不误放。
- 进食:物品 id 驱动真实调槽;food 消耗+hunger 上升核验;不饿拒绝。
- 幂等/unknown:C03 同 id 同参重放同执行/异参冲突拒;C04 断线→outcome_unknown→重连对账不复活。
- 回归基线:650 GameTest 605 过 45 败(G0 诚实记录,未修;失败集中在 MC2A02/2A0/2A04 fixture 类,见 BASELINE.md)。

## 变更说明
- 新增:RealClientNavigation(受控 Baritone 适配)/RealClientInventoryOps(槽基址动态);rcf1_lifecycle(唯一生命周期)/rcf1_env(隔离环境)/rcf1_tests_{c,n,ia}(验收);installer REPLACES 机制+build.gradle 锚定。
- 删除:InventoryCrafting(服务端合成捷径,三树+仓库);旧 直线+偏航+挖穿 导航(正式路径)。
- 修复:play.py 错误分类/抢占/useKey 清理;BridgeKernel OPERATIONS 补 place/move_items;表屏槽基址 10/37;点击节流状态机(同步竞态);木种充足选择;wooden_pickaxe 配方 id;eat 连吃/抬头/手持检查。
- 禁用:smelt 保持注册但未在本轮验收(实验代码);旧时代脚本(wood*/stone/ironage/survival/up)停止使用。

## 阻塞与续跑
- 用户动作:远端历史中 DSH 归档密钥(OpenAI sk-/GitHub/Notion)需账户拥有者撤销/轮换;共享历史清理未授权。本地开发不受阻。
- G4 续跑:tools/rcf1_tests_ia.py 已固化 fixture 模式;G4 脚本骨架=同 fixture+五次矩阵;出生点自然树可用;石面需挖 2-3 格泥土暴露(机会系统已支持)。
- 最小复现:见 REPRODUCE.md。

## 复现入口
见 REPRODUCE.md(构建/自动化/LIVE/生命周期/清理)。
