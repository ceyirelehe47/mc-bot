# LIVE-R2-5A/5B｜Resource Opportunity —— PASS（各含记录性缺陷）

## LIVE-R2-5A 挖石头途中看见矿 —— PASS
1. resident perception 在原任务进行中记录：采石 gather 运行期间产生 5 条 ACTIONABLE 注入（seq 143/144/147/148/149，时间戳经 DSH 时间线交叉验证在任务运行窗口内）
2. 事件 1:1：6 条 ACTIONABLE ↔ 6 条注入一一对应；7 条 BLOCKED 零注入（语义正确）
3. 原 execution（采石 b82a9fa9）id/ownership 未被机会机制篡改（事件携带原 execution_id；任务自身跑到 gather_timeout 终态）
4. 任务结束后精确消费：mc_mine_opportunity(ore_f4188c965b @ 234,117,51 暴露矿) 63 ticks 完成 接近→挖掘→PICKUP→入包→销账
5. 缺陷记录：嵌岩矿（无暴露可达面）ACTIONABLE 后 visible_but_not_reachable 永久悬置（4 种接近方式实测全失败、不销账不降级、last_seen 反复刷新）——"僵尸机会"
6. 缺陷记录：远距机会首次 mine 因 pathfinding_throttled 2 ticks 放弃；按验收文档以 mc_goto 分段靠近 seen_from 规避

## LIVE-R2-5B deferred diamond —— PASS
1. 无铁镐时 diamond_ore (236,117,51) 暴露可见 → 记录 BLOCKED/insufficient_tool/required iron_pickaxe
2. 离开+restart 后同 id（ore_b26605897966360db15fddec48bcfd01）仍存在
3. 给 iron_pickaxe → 13 条 BLOCKED 全部重估 ACTIONABLE + resource_opportunity_actionable 1:1 推送（断连窗口积压延迟投递，DSH 补偿确认后自行订正初判——投递延迟不等于未产生）
4. seen_from 保留
5. mc_mine_opportunity 只挖那一颗：coal 1 未变、cobblestone 31 未变、其余 13 条机会逐条原样（无换挖）
6. 矿块消失 + diamond drop 入包（diamond=1，站上掉落点拾取）+ 机会销账
7. 缺陷记录：known_resource_pickup_timeout——销账先于拾取完成，链路在"挖掉"与"入包"间可断且不可经 mine_opportunity 重试（本次环境放置救回）；goto 3 格容差无法亚格走位踩掉落物
