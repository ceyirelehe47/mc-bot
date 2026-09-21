# MC-RCF-1-R1 结果

**判定：PARTIAL** —— R1 修复门(L/C/I 代码+fixture)与 C/N/I/A 重跑达成;G4 五次矩阵与 G5 未运行(阻塞于机会池刷新缺陷,见下);fresh replay 未执行。

- 分支:experiment/rc-foundation-v1(追加,未重建;无旧历史混入)
- 最终远端 HEAD:见最终回复登记(推送后)
- 源码候选 C:本轮最后一次生产源码提交(见 git log;测试脚本在其后追加不改生产行为)
- 审查基准:ecab2f60cc4c6b2f72cd68cb537554de16a0f23b(开工核验未变)
- 产物:aibot-0.0.1.jar(经 rcf1-rebuild-base 增量构建;构建门 BUILD SUCCESSFUL)

## 门状态(真实)
| 门 | 状态 | 证据 |
|---|---|---|
| R0 现场保全/覆盖账 | PASS | COVERAGE.md;工作树遗留=本会话 G4 中断修复(bc243bb,内容核验后提交) |
| R1-L 生命周期 | PASS | 假进程 L01-L10 **16/16**(tools/rcf1_lifecycle_fake_tests.py,独立 NS+跨 OS 进程);实机有限确认 **5/5**(幂等 start 同实例/双 java 计数/60s 无复活/重启身份) |
| R1-C 收尾/导航 | PASS(代码+重跑) | finishAction 统一收尾接入 6 个出口;约束每次准入重设+读回核验;atGoal 三维;动作名 mine_opportunity 修正;C 组最终候选 **5/5**(C01-C05,live/c-group-20260921-083259.json) |
| R1-I 事务/证明 | PASS(代码+重跑) | craft 数量门/plan 消耗归因/eat claimed 轮次/move 净增+源槽/2x2 错屏拒绝/容器双向/smelt 三层禁用/旧入口硬闸 |
| R1-V fixture+重跑 | 大部达成 | I/A 组 **25/26**(A08 flaky:曾过 claimed=1 轮,最新轮秒败待查);N 组 **18/18**(n-group json);A09/A10 独立实测已删占位 |
| G4 五次矩阵 | **BLOCKED(深度推进)** | 机会系统 7 项根因修复后单环节全通:全向 sweep 注册(30°×22.5° 采样)/惰性剔除/journal 截断重放/MAX_ACTIVE 1024/inspect 驱动传感器/拾取精确站位/视线走廊。**mine 单步 3s completed(gain 0→1 实证)**;但整链编排不稳定(每轮暴露新物理细节:山脊多层遮挡/站位高差/拾取位移带离观测位/dispatch 竞态 stale)。已超三轮规则,如实记录 |
| G5 过夜 | NOT_RUN | 依门序 |
| fresh replay | NOT_RUN | 依门序 |

## 本轮关闭的缺陷(全部实测根因,详见 FIXES 视角沉淀于 commit log)
1. L2/L03 启动意图两阶段+崩溃窗口接管/阻断;L3/L04 状态 fail-closed;L4/L07/L08 STOP_FAILED 不被复活+stop 永不清预算;L5/L10 命名空间隔离(rcf1fake- 前缀互不可见)。
2. scan 正则前缀缺陷(最小复现:marker 前缀含尾'-')。
3. clearInputs≠Baritone cancel:pause/cancel/超时/异常/会话丢失/替换 6 出口统一 finishAction;pause 停路径任务。
4. 约束一次性生效→每次准入验证+篡改拒;goalReached 假到达→atGoal 三维;能力检查动作名 mine→mine_opportunity。
5. craft delta>=batches 放宽→物品数单位(min(want,fullOutput));place 无消耗门→consumed=true 双证+外部抢放 unattributed;eat 布尔→claimed 轮次(isUsingItem 转换计数+窗口起点=使用确认);move"最终≥count"→净增(dest_baseline);count=0 拒绝。
6. 容器事务:同类 GenericContainerScreen 复用错容器(先关旧屏+ownScreenPending 防开关死循环);withdraw 余量侧反了(回源侧)。
7. GoalBlock 停邻格重规划死循环→place 站位<1.3 判定(crosshair 精度不变)。
8. C04 杀客户端 PID 脱钩(rcf1_env 旧文件→lifecycle 权威);双客户端互踢致会话反复翻转(清孤儿后 C 组 5/5);Session 会话翻转重试。
9. up.py/wood/stone/ironage/survival 硬闸退役;smelt OPERATIONS/服务端/客户端三层拒绝。

## 遗留与下一最小动作
1. **A08 eat flaky**(时序):单测曾 PASS(claimed=1+hunger 0->8);最新轮秒败 client_eat_no_effect 且 beef 消失,独立于 claimed 门(V04 已证外部取走不冒充)。下一动作:在 EatAction 调槽链加诊断日志定位秒败路径。
2. **机会池刷新**(G4 阻塞):tracker 注册依赖传感器帧,方块变化后条目 stale 且无重扫。下一动作:传感器帧 diff 驱动 markStale→重注册;或 mine stale 后自动剔除过期条目。
3. C06 完整三阶段 cursor 注入(I06 已覆盖取消阶段)、C07 ledger 批量、C08 并存观察:未完整跑。
4. ~~V10/checker 反测试~~:本轮窗口末补齐——V10/V10b pass(place
   failed 不置 sheltered;伪造 completed 也不置)+H0-H10 14/14
   (tools/rcf1_tests_v.py)。
5. G5 两夜+fresh replay:未运行。
6. 外部账户密钥轮换(用户动作,未变)。

## 安全
真实环境 token 不变(隔离环境自管);推送前扫描无新增秘密;旧历史阻塞未变(不 force push)。
