# LIVE-2A0-2｜hidden evidence no leak（实机 PASS）

环境：同 LIVE-2A0-1。Bob 位于 (53,111,5)。

## fixture（RCON，显式 replace 模式）
- 清场：`fill 55 110 6 59 114 10 air`
- 石墙：`fill 56 110 6 56 113 10 minecraft:stone replace`（20 块，Bob 与矿之间）
- 钻石矿：`setblock 58 111 8 minecraft:diamond_ore`（距 Bob ≈5.8 格，radius 8 内）

## 遮挡态：不可见（NO_LEAK）
```text
local(8, blocks).histogram: stone=25, cobblestone=4, ..., diamond_ore=None
NO_LEAK=PASS visible_blocks=186 radius_effective=8
```
（stone=25 = 20 墙块 + 地面污染 5；diamond_ore 完全不在直方图/样本中。）

## 暴露态：合法可见后出现
```text
fill 56 110 6 56 113 10 minecraft:air replace
local(8, blocks).histogram: diamond_ore=1
EXPOSED=PASS
```

## strict capability log（server-2a0.log）
```text
[AIBot] ACTION event=capability_decision bot=Bob {profile=strict_survival,
 capability=HIDDEN_BLOCK_SCAN, context=inspect_local, reason=DENIED_STRICT_SURVIVAL, allowed=false}
```
inspect_local 与 perception 走同一能力门（context 独立），strict_survival 下 hidden scan
denied 决策照常记录——SEC-1/SEC-2 同时满足。

结论：同一几何中遮挡方块不泄漏、暴露后可见、能力日志显示 hidden scan 仍 denied。
