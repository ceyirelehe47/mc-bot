# 04-live-binding 中间产物说明（独立验收备注 N1 补注）

`LIVE-1-result.json` 是 LIVE-1 阶段A 在线运行的**中间产物**（21:16:49 写出）：
其中 A12 `ok=false` 是当时断言写法缺陷（payload 子串经外层 json.dumps 转义后失配），
并非行为失败。权威门文件为 `LIVE-1-journal-offline-result.json`（21:19:51，修正断言后
对同一 journal sha256=f475529b… 的离线复验，A11-A14 全 PASS）；阶段B 权威门文件
`LIVE-1-phaseB-result.json`（B1-B10 全 PASS）。seq991 body_changed 帧内容
（previous=旧 UUID / current=bob 四字段）已由独立验收 subagent 二次解析确认。
