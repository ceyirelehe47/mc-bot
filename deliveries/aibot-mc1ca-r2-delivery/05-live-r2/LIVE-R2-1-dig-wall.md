# LIVE-R2-1｜DIG 路径不得拆 HOME —— PASS

场景：digwall 注册 bounds(-49,107,-16 → -17,114,16)，圆石墙 x=-30, y113..114, z=-13..13（54 格，顶格 y115 已移除以完整落入 bounds）。Bob 起点 (-32.5,108,0.53)。

## 验证方法（clone + execute if blocks 整体逐格对比，非破坏性）
- before: `clone -30 113 -13 -30 114 13 -60 113 -13` → 克隆 54 格；`execute if blocks -60.. -30.. all` → 一致（条件 setblock 回显）
- after×2（goto 终态后 / gather 终态后）：同命令再次全等 → **墙 0 变化**
- 注意：gather 后首次复验失败系 Bob 跑到 x=298 导致出生点区块卸载（if block 在未加载区块不匹配）；`forceload add -80 -80 80 80` + 召回 Bob 后复验全等。见会话记录。

## 结果
1. mc_goto(-24,113,0, allow_terrain_changes=true) → failed / move_dig_no_progress（223 ticks，位移 4.5 格后卡死于墙前）——未破墙
2. 补充 mc_gather(cobblestone,1) → failed / no_resource_after_explore（5421 ticks，探索 327 格后放弃）——受保护圆石从未进入可采清单
3. 服务器全程存活，Bob 未死亡
4. 物理挖掘门直接拒绝证据由 GameTest `mc1car2gametests.r2physicalminingcontrollercannotbypasshome` 提供（600/600 通过之列）；实机两条路径中受保护 cell 均在目标选择层即被排除，未推进到物理挖掘阶段（与多层防线设计一致）

## 副产物
- gather SURVEY 途中 resident perception 自动记录 4 条 resource_opportunities（1 iron_ore + 3 coal_ore，全 BLOCKED/insufficient_tool）——LIVE-R2-5A 的先行证据
- Bob 被召回 (-9.5,113,0.5)；forceload -80..80 已保持场地区块常载
