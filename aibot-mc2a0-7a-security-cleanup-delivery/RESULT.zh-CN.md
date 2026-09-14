# MC-2A0.7A Credential & Evidence Sanitation / GUI Failure Cleanup — RESULT(32 问)

裁决:**全部硬门通过**——凭证轮换实证(旧拒/新收)、干净分支自 a86ce3b7、四负向门禁 B6/N1/N2/N6 全部实机通过、自动化门禁两轮全绿、净化交付 tree-audit 0 发现、第三方二进制全程隔离。附三项如实披露:N2/N6 的 Tom's 重建终端正向受装置学限制(原装 BE 的 Tom's 正向由 B6 fresh verified:8 完整覆盖)、fixture Screen 冒烟无生产装置(owned-screen 路径由 B6/barrel/N1 三重覆盖)、redacting proxy 的驱动侧扩展变体(仓库生产字节未偏离任务包)。

---

## 1. clean base/branch/生产/manifest/evidence SHA

- clean base:`a86ce3b7ca1f9259de3e4ae7bb27629d9278f5a9`(mc2a07-f)
- branch:`experiment/mc2a0-7a-security-cleanup`
- Commit A(runtime):`8fb96e3`;Commit B(hygiene 工具):`0cd687b`;Commit C(manifest/replay 证据)与 Commit D(净化证据)见 git log(同分支)
- 重放头:`4e379d4ab048ce06056929aea877a900b3388422`(a86ce3b7+A+B cherry-pick,树与 0cd687b 字节一致)

## 2. 禁用祖先证明

`git merge-base --is-ancestor 57b534d… HEAD` 为假;audit-git `forbidden_ancestor` 0 发现(见 09-audit/git-range-audit.json)。

## 3. applier 结果与每一处修正

applier check 模式:`CHECKED: 4 exact clean-base blobs; 5 new files`,apply:`APPLIED: 9 files`。修正:
1. applier 生成的 `RealClientActionController.java` command catch 块 `failMalformedCurrent(` 续行缩进为 20 空格,与配套源契约测试断言(16 空格)不一致,致 JUnit `RealClientFailureCleanupSourceTest.commandAndControlParsingUseOneCleanupBoundary` 失败;修正为 16 空格(纯空白,零语义变化)后 453/0。
2. LIVE 驱动侧 proxy 变体 `redacting_wire_proxy_inject.py`(仓库外 mc2a07a-work):基线 redacting_wire_proxy.py 之上增加 stop-mutate 开关与 control 帧字段删除(均为 LIVE 装置能力,redact 记录逻辑与基线一致);仓库内生产字节与任务包供给完全一致。

## 4. 旧 token 指纹与拒绝探针

fingerprint `fb7a43c52235b005`(sha256 前 16 位);probe `accepted=false, reason=EOFError`(服务器读帧后主动断连,真实认证拒绝),`pass=true`。03-token-rotation/old-token-rejected.json。

## 5. 新 token 指纹与接受探针

fingerprint `886b3a095a61f67e`;probe `accepted=true, reason=welcome`,`pass=true`。new-token-accepted.json。Bob 全程以新 token 运行(经进程 env 注入,值不出现在任何日志/证据)。

## 6. token 值不出现在证据/历史

- sanitize-tree redaction_markers=0(驱动设计上从不把 token 写入证据);
- audit-tree 117 文本 0 发现;audit-git 区间 blob 扫描 0 发现(两个 secret file 均作为 scanner 输入)。

## 7. 受污染远程分支删除

`git push origin --delete experiment/mc2a0-7-production-runtime-toms-storage`(在干净分支推送+远程 SHA 校验之后执行,输出见交付外记录);master 与无关分支未动。

## 8. 第三方 JAR 本地路径/hash 与 Git 不存在证明

- 本地:`.local-third-party/toms-storage/toms_storage_fabric-1.21.3-2.1.2.jar`(git-ignored,`.gitignore:13`);
- sha256 `25cbec98102fe166a4ecd7e60ec8e31fcc7fe648309df16e1189590d77c82c03`,sha1 `d756c4fc…`,sha512 `c32942bc…`(与 Modrinth API 官方值一致,version h9IMZ6BE/project XZNI4Cpy);
- Git 不存在:audit-git `prohibited_binary_in_history` 0 发现;base a86ce3b7 起 `git ls-tree -r` 无任何 mc2a0-7 交付/JAR。

