# MC-2A Graph Core R1.2A 验收修复 — 结果报告（zh-CN）

执行日期：2026-09-12。本轮为纯证据轮：不改 `aibot-dsh-m0/` 任何字节，
补全独立评审指出的三项缺口（原版图 LIVE、直接 JUnit 证据、R1.1 证据血统），
并以双亲 merge 提交作为 Graph Core 冻结基线。

## 22 问逐答

1. **验收分支与 SHA**：`experiment/mc2a-graph-core-r12a-acceptance-repair`，验收证据提交 = 本目录所在提交（SHA 见 `git rev-parse HEAD`，直接父为 cc5517e，单一提交）。

2. **机会 A 的 id**：`ore_c3d87bacf760_a2a8da5dccb4495c`（装置格 570,68,127，iron_ore）。

3. **原图 G 的 id**：`graph-473aa3ba293a669ea646d83c`（plan_key=r12a-original-570-127）。

4. **birth 收据序号**：seq=980（phase1 内建断言：journal 中该机会恰 1 条 birth、0 条终态）。

5. **回滚前 G READY 且无 execution 的证明**：`03-live-original-graph/graph-before-rollback.json` —— state=READY、subject=[A]、唯一节点 state=READY 且 execution_id 为空（phase1 对每个节点断言 `not execution_id`）。phase1 驱动源码经审计不含任何可执行 run-next 调用（影响面审查矩阵见交付报告）。

6. **语义 before/回滚哈希**：before=b327b3537dd3556aaf366b1abcb0175a215dd52a29f2c4098f2711c7a2edc052；回滚后=b327b3537dd3556aaf366b1abcb0175a215dd52a29f2c4098f2711c7a2edc052（**逐字节一致**，即回滚精确还原到 A 诞生前；含 A 的中间快照 sha=dcb762b0f2cca36257caaba9cb3b0599c47ee3f61d750b4695482f7ab7533dfd）。回滚只覆盖语义文件；journal 与 graph store 保留（journal sha=dbde1e33…、graph store sha=1b6683d4… 记录于 state.json）。

7. **A 先于首次 HTTP 恢复的证明**：`03-live-original-graph/semantic-restored-before-first-http.json` —— phase2 在服务器就绪标记（bridge bound）之后、任何 HTTP 调用与任何 RCON 变更之前，直接读持久化语义文件，断言 570 格恰 1 机会且 id==A。恢复来源即 journal birth(980) 的重放对账（R1.2 生产语义），无需端点参与。

8. **重启后原图仍 READY 的证明**：`03-live-original-graph/original-graph-after-restart.json` —— GET /v1/graphs/{G} 返回同 graph_id、state=READY、subject=[A]、节点无 execution_id。

9. **重观察保留 A 的证明**：`03-live-original-graph/semantic-after-reobserve.json` —— bot 站回装置位并 observe 后，570 格仍恰 1 机会且 id==A；journal 终局校验 births==1（重观察未铸新 birth，即无 B）。

10. **phase 2 无替代 plan 的证明**：phase2 驱动源码全文不含 `/v1/graphs/opportunity`（plan 端点）调用；journal 全程对该机会只有 1 条 birth；最终图即原图（同 graph_id）。dispatch 也只发生一次（`same-graph-dispatch.json` 为唯一 dispatch 记录，节点 attempt=1）。

11. **run-next 请求/执行 id**：dispatch_request_id=`graphd-18902c85c89d68107386d234f60791fb5a057535`；execution_id=`18e48128-dcf3-4310-b493-803354d51908`（operation=mine_opportunity，见 `03-live-original-graph/same-graph-dispatch.json`）。

12. **G 终态 DONE 的 reason**：节点 reason=`postcondition_satisfied:durable_inventory_gain_receipt`（attempt=1），图 state=DONE（`original-graph-final.json`）。

13. **consumed 序号与 birth<consumed**：consumed seq=987；birth 980 < consumed 987；无 stale（phase2 终局断言 stale 为空，`final-lifecycle-receipts.json` 仅含 birth 980 与 consumed 987 两条）。

14. **dev 树直接 JUnit**：`D:\code\mc-experiment\aibot` 执行 `gradlew.bat test --rerun-tasks --console=plain`，rc=0，**385 tests / 0 failures / 0 errors / 0 skipped，72 个 XML**。原始输出与聚合见 `01-junit-dev/`。

15. **replay 树直接 JUnit**：`D:\code\mc-experiment\aibot-replay` 同命令，rc=0，**385 / 0 / 0 / 0，72 XML**。见 `02-junit-replay/`。（两树源码 440 文件 sha 零 diff，仅运行日志差异。）

16. **evidence-only 改动路径**：验收提交相对 cc5517e 的全部改动均在 `aibot-mc2a-graph-core-r12a-acceptance/` 之下（README/drivers/tests/history 为补丁新增；00-meta、01/02、03、04、RESULT 为本轮产物）。

17. **验收证据提交 SHA**：见第 1 问；`verify_freeze_history.py` 的 `--acceptance-sha` 参数即填此值（为避免自引用文件，SHA 不落盘进冻结分支提交）。

18. **冻结 merge 双亲（按序）**：亲 1 = 验收证据提交；亲 2 = `e300f09fe20dfe943a1ee332b591c7420654f8c7`。

19. **两证据头皆为祖先的证明**：`verify_freeze_history.py` 在冻结 HEAD 上实测 `merge-base --is-ancestor`：e300f09 ✓、cc5517e ✓、验收提交 ✓（完整 JSON 输出附于本报告末尾附录）。

20. **最终 aibot-dsh-m0 == f70c9c2 的证明**：同一脚本 `git diff --quiet f70c9c2 HEAD -- aibot-dsh-m0` 通过；且 merge 前预检（`04-history/pre-merge-verification.txt`）已证 R1.1 链（e525582..e300f09）对生产树零改动、merge-tree 预演无冲突。

21. **最终冻结 merge SHA**：见附录 verify 输出的 `freeze_merge_sha`（`experiment/mc2a-graph-core-frozen` 分支 HEAD，无任何后续提交）。

22. **Graph Core 是否已冻结**：**是**。全部硬门（LIVE-1..9、TEST-1/2、SOURCE-1、HIST-1..4、SCOPE-1）通过后，双亲 merge 提交即为 MC-2A Graph Core frozen baseline；下一阶段可开始 MC-2A0.3 Body Backend Seam / FakePlayer mechanics extraction。

## 与任务书的偏差说明

- 装置格由默认 566,68,127 改为 **570,68,127**：默认格在存量语义快照中已被遗留机会 ore_fc5799c7… 占用，phase1 的 before-无机会断言无法成立；按任务书 "fresh fixture cell and a fresh plan key" 指引换格，plan_key 相应取 r12a-original-570-127。除此之外无任何偏差。
- R1.2 轮的 GameTest/Node/BridgeCore/Installer 证据未重跑：任务书明示无需重复（验收提交禁止改动生产/安装树）。

## 附录：冻结终检输出

见交付报告正文（verify_freeze_history.py 的完整 JSON，含 freeze_merge_sha 与三项祖先校验、生产树零 diff 校验、验收提交路径审计清单）。
