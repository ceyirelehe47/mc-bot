# MC-RCF-1-R1 修复台账

每项:源缺陷→根因→修复→验证。失败尝试与修复次数如实记录(全轮 ≥10 次
构建重试,主因:替换脚本断言失败静默丢写、括号笔误×2、fixture 相互污染)。

| # | 源缺陷(审查/实测) | 根因 | 修复 | 验证 |
|---|---|---|---|---|
| 1 | _spawn 崩溃窗口留孤儿 | Popen 先于状态落盘 | intents/<role>.json 原子预写+全命名空间扫描接管/阻断 | L03a/b pass |
| 2 | 状态损坏吞成空环境 | _load_json 异常回退 default | _load_json_strict 上抛 CorruptStateError | L04×4 pass |
| 3 | STOP_FAILED 被 start 复活 | 状态判定缺分支 | 显式拒绝+记失败 | L07 pass |
| 4 | stop 清预算 | 尾部无条件 _record_success | 删除;预算仅 start 成功清 | L08a pass |
| 5 | 假测试动真实状态 | 共享模块常量 | NS_ROOT 重定向+rcf1fake- 前缀隔离 | L10 pass |
| 6 | scan 扫不到实例 | 正则前缀漏尾'-'(最小复现定位) | re.escape(pref) 完整前缀 | L03 由 fail→pass |
| 7 | cancel 只清键不停 Baritone | 收尾无导航 stop | finishAction 六出口统一 | C02 pass |
| 8 | 约束一次生效 | constrained 布尔短路 | 每次重设+读回核验 | 代码审查+C 组 |
| 9 | !pathing=到达 | 两态混同 | atGoal 三维+atStance | N 组 18/18 |
| 10 | 组件检查动作名错 | "mine"≠"mine_opportunity" | 对齐实际下发名 | 代码+C 组 |
| 11 | craft delta>=batches | 单位错(批次≠物品) | min(want,fullOutput) 门 | V01 pass |
| 12 | place 无消耗门 | 只验块类型 | consumed=true+unattributed | A06/A07b pass |
| 13 | eat 布尔冒充 | after<before 含外部 | claimed 轮次+窗口起点 | V04 pass |
| 14 | move 最终≥count | 非增量语义 | dest_baseline+count 净增 | I02/V02 pass |
| 15 | 容器错屏复用 | 同类屏 exact 通过 | 先关旧屏+ownScreenPending | I04 pass |
| 16 | withdraw 回错侧 | 余量一律回玩家 | 回源侧 | I04 wdr 净 2 pass |
| 17 | GoalBlock 停邻格死循环 | Baritone 到达语义 | atStance<1.3 判定 | A02/A06 pass |
| 18 | C04 杀空 PID | rcf1_env 旧文件脱钩 | lifecycle 权威 PID+重连 | C04 pass |
| 19 | 双客户端互踢 | C04 旧 bat 直启孤儿 | lifecycle 统一+清孤儿 | C 组 5/5 |
| 20 | 会话翻转租约失效 | epoch 变化瞬拿旧租约 | Session 重试重取 | C01/C03 pass |

失败尝试保留:G4 单次链三轮(热放树不注册机会→自然树→叶机会全 stale:
存量 tracker 刷新缺陷,BLOCKED);A08 三轮(两过一败,flaky 记录)。
