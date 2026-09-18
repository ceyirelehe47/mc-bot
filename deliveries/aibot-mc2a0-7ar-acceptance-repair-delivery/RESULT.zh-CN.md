# MC-2A0.7A-R 验收修复 / 最终封版 — RESULT

日期：2026-09-14（UTC+8）。分支：`experiment/mc2a0-7ar-acceptance-repair`（自 `cb958908a494bf46b00bb24d32cd38f7b98438c7` 新建）。

## 1. 基线 / 分支 / 工具 / 清单 / 证据 / 最终 HEAD SHA

- 基线分支：`experiment/mc2a0-7a-security-cleanup`，基线 HEAD：`cb958908a494bf46b00bb24d32cd38f7b98438c7`
- 本轮分支：`experiment/mc2a0-7ar-acceptance-repair`
- Commit A（验收工具，含被替换的 `redacting_wire_proxy.py` 与 4 个新脚本/测试）：`a2b2b7763872...`（完整 SHA 见 00-meta/baseline.json）
- Commit B（canonical manifest / 自动化 / 重放）：见 git log（同分支第二提交）
- Commit C（净化证据与本 RESULT）：见 git log（同分支第三提交，即最终 HEAD）
- 替换后 proxy sha256：`2e0fed60974a81a81a054b6010684a14699339d4c975ce8eec7320ae84c87e5a`（与任务包 SHA256SUMS 预期一致）
- canonical manifest：`aibot-dsh-m0/SHA256SUMS` 446 条（Commit B 重生成，自引用排除）
- 证据：`aibot-mc2a0-7ar-acceptance-repair-delivery/`（本目录，全部经 sanitize-tree 净化）

## 2. 无 Java 生产文件改动的证明

`git diff cb95890 <本轮 HEAD> -- src/main src/client src/test src/gametest` 为 0 字节（01-diff 附件仅含 aibot-dsh-m0/scripts 5 个 Python 文件的 diff，1246 行）。冻结字节域（src/main+src/client Java、Graph Core/TaskGraphStore、FakePlayer、Real Client 协议/传输、Screen 所有权与适配器、DSH 插件/工具注册）全部零变化。

## 3. applier check/apply 结果与修正

`CHECKED: 1 exact blob; 4 new files. No changes.`（基线 HEAD、tracked worktree、proxy blob `6889003b...`、四新路径全部校验通过）→ `APPLIED: 5 files`。本轮供给补丁一次通过，无任何人工修正（上轮 7A 的缩进失配问题未复现）。

## 4. 验收修复 Python 计数

`test_mc2a07ar_acceptance_repair.py`：10 tests / 0 failures（`python scripts/test_mc2a07ar_acceptance_repair.py`，02-automated 归档）。

## 5-13. N2 精确时间线（全部数值 epoch_ms，无 prose/null）

- N2 execution ID：`1f702786-3c52-4f5e-b941-3fc031e6f80e`
- held frame ID：`held-000047`
- commit held：1789386026481
- 目标替换（terminal→air，setblock 前即时取值）：1789386026651
- target-change cancel 控制帧（经 proxy 转发，越过被持有 commit）：1789386026723（替换后 +72ms）
- 执行终态（failed / real_client_deposit_target_changed）：1789386026933（观测时刻，保守上界）
- Screen 关闭（ui.present true→false 轮询边沿）：1789386027237
- 旧 commit 释放（release flag 后同 frame_id released 行）：1789386027268
- 顺序证明：held(6481) < 替换(6651) < cancel(723) <= 关屏(237)；替换(6651) < 终态(933)；cancel(723) < 释放(268)；关屏(237) < 释放(268)。cancel 与关屏/终态的相互顺序不在门内（异步兄弟后果），实测两者都在替换后、释放前。
- `mc2a07ar_n2_timeline.py`：16 checks 全 PASS（03-n2-timeline/n2-check.json，pass=true）

## 14-16. N2 零增量与释放后无副作用

- player：2→2（delta 0）；旧目标（网络 chest 计数）：0→0（delta 0）；新目标（替换后 air 格）：0→0（delta 0）
- QUICK_MOVE 回执（proxy 全量日志统计 + summary.click_ack_count）：0
- 释放陈旧 commit 后：player/网络零变化，`ui.present_after=false`（未引发 Screen/动作/库存变化；stale/unowned 围栏生效）

## 17-23. Tom's 连→断→连三相循环

- world_lineage_id：`w07ar-4167799982467607063`（克隆世界 seed，跨三相稳定；克隆源=mc-server-mc1ca/world_play 副本）
- 装置：terminal `680 70 -11`（facing=east）/ link `681 70 -11` / inventory_connector `682 70 -11` / 网络 chest `683 70 -11`；original_link_state：`toms_storage:inventory_cable[east=true,west=true]`
- baseline_connected（新执行，非旧 B6 文件）：execution `0efe9f47-3d9f-40fd-9621-4441859d5df0`，completed / `server_authoritative_owned_screen_toms_storage_terminal_transfer_verified:1`，bob 2→1（-1）、网络 0→1（+1）
- disconnected：server_start_id `start-1789382850026`（与 baseline 不同）；观察窗 7200ms（≥5s）全程 running 不完成；终态显式 cancelled；bob delta 0、网络 delta 0、其他存储 delta 0
- reconnected：server_start_id `start-1789382917989`（与 disconnected 不同）；fresh deposit completed / `..._toms_storage_terminal_transfer_verified:1`；bob -1、网络 +1、selected 工具保留
- terminal 全程未替换（三相仅 setblock 681 一处 link；`terminal_replaced=false`）；`mc2a07ar_toms_reconnect.py`：22 checks 全 PASS（04-toms-reconnect/toms-reconnect-check.json，pass=true）
- 末相为 Tom's 正向（reason 含 `toms_storage_terminal_transfer_verified`），非 barrel/旧证据/barrel 替身（checker N6-5 显式拒绝过 barrel 替身语义）

