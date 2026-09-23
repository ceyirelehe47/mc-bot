# MC-RCF-1-R1 独立审查

审查日期：2026-09-21。仓库：ceyirelehe47/mc-bot。
分支：experiment/rc-foundation-v1。
审查 HEAD：8626013b4a829900bc001f00349e3984130f7d21。
前次审查 HEAD：ecab2f60cc4c6b2f72cd68cb537554de16a0f23b。

## 结论

保留有价值的实现改动；本轮仍是 PARTIAL，存在验收阻断，不接受为完成的 MC-RCF-1-R1。
Agent 的 RESULT 自己也报告 G4 BLOCKED、G5 NOT_RUN、fresh replay NOT_RUN、A08 失败以及 C06/C07/C08 缺项。不能将“本轮停止/交付”解释为“原任务目标全部完成”。

本审查采用 GitHub 固定提交的源码、RESULT、COVERAGE 和交付树；另在 Linux Python 中对原样摘出的判定函数执行反例。未运行 Windows 生命周期实测、Minecraft、Fabric、JUnit 或 GameTest。下述代码路径推导不冒充实机复现，Agent 自报测试计数也不视为独立复测。

## 已确认存在的改进（不等于整个门通过）

- lifecycle 在 Popen 之前原子写入 intent，加入命名空间扫描、损坏状态拒绝和假进程标记分离。
- finishAction 已包含 Baritone.stop，并接入显式 pause/cancel、硬超时和会话丢失等若干出口。
- craft 去掉了旧 delta >= batches 放宽分支。
- move_items 记录并向客户端传送 dest_baseline。
- place 新增库存减少条件；eat 新增客户端消费数与服务器库存对应核对。

## 阻断与重点问题

### R01：checker 不会可靠拒绝无效证据【离线已复现】

文件：tools/rcf1_tests_v.py。

judge_ia 只检查 cases 非空、每项有 id、结果状态在一个集合内，以及 completed 的 reason 包含 server_authoritative 字符串。它不核验完整必测 ID 集、不重算库存数量、不要求实际动作/物品/cursor/运行身份事实。_delta_consistent 虽存在，却没有被 judge_ia 调用；H6 只直接测试这个独立辅助函数。

judge_g4 只要求列表长度为五、candidate 相同、result=PASS、最终物品两把镐、cursor_empty 为真。不检查 run 唯一性、空包初态、采木采石过程、用时、自然动作来源或完整候选哈希。

本审查原样抽取上述两个函数与 _delta_consistent 并运行四项不合格输入，四项均返回 (True, 'ok')：

1. 只有一个 A01，reason 仅为 server_authoritative，没有任何原始事实。
2. before=0、after=8，但声称 delta=32。
3. 本应验证成功合成的 A01 仅返回 failed。
4. 同一 run=1 的最终两把镐记录复制五次，无动作链证据。

附件 checker_counterexamples.json 保存完整输入、预期拒绝和实际返回。
这证明的是判定函数的误接受，不是证明 Agent 实际伪造了这些游戏记录。
V10 也只测试本文件内新建的局部 if/stub，没有调用实际避难策略；不能据此验收 G5。

### R02：以全向扫描直接生成持久机会，混淆准星来源【静态确认】

文件：RealClientOpportunityTracker.java、RealClientBodyBackend.java。

observe 在 crosshairPresent、game-session、frame 去重和位置一致性检查之前，仅依据接收时间新鲜就调用 sweepRegister。该方法用服务器当前位置向多个方向 raycast，然后直接 register -> appendBirth，生成 real_client_opportunity_birth。

这不是单纯补充只读 awareness；它让未由当前真实准星指向的方块进入持久机会池。materialize 和 inspect-local 输出仍标为 client_crosshair_server_validated。

inspectLocalJson 还主动调用 tracker.observe，因此读检查可能触发持久机会写入；输出 note 却仍声称全向 awareness 只读。

扫描使用 LOS，不能仅据此断言已经穿墙透视；已确定的问题是机会出生授权、拒绝校验顺序和来源标记不真实。应保留“全向只读发现 -> 正常接近/转向 -> 新鲜准星 -> 机会出生”的区别，而不是用扫描补偿传感器/工作站位故障。

另外，opportunities() 直接从 active 删除当前方块已改变的条目，但不追加 stale resolution；replay() 在容量满时跳过 birth。持久账本一致性与跨重启行为须重新检查，不能把无回执的删除/截断当成可靠修复。

### R03：EatAction 的主包取物路径会落入错误的收尾分支【静态确定性路径】

文件：RealClientActionController.java，EatAction.tick。

在 phase=0、食物不在手持/快捷栏、cursor 为空且主包有食物时：

1. 点击主包源槽 PICKUP，设置 selectCooldown=3。
2. 该分支末尾没有 return，phase 也没有推进到进食阶段。
3. phase==1 分支被跳过，直接执行函数底部的消费核验。
4. 此时 consumedRounds 和 heldStart 仍是默认零，claimed 不大于零，调用 fail(client_eat_no_effect)。

