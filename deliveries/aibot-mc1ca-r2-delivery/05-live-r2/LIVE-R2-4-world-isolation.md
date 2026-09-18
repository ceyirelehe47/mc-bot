# LIVE-R2-4｜双存档隔离 / mismatch fail-closed —— PASS

save A：mc-server-mc1ca/world_r2（world-id 2eadb4ef-61bb-4657-ab6e-94d50141bbd1，r2home/digwall/r2home-nether + r2farm + 14 opportunities）
save B：mc-server-mc1ca-b/world_r2（全新生成 world-id 9558480e-0eb7-4a31-9c86-7be8a0ef4377，独立端口 25566/RCON 25576/桥 8766）

## 判据
1. world-id 不同 ✓（2eadb4ef vs 9558480e）
2. semantic registry 不串 ✓：B 注册 isob（经桥 REST lease+X-Control-Token 程序化路径，与 DSH 同协议）；A registry 保持 [r2home, digwall, r2home-nether] 且无 isob，B registry 仅 [isob]；两文件物理隔离于各自 save 目录
3. mismatch fail-closed ✓：将 A 的 v2 registry 植入 B（保留 B 的 world-id 文件）→ 重启 B → 启动即崩溃退出：
   `java.lang.IllegalStateException: external_bridge_start_failed_closed
    Caused by: java.io.IOException: semantic_registry_invalid
    Caused by: java.lang.IllegalArgumentException: world_id_mismatch`
   桥完全拒绝启动，A 的 HOME/FARM/opportunity 零加载（比"空加载"更强的 fail-closed）
   证据：mc-server-mc1ca-b/logs/latest.log 尾部异常栈

## 附注
- B 的 isob registry 备份于 mc-server-mc1ca-b/registry-backup-isob.json
- 桥 REST 协议细节：静态 token 认证 + POST /v1/lease（X-Owner-Id）+ X-Control-Token 携带 lease token 调执行端点（30s 过期）
