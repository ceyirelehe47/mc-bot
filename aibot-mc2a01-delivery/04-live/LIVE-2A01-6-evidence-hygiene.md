# LIVE-2A01-6｜evidence hygiene

## 旧 token 状况与轮换（任务书 RESULT 第 18 项）

MC-2A0 交付物 `aibot-mc2a0-delivery/04-live/dsh-web-2a0.log` 第 2 行包含当时实例的
真实 DSH web token（下划线 jTZ 开头的 48 字符随机串；全文值只存在于 6bd9d70 历史
提交，本树已 redact）。

处理：

1. **实例**：该 token 属于 MC-2A0 LIVE 时的 dsh web 进程，实例已终止
   （本轮 23:43 前后所有 dsh/node 实例均为新起短命进程）。
2. **轮换验证（VERIFIED-ROTATION）**：本轮启动新 dsh web 实例，boot token 为
   AEV9Jvkg 开头的新随机串（与旧值不同）——DSH web token 为每实例内存态随机值，
   无持久化，旧值随旧进程终止失效。新实例取 token 后立即终止，token 同样失效。
3. **历史**：不重写 git 历史（历史提交保留原样，符合任务书 §P1-5）。
4. **当前分支工作树修复**：sanitize 脚本对旧交付目录亦执行 redact
   （本提交起 HEAD 树不再携带该 token 字面值；历史提交中仍存在，已记录）。

## sanitize_evidence.py（deterministic 脚本）

`scripts/sanitize_evidence.py`：对目录树全部文本文件做幂等 redaction（跳过脚本自身）。
识别的凭据形态（此处用文字描述避免自匹配）：

1. URL 查询参数中的 token 字段（问号 token 形式）及其 16 位以上随机值；
2. Authorization 头的 Bearer 凭据；AIBOT_BRIDGE_TOKEN 环境变量赋值；
3. api_key 赋值；cookie 头整行；
4. mc1ca-isolated-token 前缀的桥 token 字面值；
5. user_2y 开头的 commandcode key 样式。

命中一律替换为尖括号 REDACTED 标记；已替换的不再变化（幂等）。
已对 `aibot-mc2a01-delivery/` 全树执行；并对旧 `aibot-mc2a0-delivery/` 执行
（redact 其中的 dsh web token 字面值）。

## 最终 delivery tree secret scan

`05-audit/secret-scan.txt` 记录模式扫描结果（模式与任务书 §8 LIVE-6 一致：
token 字段、Authorization 头、Bearer 前缀、AIBOT_BRIDGE_TOKEN、api_key、cookie 头）。
允许命中：REDACTED 标记与文档中的字段名说明（如本文件、任务书引用）；
不允许真实 secret 值。

## 结论：PASS
