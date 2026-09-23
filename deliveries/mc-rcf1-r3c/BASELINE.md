# BASELINE — MC-RCF-1-R3C

## 候选与提交

- 候选 C:本轮行为代码全部在 git(本分支);范围=
  - Java 生产码:aibot-dsh-m0/aibot-overlay(GotoAction 停滞检测/
    facing 诊断;DepositAction 零点击诚实失败;observe world_time)
  - tools/:rcf1_facts.py(v2)/rcf1_tests_ia.py(I02 拆分+录制链)/
    rcf1_checker.py(v3 R3C)/rcf1_mutations.py(v2)/rcf1_g4.py(
    status_end+execution_id)/rcf1_g5.py+rcf1_g5_drive.py(world_time/
    skill 通道/站位纪律/走查扫视)/rcf1_env.py+rcf1_lifecycle.py(
    RCF1_ROOT 参数化)/rcf1_sync_overlay.py(--build)
- 证据提交 E:deliveries/mc-rcf1-r3c/(文档+证据;纯交付资料,
  不改变已验收行为)
- 远端 HEAD:push 后回填 HEAD.txt

## 构建输入与身份

- 上游:D:\code\mc-experiment\aibot @ a029fa6a
- 补丁:aibot-dsh-m0/upstream-patches/r3-base-tree.patch
- overlay:仓库 aibot-dsh-m0/aibot-overlay/src(85 文件,
  python tools/rcf1_sync_overlay.py --build 同步)
- fresh 构建树:D:\mc-rcf1-r3c-replay\src(新 clone,非旧 build
  tree 副本);remapJar BUILD SUCCESSFUL in 1m19s
- 产物 SHA-256:d6ce23758ef3dfc012090960cbc86cc88ab8da49422cd3b501771809ce2a4317
- 部署:rcf1-server-r3c/rcf1-client-r3c(新目录;两端 mods 同 jar;
  运行时握手两端一致)
- 与 rebuild-base 产物差异:manifest 时间戳与条目时序(逐项解释见
  evidence/fresh-deployment.json content_diffs;class 语义同源)

## 世界与运行身份

- 世界:rcf1-server/world_rcf1 副本(声明来源);aibot journal
  重置为全新账本(旧执行历史不带入)
- 运行命名空间:RCF1_LIFECYCLE_NS_ROOT=D:\mc-rcf1-raw-r3c\lifecycle
  (与旧环境状态隔离)
- 原始日志:RCF1_RAW=D:\mc-rcf1-raw-r3c(仓库外;脱敏后进交付)

## I08 阻断证据链(定位到第三方)

1. 客户端路径全通:goto face completed → 屏开(HandledScreen 分支)
   → 服务端 authorizeOwnedScreen → commit → QUICK_MOVE 点击 →
   client_owned_screen_quick_move 状态流(逐秒轮询实录)
2. 物理零转移:5 种组网(plain connector ±facing/cable_connector
   east+west/无线极简/预置内容)下箱子 Items 恒空、玩家 dirt 不动
3. 第三方佐证:tom5454/Toms-Storage#381——1.21-2.x 为重写版,
   cable connector 未实现;锁定版本 1.21.3-2.1.2-fabric
4. 生产侧防御已修:DepositAction 零可存物(全在保护槽)时
   fail(client_deposit_nothing_depositable)不再假完成

## G5 阻断证据链

- 尝试 1(s01):基点感知空(树冠遮挡)→ 7min 零候选
- 尝试 2-3(s01b/c):基点为 R3 旧挖掘坑,走查扫视后仍 0-3 块可见
- 尝试 4-5(s01d/e):站位修正后真实采伐成功(3+1+2 根原木,
  含机会准入/迟效拾取),但白昼窗口(~11min)耗尽 → 夜亡×2
  (fresh 部署服务端日志死亡核数=2:04:22 Spider、04:49 Skeleton;
  更早 2 次死亡在旧部署诊断期,不属本部署正式矩阵)
- 同源故障影响 S02(相同日长/链速),不重复必败条件
- 核心机械已由 G4 5/5 + S01d/e 部分链证明;缺的只是链速/场景选址

## 工作区状态

- 权威 overlay 与 rcf1-rebuild-base/src 一步同步(--check 0 差异)
- 源工作区(D:\code\mc-experiment\aibot)未动(锁定上游)
- .secrets/ 不入库;journal/日志脱敏后入 evidence
