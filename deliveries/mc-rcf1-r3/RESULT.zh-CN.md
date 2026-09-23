# MC-RCF-1-R3 交付结果

整体判定:**PARTIAL**(G4 正式验收成立;G5 仍 BLOCKED;IA 事实采集
部分缺口;C/N LIVE 与 I08 未重跑)

- 源码候选 C(分支 HEAD):见 deliveries/mc-rcf1-r3/HEAD.txt(推送后
  生成,避免自指提交)
- 两端实际加载产物(运行时握手报告):服务端与客户端
  SHA-256 `033ba71d2f6c8805165a1a374ae7c2452dcbff944606a6ff2d3c5d0091030814`
  (由 /v1/status 与 observe 的 client_mod_jar_sha256 字段实时报告,
  非硬编码提交或磁盘待部署文件)
- G4 五连的每次 run 均绑定该运行时身份(g4-runs-r3.json identity 段)

## 门与真实状态

| 项目 | 状态 | 真实入口/证据 | 缺口 |
|---|---|---|---|
| G4 五次计分 | **PASS** | evidence/g4-runs-r3.json;tools/rcf1_checker.py judge-g4 → accept=true | 无 |
| G4 成对变异 | **PASS** | evidence/mutations-r3;原例接受+11 单因素变异全拒绝(returncode=1) | 无 |
| 诊断核心链 | PASS | evidence/g4-diag-r3.json(采木→合成→放台→木镐→采石→石镐→重开合成全通) | — |
| 洞内连续放置 | PASS | evidence/place-slice-r3.jsonl(先上后下均 completed+见证) | — |
| IA LIVE 事实 | PARTIAL | evidence/ia-facts-r3.json(23 用例全录,24/26 场景 PASS) | judge-ia 拒绝于 I02 move 守恒(pre/post 快照数量不一致——真实发现,待查外部拾取/槽位竞态;不下调判定、不补 true) |
| G5 自然过夜 S01/S02 | **BLOCKED** | 两次尝试失败链(场景勘察+尝试记录);失败模式见 FIXES | 环境稳定性(客户端游走/感知空) |
| L 组生命周期 | PASS | rcf1_lifecycle_fake_tests 19/19(含 R3 新增 L14 stop 尾部核验) | — |
| V10 真实策略 | PASS | V10a-V10g 7/7(unknown fail-closed/植物不算围护/空包清晨不过) | — |
| checker 自测 | PASS | rcf1_checker_selftest 16/16(旧格式拒绝+8 组成对变异) | — |
| C/N LIVE | NOT_RERUN | R2 记录为旧格式(兼容拒绝,不作正式事实) | 本轮未重跑 |
| I08 Tom's | NOT_RUN | IA 采集含 i08 场景(结果见日志);正式转移未重验 | — |
| fresh replay | PARTIAL | 全新目录 clone 上游 a029fa6 + 仓库补丁 r3-base-tree.patch + overlay 同步 → remapJar 成功;与部署产物逐项比较:671 条目,0 class 差异,4 个文本/资源文件差异(许可证行尾/mixins 空白/语言文件) | 部署目录沿用既有受管环境(与 R2 同类偏差,如实记录) |

## R3 修复对照(ff29078 审查项)

- F01 checker 事实重算:**已实现**(rcf1_checker v3:操作类型物理效果
  重算、运行时 jar 身份、重复 (case,attempt) 拒绝、outcome_unknown
  拒绝、正例防全拒)。真实证据+11 变异经同一 CLI 拒绝。
- F02 G4 事实采集:**已实现**(运行时握手身份、完整回执、cursor 由
  终态 observe 派生、720s 限时逐步执行、事件区间不重叠校验)。
- F03 G5 特权移除:**已实现**(rcf1_g5.py armed 后结构性禁 rcon;
  三值避难判定 fail-closed;两自然场景声明);G5 仍 BLOCKED 于
  环境稳定性,非特权回退。
- F04 进食归因:**已实现并 LIVE 验证**(ConsumableComponentEatWitnessMixin
  见证真实完成处理;A08 LIVE 回执含 witness=1;外部移物 V04 拒绝)。
- F05 纯读:**已实现**(opportunities 无世界查询/无 journal 写;
  maintain 后台 TTL;inspect-local 有界视图)。
- F06 stop 全角色:**已实现**(两路径 scope-verified-0;L07/L12/L14)。
- F07 交付/覆盖:本目录。

## 关键实测根因(本口述实录均为带日志实证)

1. 树柱挖掘留洞后平视射线穿洞 → 机会永不出生(见 FIXES §采伐链)。
2. 高枝目标在近距站位的中心瞄准先命中更低格 → facing 永不达成
   (南向主站位 + 触达过滤修复)。
3. PlaceAction 支撑面心瞄准落在格边界被 raycast 解析到邻格
   (内缩 0.35 修复,place-diag 日志实证 cross≠support)。
4. 客户端动作无自有硬预算时,服务端取消竞态导致 Baritone 自旋
   10 分钟+(MineAction 硬预算修复)。

## 停止与残留

交付后 lifecycle stop all 核验(见 audit/stop-receipt.txt)。


## 附录:最终判定输出(同一 CLI,可复跑)

- judge-g4 D:/mc-rcf1-raw/g4-runs-r3.json → accept=true ("ok")
- judge-ia D:/mc-rcf1-raw/ia-facts-r3.json → accept=false
  ("case-I02-completed-unproven:move-total-count-not-conserved")
- mutations run → 原例 G4 接受;IA 原例随 judge-ia 拒绝;
  11/11 变异明确拒绝
- 生命周期假进程 19/19;checker 自测 16/16;V10 7/7
