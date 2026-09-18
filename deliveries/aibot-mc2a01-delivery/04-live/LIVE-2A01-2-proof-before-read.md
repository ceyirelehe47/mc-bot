# LIVE-2A01-2｜proof-before-read guard

环境同 LIVE-2A01-1（world_play，Bob 在 572,63,157）。

## 行为复验：墙后 diamond 不泄漏

1. RCON 在 Bob 前方 z+6 放 `diamond_ore`（574,63,163），z+4 处立 4x3 石墙遮挡。
2. `mc_inspect_local radius=8 detail=blocks`（histogram 断言）：
   ```
   diamond_ore in histogram: False      <- 遮挡时不输出
   stone in histogram: True             <- 可见的墙本身正常报告
   ```
   （单次耗时 103.9ms，visible_blocks=161。）
3. RCON 拆墙（fill air）后再查：
   ```
   diamond_ore now visible: True        <- 暴露后输出
   ```

## 源码级证据（static/source-contract，非 black-box）

JUnit `PrivilegedBoundarySourceTest.cognitiveLocalScanProvesObservabilityBeforeBlockRead`
（src/test，主仓库与安装器 EXTRA_CHANGES 同源）断言：

- `CognitiveInspector.inspectLocalJson` 方法体内 `canObserveBlock` 的首次出现位置
  **严格先于** `getBlockState` 的首次出现（BOUND-1 源码顺序即契约）；
- `StructureKnowledge` 中证明循环（canObserveBlock/canObserveCell）先于读取循环
  （getBlockState）。

生产实现（对齐契约 §3.1 的语义顺序）：

```java
if (!ObservableWorldQuery.canObserveBlock(bot, pos)) continue; // proof BEFORE read
BlockState state = world.getBlockState(pos);
if (state.isAir()) continue;
```

注：proof 语义对 baseline cell 采用 `canObserveBlock || canObserveCell`——`canObserveBlock`
对空气格恒 false（无碰撞面可命中），若只用它，被破坏的 cell（正是 missing 要统计的）
永远无法进入 LIVE 验证。这不是放宽：`canObserveCell` 是同一严格射线族对空目标格的
判定（ObservableWorldQuery 既有 API），遮挡的空气格同样不可证明。

## 结论：PASS（行为 + 源码双证据）