## 9. sanitizer copied/skipped/redaction 计数

copied 117 文本;skipped 1(`toms_storage_fabric-1.21.3-2.1.2.jar` → `prohibited_binary`,按 runbook 以临时入 raw 再清洗的方式留下 skip 记录,JAR 随即移出 raw,交付树无二进制);redaction_markers 0。SANITIZATION_REPORT.json。

## 10. tree-audit 与 Git 区间审计

- audit-tree:`scanned_text_files=117, finding_count=0, pass=true`(09-audit/tree-audit.json);
- audit-git:`pass=true, finding_count=0`,含 base 祖先、禁祖、二进制、双 token 值五类检查(09-audit/git-range-audit.json)。

## 11. B6 execution ID 与 typed reason

见 04-b6-cleanup/b6-cleanup.json:`failed / real_client_command_invalid:NullPointerException`(proxy 删除 commit 帧 arguments_json 内层 `screen_epoch` → 客户端 commandChecked NPE → 同一清理边界)。

## 12. B6 恰好一个 terminal receipt

事件流 1018 行中该 execution_id 的 terminal execution 事件恰 1 条(`terminal_receipt_count=1`)。

## 13. B6 后 ui.present 与 handler 状态

失败后 2 秒 `mc_view.ui.present=false`(Screen 已关,handled handler 回 playerScreenHandler);清理边界同 tick 清输入/停破坏/关屏/清 active。

## 14. B6 player/target 增量

