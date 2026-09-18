# LIVE-R12-2 — 终态收据主导旧语义快照(birth → terminal 有序, 不复活)

判定: **PASS**

装置: 同 LIVE-R12-1 装置格 (562,68,127)。

## 时间线(r12_live2_terminal.py)

1. **化身 C 诞生**: 放矿(bot 遣远)→ tp 矿旁观察 →
   `C = ore_fefc057feb30_07b497c525c74dfd`; birth 收据 seq **960** durable。
2. **含 C 快照**: 观察后语义快照留档(`r12-semantic-with-C-observed.json`)。
3. **真 stale 终态**(经正常可观察路径, 非手工写收据): RCON stone 替换矿格 + bot 同格观察 →
   observeVisibleBlock 的 R2.1/R1.2 路径: `resourceOpportunityStale` 收据先行
   (kernel.recordOpportunityResolution → journal fsync)→ 成功后才移除语义条目。
   stale 收据 seq **962**; 注册表 C 出表(语义文件实测)。
   顺序的源码形态由契约测试 `staleReceiptMustBeDurableBeforeSemanticRemoval` 锁定:
   receipt 在前, `OPPORTUNITIES.remove(key)` 在后。
4. **注入旧快照**: 停服后将含 C 的观察版快照覆盖语义文件(即"crash 前语义未及落盘"窗口)。
5. **纯启动即停重启** → **C 不复活**: 持久化注册表无 C、装置格为空。
   lifecycle 重放为 `birth(960) → stale(962)` 有序: terminal 收据把 activeBirths 中的 C
   关闭, 不存在"birth 总是复活"。

## 证据文件

- drivers/r12_live2_terminal.py
- journal-receipts-C.txt: birth 960 → stale 962 全文
- snapshot-hashes.txt: 注入快照 sha256 + 重启后注册表含 C=False
- r12-semantic-with-C.json / r12-semantic-with-C-observed.json: 两态快照原件
