# BASELINE — MC-RCF-1-R3

- 分支:experiment/rc-foundation-v1;审查基准 ff29078ab3cd52d6cfceb928344c90e0c25ab2a7
- 源码候选 C:分支最新提交(完整 SHA 见 HEAD.txt,推送后写入同目录)
- 权威源码树:D:\code\mc-bot\aibot-dsh-m0\aibot-overlay\src(git 跟踪)
  + aibot-dsh-m0/upstream-patches/r3-base-tree.patch(构建基树的
  未提交本地修复,已入repo;29 文件 +624/-57)
- 构建树:D:\code\mc-experiment\rcf1-rebuild-base(上游 aibot
  a029fa6a + 上记补丁 + overlay 同步,tools/rcf1_sync_overlay.py)
- 构建命令:gradlew remapJar -g D:\code\mc-experiment\.gradle-rcf1
- 部署产物:aibot-0.0.1.jar SHA-256
  033ba71d2f6c8805165a1a374ae7c2452dcbff944606a6ff2d3c5d0091030814
  (rcf1-server/mods 与 rcf1-client/mods 同哈希)
- 运行时身份(由桥握手实时报告,非磁盘文件):
  GET /v1/status → server_mod_jar_sha256=033ba71d…;
  observe → client_mod_jar_sha256=033ba71d…;两端一致
- 上游:fabric-loom 1.16.2 / yarn MC 1.21.3+build.2 / fabric-loader
  0.18.4 / baritone-api-fabric 1.12.0 / toms_storage 2.1.2 /
  fabric-api 0.114.1+1.21.3
- 环境:server 25599 / RCON 25598 / bridge 8799 / control 8798;
  生命周期唯一入口 tools/rcf1_lifecycle.py
- 单元测试:479/479(rcf1-rebuild-base;含 R3 新增 4 项)
- fresh replay:D:\mc-rcf1-r3-replay(全新目录;clone 上游 aibot
  a029fa6a → git apply r3-base-tree.patch → overlay 85 文件同步 →
  remapJar 成功);与部署产物内容逐项比较:671 条目、0 个 class
  差异、4 个非类文件差异(LICENSE_aibot 行尾、aibot.mixins.json
  空白、assets/aibot/lang/{en_us,zh_cn}.json)
