# LIVE-R21-2｜dirt→grass 自然漂移等价 —— PASS

环境同 LIVE-R21-1。

## 场景

r2home baseline 中大量 expected=dirt 的格子，实机世界已自然演化为 grass_block
（草蔓延），R2 轮曾因此持续产生 `wrong` 并让 repair 永久 fail-closed（两次实证）。

## 执行与结果

1. 等价判定：R2.1 修复后 `integrity` 与 `homeRepairPlan` 对
   `dirt <-> grass_block` 白名单对判定等价 —— 全结构 wrong=0，
   等价格不计入 missing 也不进 repair blueprint。
2. 真 missing 修复语义：将 baseline=dirt 的 (33,113,24) 置空 → repair →
   **补回 minecraft:dirt（baseline 原始 id，不是 grass）**，dirt 材料 33→32 消耗。
3. 漂移不被强改：baseline 为 grass_block 的各格在 repair 后保持 grass_block；
   baseline 为 dirt 但当前已是 grass 的格保持 grass（不被回写为 dirt）。
4. 收尾 integrity=1.0 / missing=0 / wrong=0 —— 长期漂移的结构不再被误判为破坏性冲突。

## 窄等价边界（代码证据）

- 白名单仅 `HomeBlockEquivalence`（`minecraft:dirt <-> minecraft:grass_block`），
  注释明确"never a tag/palette family match"；logs/planks/stone 等结构材料不参与等价。
- GameTest `r21HomeDirtGrassNaturalDriftIsEquivalentButMissingStillRepairable` 覆盖同语义。

## 证据

- 服务器日志：`server-r21-live.log`（单格轮）与 `server-1708-multicell-repair.log`
  （多格轮，含 baseline=dirt 格补回 dirt 的 `event=place item=minecraft:dirt`）；
  两次 repair 的 `home_missing_only_repair_verified` 见 journal execution
  `7eb023c3` / `71ab1708`（桥 journal 持久化记录）
- registry：registry-final-state.json（r2home integrity 全绿）