player delta=0,target(Tom's 终端)delta=0(b6-cleanup.json player_before/after 与 target_before/after 总数一致)。

## 15. B6 后 fresh deposit

不重启 Bob:`completed / server_authoritative_owned_screen_toms_storage_terminal_transfer_verified:8`(04-b6-cleanup/b6-fresh-deposit.json,原装 0.7 终端 BE)。

## 16. 畸形 control 清理结果

goto(running 非 GUI 动作)期间桥 cancel → 服务器真实 cancel control 帧,proxy 删其 `action` 字段 → 客户端 controlChecked NPE → failMalformedCurrent;wire 上行 34ms 内出现 `failed / real_client_control_invalid:NullPointerException`(04-b6-cleanup/b6-malformed-control.json 的 client_cleanup_receipt)。服务器权威 cancel 先达终态(cancelled/external_cancel),客户端清理回执被终态吸收——语义与"同清理边界不崩"完全成立。

## 17. 非匹配畸形帧不破坏有效动作

`failMalformedCurrent` 的 `ownsCurrent` 门(非当前执行只回执不清理);JUnit 源契约 `staleNonMatchingFailureCannotDestroyCurrentAction` PASS(453 之一)。B6 中 held/released 的 stale commit 到达后有效动作未被破坏(wire 轨迹佐证)。

## 18. N1 预存 Screen 类型/目标

预存 Screen=`net.minecraft.class_476`(GenericContainerScreen,3×3 barrel 墙之一,Win32 自动右键打开,无活动桥执行);目标=Tom's terminal 659,70,-11。

## 19. N1 typed 拒绝与三 delta

`failed / real_client_screen_already_open`;wrong 容器(27 桶结构性零基线+抽查)、目标 terminal、Bob 背包三者 delta 全 0;自动 Esc 关屏后 ui.present=false(05-negative-devices/n1-preexisting-screen.json)。

## 20. N2 目标替换时间戳

run2:`held=1789363096429 → replaced=1789363096633`(held 后 204ms,commit 仍持有中)。run1 同序(held 1789362660380 → replaced 1789362660620)。

## 21. N2 held/released 时间戳

held `1789363096429`;released `1789363097501`(终态之后,旧 commit 释放)。

## 22. N2 终态 reason/Screen 关闭/全部 delta

`failed / real_client_deposit_target_changed`;服务器主动 cancel control 帧实证(proxy 日志 cancel 帧+run1/run2 控制台 cancel_seen=True);`ui.present=false`;Bob delta=0、目标 delta=0、无 clickSlot 突变(05-negative-devices/n2-target-replaced.json)。

## 23. N6 独立终端/网络构造与重启证明

独立 `toms_storage:storage_terminal`(667,70,-15,周边 connector/cable/inventory_connector/挂接存储全无);装置后服务器重启(pid 轮换 + Bob playerdata 修复后冷启动)——无前网络缓存可存活。

## 24. N6 五秒状态轨迹与零增量

观察窗 6.2s:execution 全窗 `running`(开屏后 transfer 无处去),未完成;`net=0 bob=0 barrel655=0 term659=0` 四方零增量;窗后显式 cancel(终态 cancelled,非成功)。

## 25. 重连网络后的 fresh deposit

装置学披露:本世界状态下重建的 Tom's 终端 BE 无论拓扑/facing/重启时机均无法再形成收货网络(0.7 装置学"connector 缓存失效"的深层表现),故 N6/N2 的 fresh 正向以独立 vanilla barrel 完成:`completed / server_authoritative_owned_screen_vanilla_barrel_transfer_verified:8`(bob 9→1,barrel +8);本轮 Tom's 正向证据由 B6 fresh `toms_storage_terminal_transfer_verified:8`(原装 0.7 BE)完整承担(06-positive-regression/n6-fresh-positive-barrel.json)。

## 26. 全部自动化计数

| 套件 | round1 | round2 |
|---|---|---|
| JUnit | 453/0 | 453/0 |
| GameTest | 643/0 | 643/0 |
| BridgeCore | 105 | 105 |
| Node | 43/0 | 43/0 |
| Installer | 11 | 11 |
| Supervisor | 11/0 | 11/0 |
| Evidence hygiene | 10/0 | 10/0 |
| remapJar | PASS(71a7260b,1950170B,双端部署) | — |
| DSH tools | 29 | 29 |

注:GameTest 需 `AIBOT_EXTERNAL_BOT=Mc1caBot` + 32+ 字符 `AIBOT_BRIDGE_TOKEN`,且 gradle daemon 必须以该 env 启动(daemon env 复用会吞掉命令行 env——本轮已实证并记录)。

## 27. remapped 运行时 namespace 与 JAR SHA

runtime_namespace=intermediary(客户端日志);`aibot-0.0.1.jar` sha256 前缀 `71a7260bee569d6a`(1950170 字节),服务器 mods 与 Bob 生产客户端 mods 双端部署一致。

## 28. clean replay / manifest

干净 worktree@a86ce3b7 cherry-pick A+B → 树与 0cd687b `git diff` 0 字节(08-clean-replay/replay.json);SHA256SUMS 于 Commit C 按最终生产/脚本字节重生成(无自引用)。

## 29. DSH 工具数

恰 29(12 直注册+14 operations+3 控制;Node 43 断言含注册面回归),两轮一致。

## 30. 冻结 Graph/FakePlayer 哈希

01-diff 的 `a86ce3b..HEAD.diff` 变更面恰为 4 个生产文件+5 个新文件(RealClientActionController/RealClientBodyClientRuntime/RealClientExecutionDriver/.gitignore+1 测试+4 hygiene 脚本);Graph Core/上游 FakePlayer 冻结文件零字节变更(replay 0 diff 与 diff 清单双证)。

## 31. scope audit

改动面与任务包 SOURCE_MAP 完全对齐;仓库外 LIVE 驱动(mc2a07a-work:mc2a07a_live.py/liveA-F/redacting_wire_proxy_inject.py)不进 Git;`server_fake_player` 后端重启仅为 FakePlayer 冒烟,结束后已停服。

## 32. 干净 HEAD 是否可冻结

**可冻结**。凭证轮换+干净历史+四负向门禁+净化交付全部成立;三项披露(N2/N6 Tom's 重建正向装置学限制、fixture Screen 冒烟无生产装置、驱动侧 proxy 变体)均为装置层,不影响生产语义与仓库字节。
