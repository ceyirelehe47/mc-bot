# MC-2A0.7A-R2 Final Closure — RESULT

轮次目标：在不修改任何生产行为的前提下，闭合最后两个冻结门——Tom's selected-tool retention 的机器证据 + exact final-HEAD external attestation v2 的实际文件回传。

## 16 问必答

### 1. 起点、分支与提交链

- 起点：`experiment/mc2a0-7ar-acceptance-repair` @ `d7b89c919eecee7b489993c3e48607d886117b4a`（应用器硬校验通过）。
- 新分支：`experiment/mc2a0-7ar2-final-closure`。
- Commit A：`d4ecdf5`（checker/test/audit-v2 三文件替换 + SHA256SUMS 重建，290 insertions / 36 deletions）。
- Commit B：本 RESULT 与净化 R2 LIVE 证据（见 `git log`，sha 以远程为准，最终 HEAD 见第 11 问与 attestation 文件）。

### 2. 三个补丁文件旧 blob 与新 SHA-256

| 文件 | 旧 Git blob | 新 SHA-256 |
|---|---|---|
| `aibot-dsh-m0/scripts/mc2a07ar_toms_reconnect.py` | `5f37e349f4b84897105a71ae1b65b826f038db54` | `bedb2a469d9831702327e8c8ba6e226f049ee7ea83e19c1d43057f86ab07208a` |
| `aibot-dsh-m0/scripts/mc2a07ar_external_final_audit.py` | `377eec1f8a78eacefd605667a96b0c9613689fc5` | `3d6217ef4f14de1747f70c9ad4424805cd0d4fbc1872c8445ee37cb78aa01dbd` |
| `aibot-dsh-m0/scripts/test_mc2a07ar_acceptance_repair.py` | `7966f207cb1c5308cedb133a4b5696a14fcf8128` | `39e500b00accbd700e8f24ca48740c42bc32d75d54680d9656b2598abbdfdeaa` |

三值与任务包 SOURCE_MAP 声明逐一相等（应用器在写入前完成 blob 与 sha256 双重校验）。`aibot-dsh-m0/SHA256SUMS` 由仓库内 `scripts/gen_sums.py` 重建，不使用任务包静态副本。

### 3. 测试计数

- acceptance repair Python：**17 tests / 0 failures**（`02-automated/acceptance-repair-python.txt`，exit=0）。
- evidence hygiene Python：**10 tests / 0 failures**（`02-automated/evidence-hygiene-python.txt`，exit=0）。

### 4. 生产 Java diff 0 字节证明

`git diff d7b89c919eecee7b489993c3e48607d886117b4a -- src/main src/client src/test src/gametest` 输出 **0 字节**（`02-automated/production-java-diff.txt`）。本轮全链路未触碰任何生产 Java、DSH 插件、注册或代理/N2 checker 逻辑。

### 5. Tom's 装置与血统

- fixture_id：`toms-r2-live-20260914`。
- world_lineage_id：`w07ar-4167799982467607063`（世界种子派生，三相一致）。
- terminal `680 70 -11`（storage_terminal，三相不替换）；link `681 70 -11`（唯一被切换单元）；connector `682 70 -11`（inventory_connector，无 facing 裸放）；network chest `683 70 -11`。
- original link state：`toms_storage:inventory_cable[east=true,west=true]`。
- 说明：上一轮 LIVE-B N2 在其收尾时破坏了 terminal；本轮在三相循环开始**之前**做了一次初始构建（cycle 外），循环内 terminal/connector/chest 均未替换，`only_link_changed=true`、`terminal_replaced=false` 由 checker 核验。

### 6. Phase 1 connected positive

- 冷启动：`start-1789394955638`；link 保持 original state。
- execution `55fe1bb5-1be2-454a-8d9f-f4d9cea7dd0e`：`completed`，reason `server_authoritative_owned_screen_toms_storage_terminal_transfer_verified:2`。
- 转移量 N=2（player_delta=-2，network_delta=+2）。
- selected tool 前后快照（RCON `SelectedItemSlot`/`SelectedItem.id`/`SelectedItem.count` 原始读取）：slot 1→1、`minecraft:diamond_pickaxe`→`minecraft:diamond_pickaxe`、count 1→1。**非布尔自报**。

