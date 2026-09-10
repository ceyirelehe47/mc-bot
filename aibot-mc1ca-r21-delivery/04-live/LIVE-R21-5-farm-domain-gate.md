# LIVE-R21-5｜Farm exact-mask mutation gate —— PASS

环境同 LIVE-R21-1。fixture：沿用已注册 r2farm（center -22,112,26，radius 5，
crop=wheat，**5 个 exact connected cells**：(-22,112,24)(-22,112,25)(-22,112,26)
(-21,112,25)(-20,112,25)）。

## 步骤与结果

1. **mask 内成熟作物可正常处理**：在注册格内放置成熟的
   (wheat[age=7])，例如 (-21,113,25) ——（另两格由现场已有作物承担）。
   调 `mc_tend_farm {"name":"r2farm"}` → execution `1e205475` → **completed**。
   日志：`event=harvest pos=(-22,113,24)/(-22,113,26)/(-20,113,25)` +
   `event=plant`（割后补种），mask 内 farmland 保持。
2. **mask 外必须被拒绝**：在 mask 外 5 格 (≈ -17,113..114,25) 种植成熟小麦；
   tend_farm 运行后该作物**完好无损**（`execute if block -17 114 25
   wheat[age=7]` 命中），未被 reserved external farm path 修改。
3. **HOME 保护不受影响**：r2farm 运行不触碰 r2home；(26,113,26) chest
   与 r2home 全格保持（LIVE-R21-1 复核一致）。

## 代码级 gate（对应 R21-10）

- `FarmAction.till/plant/harvest` 入口调用
  `SemanticWorldRegistry.farmMutationDenial(bot, cell, kind)`；
- reserved 身体 + cell（或 crop 格的下一格 farmland）不在任何注册 mask 内 →
  `farm_mutation_outside_registered_mask:<kind>@<pos>` + BotLog `farm_mutation_denied`
  + 世界零改动；
- 非 reserved（legacy/内部脑）模式返回 null（上游行为不变，R21-11 隔离）。

## 证据

- execution：`1e205475-2a98-4065-ba88-aeddda0a6d1f`（completed）
- 服务器日志：server-r21-live.log（harvest/plant 事件坐标全在 5 格 mask 内；
  无任何 mask 外 mutation）
- GameTest 侧对应：`r21ReservedFarmMutationOutsideMaskIsRejected` /
  `r21RegisteredFarmMutationInsideMaskStillWorks`
