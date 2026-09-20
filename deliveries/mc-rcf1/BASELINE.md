# MC-RCF-1 基线:源码、构建与测试事实

## 源码→构建→部署映射
| 项 | 值 |
|---|---|
| 上游基座 | `zoyluoblue/mc_aiplayer` fork,commit `a029fa6a3760fd0f83834c104051b041d986da60` |
| 本轮源码权威 | `aibot-dsh-m0/aibot-overlay/`(72 新文件)+ `scripts/apply_to_aibot.py`(CHANGES 22 + EXTRA_CHANGES 3 锚定补丁 + REPLACES 2 整文件替换 + mixins.json 合并) |
| 可重建性证明 | 干净 worktree `rcf1-rebuild-base` + 安装器 `--apply` ⇒ 与活树 `D:/code/mc-experiment/aibot/src` **字节一致**(diff -rq 空;mixins.json 行尾换行已双向归一化) |
| 空目录构建 | `gradlew build --no-daemon -g ..\.gradle-rcf1`(代理 127.0.0.1:7897)→ BUILD 进入 runGameTest 后失败,但 jar 已产出 |
| 构建产物 | `aibot-0.0.1.jar`(2,005,144 B,sha256=68c19090…)+ sources jar;已部署至 rcf1-server/mods 与 rcf1-client/mods(同哈希) |

## 版本身份
MC 1.21.3;Fabric Loader 0.18.4;Fabric API 0.114.1+1.21.3;gradle 9.4.0(经 loom 1.16.2);Java 21 目标(JDK 21.0.12.1+1 运行 / JDK 23.0.2 编译均通过)。

## 服务器/客户端附加模组(沿用生产集)
server: appleskin 3.0.6 / toms_storage 2.1.2 / vein_miner 26.x;client: toms_storage 2.1.2。均为 Fabric 1.21.3 官方发布件。

## GameTest 基线(2026-09-20,干净重建,未做任何修改)
- 总量:650;失败:45;通过:605。
- 完整失败清单(45 项,机器提取):`D:/mc-rcf1-raw/gametest-baseline-failures.txt`(仓库外原始件,脱敏后按需入交付)。
- 主要聚集:MC2A02 tree-harvest、MC2A0 cognitive-view(inspect/hash/dimension)、MC2A04 tracker(`semantic_registry_not_started`——GameTest 装置内注册表未启动,疑似 fixture 顺序问题而非生产缺陷,待 G3 触及时定性)、gt1..gt7 通用组。
- 处置:按 03_ACCEPTANCE §I 记录为基线失败;本轮引入的失败必须归零,基线失败在涉及语义被本轮改动时同步修复或以等价行为断言替换。

## 隔离环境身份
- server dir `rcf1-server`:game 25599 / bridge HTTP 8799 / 控制 TCP 8798 / RCON 25598;world `world_rcf1` seed 178000000001(新档,非生产复制)。
- client dir `rcf1-client`:Bob 离线身份 UUID faa5dca3-c3d4-354b-ae1b-dde9e5a14b3b;background 窗口;输入隔离沿用 mod 机制。
- 凭证:全新生成 bridge/control/rcon 三件套,存 `.secrets/rcf1-*`(gitignore 覆盖 `*.token`/`.secrets/`)。

## C/E 提交关系
- 当前:C = 逐门推进中的 `experiment/rc-foundation-v1` HEAD(见 git log);E = 计划最终证据提交(未产生)。
- 最终候选定稿时在此登记 C/E 哈希与"E 未改生产源"的证明(diff 范围)。