### 7. Phase 2 disconnected negative

- 冷启动：`start-1789395020894`（与 phase1 不同）；link 替换为 `minecraft:air`，其余装置不动。
- execution `6dbdadf8-9eff-4cb6-8443-4787a08bab7f`：观察 **7132 ms**（trail 全程 `running`，见 `state_trail_note`），未 completed，随后显式 cancel → `cancelled`。
- 全部 delta：player_delta=0、network_delta=0、other_storage_delta=0。

### 8. Phase 3 reconnected positive

- 冷启动：`start-1789395095539`（与 phase2 不同）；link 在同一坐标恢复精确 original BlockState（裸放 + 邻接自动开面）。
- execution `21ffda2c-319b-4cd0-9e0c-c7bd52a3e282`：`completed`，reason `server_authoritative_owned_screen_toms_storage_terminal_transfer_verified:2`。
- 转移量 N=2（player_delta=-2，network_delta=+2）。
- selected tool 前后快照：slot 1→1、`minecraft:diamond_pickaxe`→`minecraft:diamond_pickaxe`、count 1→1。

### 9. checker 24 项结果

`mc2a07ar_toms_reconnect.py`（R2 版）对 `03-toms-selected-tool-cycle/toms-reconnect-cycle.json` 输出 24 项检查全部 true，含新增 `baseline_selected_tool_retained` 与 `reconnect_selected_tool_retained`（由显式快照六字段推导）；**`failed=[]`、`pass=true`**（见 `toms-reconnect-check.json`）。

### 10. canonical manifest

`gen_sums.py` 重建 `aibot-dsh-m0/SHA256SUMS`：**445 条**（自引用排除），二次运行零变化（幂等），`git diff --check` 通过。

### 11–15. external attestation v2（仓库外文件为准）

第 11–15 问的数值不在本 RESULT 内自报：最终 push 后运行 `mc2a07ar_external_final_audit.py`（base=`cb958908…`，branch=`experiment/mc2a0-7ar2-final-closure`，forbid-ancestor=`57b534d…`，两个 secret 文件），产物为仓库外 `D:/mc2a07ar2-final-attestation.json` 并**作为文件回传**。该文件机器核验：

- audited_head = local HEAD = remote HEAD（audit 前后一致）；
- secret_file_count=2、两个不同 16 位 SHA-256 指纹；
- audit.finding_count=0、pass=true、failed=[]；
- worktree_clean_before/after=true；
- attestation 生成后分支零新 commit（远程 SHA 复核）。

### 16. 最终 freeze verdict

**FROZEN。** MC-2A0.7A 基线（Production Real Client + Tom's Storage 真实适配器）最后的两个阻断点已按机器证据闭合：selected tool 由两正向相显式前后快照证明保留（slot/item/count 三元全等），最终远端 HEAD 由 attestation v2 实文件覆盖。本轮零生产 Java 字节改动，audit 后不再向该分支 commit。

## 附录：本轮执行注记

- **runtime bots 复活坑**：上一轮收尾的 FakePlayer 冒烟把 fake player 记录写回 `world_play/aibot/runtime.json` 的 `bots[]`，real_client 后端起服即触发 `real_client_fake_player_authority_conflict` 崩服；phase1 前已清空（world 数据复位，非生产改动）。
- **装置区区块未加载坑**：冷服上 RCON `execute if block` 对远端装置区假失败；本轮先 `forceload add 670 -16 690 -4`（票持久，跨三相冷启动有效）再做装置核验。
- **selected slot 非零**：Bob 持久 selected hotbar slot=1；采用垫位算法（先垫 S 个 cobblestone 进 slot 0..S-1，pickaxe 精确落 slot S=selected，extra cobblestone 入其他格），使 pickaxe 处于 selected 格从而被 QUICK_MOVE 排除，同时 N=2 转移量不受影响。
