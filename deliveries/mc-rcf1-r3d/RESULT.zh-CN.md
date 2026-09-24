# MC-RCF-1-R3D — 结果报告

提交状态:**READY_FOR_REVIEW**(PARTIAL;非 COMPLETE_CANDIDATE)

## 1. Bob 实际完成了什么

**完成(干净部署 seed=178000000001,jar=29d160c3…;verifier 复核后
D 切片与非计分链已在本部署重跑,重跑记录为交付证据;d-slices 日志中
较早时间戳的行为同代码旧部署首跑,仅作历史层):**
- 采集技能核心重写后,D01–D06 受阻恢复六类切片全部真实通过:
  首选 6 站位真实轮试后推进次候选、goto 失败分支换站位采到同目标、
  全禁有界返回(无第三次同组合盲发)、取消/骷髅死亡翻会话/驱动进程
  击杀均"旧请求不复活+对账解除"、净进展解禁后原目标重新候选。
  全部带真实回执(facing_timeout/stall/预检跳过)与阶段耗时。
- 非计分确认:采木→板→棍→台→木镐→采石→石镐→再木镐全链 67.6s
  (R3C 同链 200s+);进入+连续封口 6s。
- G4 五连 5/5 accept=true(116–453s/run,远低于 720s 门槛)。
- IA 28/29;C 7/7(含实装的 C06/C07);N 18/18(×3 独立 attempt);
  V 13/13(live 注入四例真实通过+证据映射);L 20/20。
- JUnit 479/479;Python 离线 82/82;变异:g4 组 12/12 拒+正例受;
  IA 组 16 条 INCONCLUSIVE(I08 正例阻断所致,如实计数)。
- I08 根因重定性:R3C 的"上游 #381 dropped connectors"结论修正——
  连接器块存在;R3C fixture 用 `[facing=east]`(2.1.2 无此属性)致
  setblock 静默失败、连接器从未放置;合法组网后实测:连接器→箱子
  链路已证(toms_diag 反射),但终端聚合恒 0 + 作者在 #381 确认
  1.21 重写版缺 cable connector 待补回 → BLOCKED(上游),8 拓扑
  实测记录,不扩大归因(vanilla 容器 I04 不受影响)。

**失败(证据保留):**
- G5 S01 attempt-1(旧场景):无 minable 石(全新世界全表土覆盖,
  泥土不在感知类别=合法链不可挖)→ 无法获 3 圆石;板式掩体封口
  与顶盖放置连续失败(PlaceAction 对特定支撑面的准星错配,place-diag
  实录 sent=true 打到错面)。
- G5 S01 attempt-2(石壁场景):树位于 4 深坑(实测高程),goto 超时,
  未获木。该世界未找到"平地树+合法可达石"共址场景(1.5h 网格实测)。
- G5 S02:声明后未跑(共享缺陷未修前不硬凑;场景勘定本身未完成)。
- IA/mutations 门:g5/i08 以外的全部子项通过;两门仅因 I08 阻断。

## 2. 门结果(judge-all,checker v4)

| 门 | 结果 | 原因 |
|---|---|---|
| g4 | ✓ ok | 5/5,新合同下 accept |
| tests_c/n/v/lifecycle | ✓ ok | 7/7, 18/18, 13/13, 20/20 |
| build / fresh_deployment | ✓ ok | JUnit 479/479+Python 82/82;空目录+72 条哈希依赖+全新世界 |
| ia | ✗ | 仅 I08 positive-op 未证(上游阻断;其余 28 case 过) |
| i08 | ✗ BLOCKED | 上游 #381(1.21 重写缺 cable connector);8 拓扑+反射诊断在案 |
| mutations | ✗ INCONCLUSIVE | ia 组无合格正例(I08 连带);g4 组 12/12 全拒+正例受 |
| g5_s01 | ✗ | stones=0(上述 attempt-1/2 实录) |
| g5_s02 | ✗ | 该槽位以 s01b(S01 attempt-2)文件受判:
  model-decisions-single-op-script;S02 场景本体声明后未跑(独立
  阻断如实单列) |

## 3. R3C R01–R04 关闭状态

- **R01 关闭**:完整源码修复(rcf1_mine_skill 核心+双适配层)、
  可隔离测试 12/12、D01–D06 真实请求/位置/准星/效果/取消记录全备。
- **R02 关闭**:checker v4(自测 50/50,含 18 个 F01–F07 新边界);
  事实层 unknown 对账记录;R3C 真实 G4 证据在新合同下仍 accept。
- **R03 关闭**:失败行致命/attempt 唯一/INCONCLUSIVE 可区分/
  build 必需入口/fresh 逐项差异+世界声明/变异门成对语义。
- **R04 关闭**:候选=HEAD(见 BASELINE);空目录安装+逐条哈希依赖
  清单+全新世界(seed 声明);正式矩阵全在此部署完成。

## 4. 遗留(下轮输入)

1. PlaceAction 支撑面准星错配(S 封口/顶格 6 连败,place-diag 在案)
   ——修 mod 后 G5 板式掩体可行;或场景选址保证可挖石壁。
2. G5 场景勘定需自动化判据(平地树+50 格内露头石+缓坡),本轮
   手工勘定两次踩坑(坑树/无石)。
3. I08 待上游补 cable connector 后重验(锁定版本是项目约束)。
4. checker v4 三处健壮性小项(verifier 提出,不影响本判定):
   judge_g5 的 run_id 参数未用;G4_SCHEMA 常量未参与校验;
   _gate_fresh 的 world.sha256 字段名与实际 level_dat_sha256_ungz
   不一致(当前经 origin=fresh_generated 分支通过)。
