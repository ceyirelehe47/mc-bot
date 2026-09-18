# LIVE-2A0-3｜structure inspect（实机 PASS）

对象：world_r2 中已注册并 captured 的 HOME `r2home`（R2 轮注册，527 cells baseline）。

## 流程
1. `view` 记录 hash_before = sha256:3894e5be2d40b19e607f28185d191c82b27e43aaa2b131b308f98908c27b79c
2. `inspect r2home baseline`：
   ```text
   baseline_cells=527 histogram_kinds=11 missing_sample=0 truncated=False
   ```
   （bounded：block histogram 11 种而非 527 格 dump；missing 样本上限 64。）
3. `inspect r2home integrity`：`{"expected":527,"matched":527,"missing":0,"wrong":0,"repairable":false}`
4. RCON 物理对照：用 histogram 主导块 grass_block(233) 在 bounds 内探测到实体格 (18,112,18)。
5. 查询风暴（3× view + inspect + inspect_local）之后：
   ```text
   probe_after=True（物理格未变）
   hash_same=True（语义状态零变化）
   active_execution=None（无 mutation execution 被创建）
   ```

结论：drill-down 有界、只读，前后 0 世界 mutation、0 registry mutation、执行所有权不变。
