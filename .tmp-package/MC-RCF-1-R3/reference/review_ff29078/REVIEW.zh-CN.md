# MC-RCF-1-R2 独立审查：ff29078

## 结论与范围

仓库：ceyirelehe47/mc-bot。分支：experiment/rc-foundation-v1。
审查 HEAD：ff29078ab3cd52d6cfceb928344c90e0c25ab2a7。
Agent 报告源码候选 C：304da18010fa8e1ad1faa2442225ba00d59258d9。
独立结论：**PARTIAL — 存在阻断项，不接受“仅 G5 未完成，其余门全部通过”。**

本轮确有实质进展：移除了 sweepRegister 直接注册旁路，进食准备阶段贯穿已修；已有受控场景核心链事件记录。不能把未充分证明等同于实际游戏必然没有发生，但当前记录与判定器不够支持正式整门 PASS。

本审查没有操作用户机器，没有运行 Minecraft、Fabric、GameTest、G4/G5。实际运行了：
- byte-identical tools/rcf1_checker.py 的 CLI：7 个合成无效证据均误接受；3 个对照错误均正确拒绝。
- byte-identical RealClientEatDecisionCore.java：Java 21 编译并注入合成观察，复现外部移走食物被报告为完成消费。
两个生产文件的 Git blob 均与 GitHub connector 返回值逐字节一致。PROVENANCE.json 记录来源和哈希。

## F01 — Checker 仍用声明代替物理事实（阻断）

生产入口：tools/rcf1_checker.py。
judge_ia 新增必测 ID、候选注册表和 reason 数字一致性检查，这些是有效改进。但它仍可接受：
1. 所有 ID 齐全，正例仅写 server_authoritative，负例只写两个 true；没有原始物理事实。
2. reason 内部算术一致，但请求是新增 32，附带前后库存完全未变。
3. 同名 case 先出现 outcome_unknown，再被后面的同 ID 正例覆盖。
4. runs 注册表不包含候选身份。

judge_g4 可以接受：
5. 五个不同 run/session 字符串和 nonce-only events，没有采集/合成动作。
6. 事件明确写 mine failed、cursor 持有石镐，但 chain 声称全部 true。
7. 初始库存已经有两把镐，结束库存完全相同，但 chain 声称空包采集。

以上输入全部是审查方生成的合成反例，不是对 Agent 实际运行证据的伪造指控。每个输入单独经过真实 CLI；结果及返回码在 checker_counterexamples.json。

修复方向：以实际事件和前后快照产生单用例结论；主入口检查过程覆盖、对象/会话/候选绑定、因果顺序与数量，不能通过增加布尔字段或仅检测某个 reason 单词关闭。重复 ID、缺身份和矛盾事实须明确拒绝。保留完整有效正例防止全拒绝器。

## F02 — G4 摘要包含未测即真的字段（阻断正式 G4 验收）

入口：tools/rcf1_g4.py run_core/main/_result_of。
当前 runner 在结束时直接写：

    chain.chain["cursor_empty"] = True
    chain.chain["no_unresolved_unknown"] = True

candidate 在 main 中为硬编码 SHA，session_id 是 run_id 加当前秒数的字符串，不是已核验的 Minecraft/control session 身份。events 保存的是截断至 80/90 字符的回执，缺执行 ID、机会 incarnation 和对应原始帧/槽位快照。不是说这些摘要完全无用，而是它们不能独立证明全部最终条件。

提交的 g4-runs.json 有采木、合成、放置、采石和最终库存的连续摘要。这比上轮仅预给材料的动作测试有明显推进。但在 F01/F02 修复前，不能把五条 PASS 字段当成正式五次完整验收。

修复方向：补实际 cursor/执行账本终态，记录原始完整回执和动作身份，绑定运行时加载产物。事实不足的项目实跑，不能事后填 true。

## F03 — G5 路径含 armed 后管理员传送（阻断）

入口：tools/rcf1_g5.py run_survival。
脚本在白天已完成采集、合成与 dig_in 后，用 RCON tp 把 Bob 送进指定洞格；到黄昏后又执行一次 tp。这不是准备期出生设置，而是将原本待测的正常入洞过程用管理员动作完成。与 R2 自然过夜约束不符。即使以后 seal 成功，该脚本也不能直接计为合法 G5。

另外：
- observe_shelter_facts 使用管理员方块查询决定下一业务动作，并将 inside_enclosure 直接设为 True。
- 对查询返回不含 passed 的情况，头顶默认为 solid，四周不增加 open_sides；空/错误回应可能被当作封闭。
- morning 进度丢失条件使用 wooden_pickaxe < 0 AND oak_planks < 1；正常非负库存（包括空包）不会进入该失败分支。
- S01/S02 的命令行入口使用同一 AREAS["default"]；当前只有一个准备装置。

Agent 如实报告 G5 BLOCKED，未把失败夜晚算通过。但“只差修洞内放置”不完整：必须先移除测试期特权推进，修正观察/进度/场景设计，再检验放置动作。

## F04 — 主包进食贯穿已修，消费归因仍误接受（已离线复现）

