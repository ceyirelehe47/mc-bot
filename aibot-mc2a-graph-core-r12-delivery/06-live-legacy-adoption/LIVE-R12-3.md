# LIVE-R12-3 — pre-R1.2 遗留活动 id 一次性收养(id 不变, 重启幂等)

判定: **PASS**

注入 id: `ore_0123456789abcdef0123456789abcdef`(32 hex 连续旧式格式, 非 R1.1 化身格式)。
装置格: (562,68,127) 真实 iron_ore(RCON 布置, bot 遣远未观察)。

## 时间线(r12_live3_legacy.py)

1. **升级前状态**: 停服态注入(inject_legacy_opportunity.py, 任务包原版脚本)后,
   journal 无该 id 的任何 birth 收据(读收据断言)。
2. **首启收养**: R1.2 启动 → reconcile 检测"注册表有、birth 无" → 追加**恰一条**
   `resource_opportunity_birth`(seq **969**, 同 id) → id 原样不变(语义行比对)。
3. **重启幂等**: 第二次重启后 journal 仍恰 1 条 birth(无重复收养), 注册表 id 不变。
4. **Graph 同 id**: plan(r12-legacy, ref=…/opportunity/ore_0123456789abcdef0123456789abcdef)
   → `graph-ae4fc9b002c8b81c…` READY, inspect 引用同一 legacy object_id。
5. **正常关闭**: stone 替换矿格 + bot 观察 → stale 终结, birth(969) < stale(**976**),
   注册表清空——收养后的 id 按普通化身语义正常关闭。

## 自然收养的额外披露

本轮 R1.2 代码在隔离服**首次启动**时, 对 R11 时代遗留的全部 21 个活动机会
(含 R11-1 的 B 化身 `ore_4c1fc778…`, R1.1 化身格式但无 birth 收据)执行了同一收养机制
(journal seq 875-879 区间批量 birth), 全程无 id 改写、无冲突——收养机制对真实存量数据
的兼容性获得了计划外的整册验证。

## 证据文件

- drivers/r12_live3_legacy.py
- journal-receipts-legacy.txt: birth 969 → stale 976 全文
- injected-legacy-row.json: 注入行摘要