## 24. 回归计数（一轮完整回归 + replay）

JUnit 453/0、GameTest 643/0、BridgeCore 105、Node 43/0、Installer 11、Supervisor 11/0、evidence hygiene 10/0、acceptance-repair 10/0、DSH 工具恰 29、remapJar PASS（产物 sha256 前 16：71a7260bee569d6a0）。LIVE 十项回归：say、goto pause/resume（同一执行）、moving final-facing（arrival_and_facing_verified）、Graph mine+pickup+durable DONE（raw_iron 入包）、vanilla barrel deposit（verified:1）、Tom's 正向 deposit（verified:1）、gather typed 拒绝（HTTP 409）、FakePlayer say/goto/deposit 3/3（fake 后端重启+spawn；spawn 于世界出生点先 tp 至工作区并 set_base 后全通）、background 输入隔离（物理 A 键注入 3s 位移 0.000）、单一物理权威（8766 第二控制连接被拒，probe expect=rejected PASS）。

## 25. clean replay / canonical manifest

git worktree @cb95890 → cherry-pick a2b2b77 → 树哈希 `d1da4325ddc07904bf395553add11a7a42693d3a` 与分支 A 树哈希完全一致（diff 0 字节，worktree clean）。SHA256SUMS 446 条按最终生产/脚本字节重生成（无自引用）。

## 26. DSH 工具数

恰 29：12 个显式注册（connect/observe/view/inspect/inspect_local/status/release/request_status/graph×4）+14 个 bounded operations（goto/gather/craft/smelt/eat/set_base/deposit/say/register_home/register_farm/tend_farm/capture_home/repair_home/mine_opportunity）+3 个 execution 控制（pause/resume/cancel）。与冻结基线一致，无变化。

## 27. 冻结生产哈希 / diff

`git diff cb95890 <HEAD>` 仅 aibot-dsh-m0/scripts 5 个 Python 文件 + 本交付目录 + SHA256SUMS；生产 Java/Graph/FakePlayer/RealClient/Screen/DSH 冻结域零字节变化（见 §2）。

## 28-31. 最终远程 SHA / 外部终审计 / 冻结后零提交

- 最终远程 SHA = 本分支最终 HEAD（Commit C）推送后的远程引用；由 LIVE-F 外部终审计（`mc2a07ar_external_final_audit.py`，输出 `D:/mc2a07ar-final-attestation.json`，仓库外）在推送冻结后对精确 remote SHA 执行 exact-secret 审计并记录。
- 审计密钥文件数：2（.secrets/mc2a07-old-control.token、.secrets/mc2a07a-new-control.token，仅指纹引用，值不进任何证据）。
- 冻结前双审计（07-audit-pre-freeze/）：audit-tree（交付目录）pass=true 0 findings；audit-git（cb95890..a2b2b77，禁祖 57b534d 校验含）pass=true 0 findings。
- 外部终审计生成后本分支不再有任何 commit（attestation.freeze_rule）；"无后续提交"由远程 SHA 冻结与 attestation 对外返回共同证明。

## 32. 最终冻结裁决

Production Real Client + Tom's Storage 基线在 cb95890 之上的三道验收缺口（N2 主动取消时序、Tom's 真实断链重连正向、最终 HEAD 精确审计）全部闭合；生产字节零变化；一轮完整回归全绿 + clean replay 零 diff。**裁决：可冻结（freeze-ready）。**

## 附录 A：本轮装置学发现（设备层，非生产缺陷）

1. `toms_storage:inventory_connector` 无方向属性：`[facing=east]` 等非法属性使 setblock 静默失败——这是上一轮 N2/N6"重建装置不能收货"的共同根因之一（connector 从未放置成功）。
2. `inventory_cable` 裸放依赖邻接自动开面（terminal/connector 在位时自动 `[east=true,west=true]`）；显式 `[east=true,west=true]` 放置也会被后续邻接更新按邻居类型重算。
3. verified 证明链：terminal BE 的 items 运行时缓存增量==网络 chest 实际增量，仅当 chest 为空时两者相等成立——LIVE-C 各相重启天然清空缓存（RUNBOOK 重启要求与本语义耦合），LIVE-D 不重启场景用 terminal+chest 双重放等价复位。
4. 8766 控制传输单 accept：真实客户端断开后必须连服务器一起冷启动才能再次接受控制连接。
5. 克隆服 real_client 后端启动要求 runtime.json `bots` 为空（上一轮 fake 后端冒烟遗留的 fake player 记录会触发 `real_client_fake_player_authority_conflict` fail-closed 崩服）。
6. fake 后端 spawn 的 Bob 无既有档案记忆（删档轮 spawn 于世界出生点），deposit 前需 set_base；goto 有 128 格距离上限。