被测：RealClientEatDecisionCore（生产模块，实际客户端调用它），以及服务端 eatSnapshot 的数字条件。
确认修复：主包唯一食物时第一步为 PICKUP_MAIN，phase=0，未提前完成。
反例时间线：手持食物 2 件 → 开始使用 → 合成观察模拟外部清除两件、停止使用 → 核心返回 COMPLETE / client_consumed=2。没有给核心任何正常吃完的独立事件。
服务端仍按 completed、claimed>0、after==before-claimed 判断。before=2/after=0/claimed=2 满足该表达式，不能据此区分正常进食与外部清物。

日志：eat_core_probe.txt。Java 代码：EatCoreProbe.java。
这是生产纯核心的确定性反例，不是 Minecraft LIVE 复现；真正客户端、服务器网络路径仍需补正常使用完成的归因测试。

## F05 — 读接口仍可能写账本，局部机会未限定范围（静态确认）

入口：RealClientBodyBackend.inspectLocalJson → tracker.opportunities。
observe 直接调用已从读接口移除，sweep 出生旁路已删除，方向正确。然而 opportunities() 遍历缓存坐标查询服务器实际方块，发现变化就 markStale、journal.append。故 inspect 间接仍可修改持久账本。列表仅按维度筛选，没有应用 local 的 radius 和数量上限。

这不等于已复现穿墙采集；具体问题是读/写职责和可见性/查询界限仍未关闭。应将经过授权的失效处理放到明确后台写入流程，并让只读查询返回有界、来源清晰的视图；不利用远方不可见世界状态向模型泄漏新事实。

## F06 — 生命周期 stop 在已有记录时未检查额外实例（静态确认）

入口：tools/rcf1_lifecycle.py stop。
无记录分支已新增命名空间扫描、可归属接管/不可归属 BLOCKED；按角色失败预算也是有效改进。
但已有 marker/PID 时，stop 直接按该 marker 找到并停止一个实例，没有在返回前核验同角色额外实例。故不能仅凭 stop 回执证明“本轮所有实例为零”。需分别测有记录+额外实例、无记录孤儿，并在结束时读取全量事实。

不声称此刻用户机器上有额外进程；本审查没有进程访问权限。

## F07 — 交付与 replay 的实际支持范围不足

RESULT 同时出现“完整 C/N/I/A/L/V PASS”与 IA23/26、I08 BLOCKED、C08 NOT_RUN；覆盖账也列出等价覆盖而未提供完整对应证据。应按完整原子条件重新结算，不以行标题 PASS 吞掉缺项。

本次 live/ 目录的 ia-group-latest.json 与旧 ia-group-20260921-022419.json 同 Git blob（481815e7ba5f91463778fe2f9abe0dcffbca4dca）；它不是所述的新 23/26 结果。目录未提供报告引用的 g5-s01.json。

replay-build.log 确实记录了 compile/remapJar/test 和 BUILD SUCCESSFUL；不否认这份构建成果。但 REPRODUCE 描述 robocopy 现有构建树（排除 build/.gradle）到新目录，不是从锁定上游+C经installer生成；运行部署目录也被报告承认复用。新 replay JAR 哈希仅给出前缀，不能直接接受“只是时间戳不同”的未附字节比较解释。这只证明有限复制构建/测试，不等于任务要求的 fresh replay 完成。

## 优先顺序（不是新增功能任务包）

保留已有核心链与修复；不重选后端、不新增时代脚本。
1. 将 F01 的事实判定与 F02 的实际采集交给独立验证路径，使用真实证据重算；缺证据再跑。
2. 实现侧关闭 F03 的特权推进和错误安全判断、F04 的消费归因，同时补 F05/F06 明确缺口。
3. 固定可追溯候选，补齐未测原子条件、合法 G4/G5、从权威源码与锁定上游 fresh replay。

本轮定位：**受控核心链有进展，正式完整验收与自然生存仍未成立。**

## 来源入口

所有以下路径均按同一 HEAD 读取：
- deliveries/mc-rcf1-r2/RESULT.zh-CN.md、BASELINE.md、COVERAGE.md、REPRODUCE.md
- deliveries/mc-rcf1-r2/live/g4-runs.json，以及 live/ 和 audit/ 的完整 Git tree
- tools/rcf1_checker.py、rcf1_g4.py、rcf1_g5.py、rcf1_shelter.py、rcf1_lifecycle.py
- aibot-dsh-m0/aibot-overlay/src/main/java/io/github/zoyluo/aibot/external/realclient/RealClientEatDecisionCore.java
- 同目录 RealClientExecutionDriver.java、RealClientOpportunityTracker.java、RealClientBodyBackend.java
- aibot-dsh-m0/aibot-overlay/src/client/java/io/github/zoyluo/aibot/client/realclient/RealClientActionController.java

当前依据：已提供的 MC-RCF-1-R2_TASKBOOK.md 与其中保留的原完整验收矩阵。旧 0.8 handover 仅作持久边界参考，不作本轮进度/文件白名单/固定计数依据。