因此至少存在一条无需假设网络竞态的确定性失败路径。它与 RESULT 报告的错误名一致，但本审查没有证明每次已报告 A08 失败都只由此导致。修复应防止阶段间贯穿，并在取物后等待已核验的槽位/cursor 变化；不能只继续延长 48 tick 窗口。

### R04：覆盖账中的 PASS 仍比测试内容强【静态确认】

文件：tools/rcf1_tests_ia.py、tools/rcf1_tests_n.py、deliveries/mc-rcf1-r1/COVERAGE.md。

- I06 声称三阶段 cursor 注入，实际只固定等待 1.2 秒后 cancel 一次。cursor_after=None，断言是 cancelled 且日志物品数 >=0（默认也是0）。无法证明光标为空、材料守恒、三个注入阶段或恢复归属。
- I08 声称 Tom's deposit 回归，实际没有搭终端、没有发生存取；普通 deposit 返回 failed 即 PASS。该脚本注释自己承认无终端，不能把这一拒绝测试替代正式回归。
- A10 不先证明目标已被破坏及指定掉落存在，只睡4秒后清掉落并要求终态不为 completed；其他无关失败也可能过关。
- 当前 N 测试文件 blob 仍与前轮相同。N01 的台阶仍设置在 P_FLOOR，与平台同高；COVERAGE 却标为已修。修复/通过声明必须与实际装置一致。

这些不是禁止开发 fixture；测试准备和明确负例注入可使用管理员手段。问题是前置条件和后置断言没有证明所命名的场景。

### R05：取消出口只修了一部分【静态确认，未实机复测】

finishAction 已接入多个退出路径，但 Action.complete()/fail() 仍只设标记和发回执；tick 检测 terminal 后只 clearInputs。failMalformedCurrent 也仍用旧清理路径，没有调用导航 stop。

不能仅以显式 cancel 的五项摘要推导所有成功、失败、格式错误、暂停恢复与 cursor 阶段均已安全停止。应检查全退出路径及外围是否确实补偿，并以最终候选实测证明导航和后续点击不继续执行。

### R06：生命周期剩余的状态/预算问题【静态确认，未 Windows 复测】

文件：tools/rcf1_lifecycle.py。

- stop 在无记录时直接报告 STOPPED(no record)，没有调用新的命名空间扫描。state 丢失但存在本轮孤儿进程时，这个停止回执不能证明实际零实例。
- _preflight 在已验证一个记录时提前返回，不再扫描同角色额外实例；不能由一个合法 PID 推导全机配额已满足。
- 失败计数仍是全局单一值；任一新角色 start 成功都会 _record_success 清零。例如 server 失败两次后 client 的独立启动成功，可以清掉 server 的未结失败记录。这没有完整满足“其他角色成功不得重置失败预算”。

intent/锁/严格读取等改进有价值，不应全盘回退；但自报 L 门通过还需这些针对性反例与真实记录。

### R07：动作结果证明仍不完整【静态确认】

- craft 用 min(want, batches*outputPerBatch) 作为下限；这里实际等于 want，未保证已宣布的所有合法批次产出。请求5、每批4、两批应产8时，delta=5仍满足下限；snapshot没有保存并核验材料基线。
- place 增加了 after<before，但没有将成功分支绑定实际本次客户端交互回执。其他主体放同类型块同时另因减少物品的情况，仍不能可靠归因。目标已正确但未观测消耗时的 running 分支在全局超时检查之前返回，需同时核对超时与失败终态处理。
- 这些需要独立后置事实核验；不能通过添加 server_authoritative 文案来补足。

### R08：交付证据与最终候选未闭合【目录和文档确认】

本轮目录的完整递归树只有四份 Markdown，以及两份 LIVE JSON（C/N）。报告提到的本轮 I/A、生命周期、G4 深度调试等原始事实未随该目录提交可审查版本。

RESULT 的源码候选仍写“最后生产提交见 git log”；缺少最终 C、两端实际加载产物、配置/世界/run 的完整绑定。不能仅按文件名或提交时间断言用了错版本，但现有材料也不能证明同一最终候选完成全部回归。

G4五次、G5两次、fresh replay本来就自报未完成，不属于可以靠补写材料关闭的文档缺口。

## 下一接续点（非新功能轮）

1. 保存此候选与原始记录，停止继续扩展机会扫描或时代脚本。
2. 先修 R02 的机会边界和 R03 的确定性进食状态机错误；闭合生命周期、全退出路径和必要物品事实证明。
3. 将不覆盖场景的 PASS 改为 NOT_RUN/FAIL，判定器必须拒绝本审查附带的四类输入，并补完整必测集合与事实断言。
4. 固定可追溯的候选，先跑一次真正完整、正常交互的核心链；成功后再执行原矩阵五次、过夜和fresh replay。单次成功不得冒充最终通过。
5. 不能确认的旧密钥轮换仍由账户拥有者处理；不要测试密钥或在证据中写入凭证。

## 本地反例复现

在本目录运行：

```sh
python run_checker_counterexamples.py
```

无需 Minecraft、GitHub、网络或 Windows。checker_functions_verbatim.py 是仓库判定函数的原样摘取，不是整仓库副本。JSON 标明证据为离线无效输入测试。
