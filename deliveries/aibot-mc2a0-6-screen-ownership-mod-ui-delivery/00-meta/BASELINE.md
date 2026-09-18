# MC-2A0.6 元数据

- 基线: experiment/mc2a0-5-background-native-screen @ 471d88a61430f28ca4f59dd3a9a60bf6adbdca2f
- 分支: experiment/mc2a0-6-screen-ownership-mod-ui
- 生产 jar (0.6 修正版): aibot-0.0.1.jar sha256 a594fa336df5ef32... (dev 构建后修正版, 同步两服)
- fixture jar: 生产 remap 版 d6654743252734dc31d7891f76c4ed6413224697884fa899434c35b9ffe80afa
  dev(named,Bob dev 客户端实际加载) 3347258f3bba02ef8cfe263fb1456dd974b9b695749206faa8ec17a3338ed468
- MC 1.21.3 / Yarn 1.21.3+build.2 / Fabric API 0.114.1+1.21.3 / Loom 1.16.2 / JDK 21
- LIVE 环境: mc2a06-live-server(25599/8765/8766, 沿用 0.5 世界装置) + mc2a06-fake-server(8767)
- 控制代理装置: mc2a06-commit-proxy.py(8768, hold_commit/hold_cancel/rewrite/inject_drop)
- JOIN 门控装置: mc2a06-join-gate.py(25598)
