# MC-RCF-1 复现手册

## 前置
- JDK21(D:/mc-server/jdk-21.0.12.1+1 运行;JDK23 可编译);代理 127.0.0.1:7897(Gradle 全局配置已有)。
- 凭证:.secrets/rcf1-{bridge,control}.token、rcf1-rcon.pass(已生成;不入库)。
- 依赖:Baritone v1.12.0 官方 release 资产(sha256 b3b36aa3d74c4df053d147ee9254b70c15f4d1e5e11a2766141a146eea3bd60b,python aibot-dsh-m0/scripts/fetch_baritone.py 自动校验)。

## 源码→构建
1. 干净基座:git worktree 于 zoyluoblue/mc_aiplayer fork a029fa6a。
2. 应用 overlay:python aibot-dsh-m0/scripts/apply_to_aibot.py --repo <worktree> --apply
   (锚定 CHANGES/EXTRA_CHANGES/REPLACES;build.gradle 依赖经 -Pbaritone_jar 或默认路径)。
3. 构建:cd <worktree> && gradlew.bat build --no-daemon -g D:/code/mc-experiment/.gradle-rcf1
   (代理经全局 gradle.properties)。快速增量:remapJar 同法。
4. 字节码核验(部署前必做,教训):解包 build/libs/aibot-0.0.1.jar 确认新符号在
   (例:RealClientNavigation/server_authoritative_native_craft/无 InventoryCrafting)。
5. 部署:复制 jar 至 D:/code/mc-experiment/rcf1-{server,client}/mods/。

## 环境起停(唯一入口)
- python tools/rcf1_lifecycle.py start server|client [timeout_s]
- python tools/rcf1_lifecycle.py stop server|client|all
- python tools/rcf1_lifecycle.py status
- 并发安全:OS 文件锁;身份=PID+创建时间+命令行 marker;三连败锁定,unlock 需显式 reason。
- 假进程验证:python tools/rcf1_lifecycle_fake_tests.py(8/8;不启动 Minecraft)。

## 验收(全部 LIVE,需 server+client 在线)
- C 组:python tools/rcf1_tests_c.py(控制/取消/幂等/断线/租约)
- N 组:python tools/rcf1_tests_n.py(导航 6 场景×3;天空竞技场 fixture 自动布置)
- I/A 组:python tools/rcf1_tests_ia.py(背包/GUI/合成/放置/进食;fixture 设白天+清怪)
- 证据落 D:/mc-rcf1-raw/(仓库外,脱敏后入 deliveries)。

## 游玩接口
- python tools/play.py observe|view|overview|inspect|do '<op> <json>'|ctl|status|events
- 操作:goto/mine_opportunity/craft/eat/place/deposit/say/move_items(smelt 实验性,未验收)。

## G4 续跑起点
- 出生点 (8,107,-1) 自然橡树;链=挖叶暴露树干→挖原木→craft planks/stick/table→place→
  craft wooden_pickaxe→挖泥土暴露 stone(石类掉落已按 cobblestone 计)→mine 3 圆石→
  craft stone_pickaxe。矩阵:2×oak+2×第二树种+1×非默认快捷栏。

## 清理/停机
- python tools/rcf1_lifecycle.py stop all(显式;无自动复活)。
- 原始证据/日志:D:/mc-rcf1-raw/(含 lifecycle 状态);不入库。
