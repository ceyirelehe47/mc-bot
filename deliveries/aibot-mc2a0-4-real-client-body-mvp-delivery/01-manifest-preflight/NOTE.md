# Stage A:canonical manifest preflight
- 修复前:156 条目,第 5 行含自引用 `fad74cc3...  SHA256SUMS`
- repair_manifest.py 输出:PASS canonical SHA256SUMS: 155 entries, self excluded, all hashes valid
- Commit ee4d90f 仅改 aibot-dsh-m0/SHA256SUMS
- 终稿(D 提交后重生成):169 条目(新增 14 个任务包文件+关联),无自引用,sha256sum -c 全部 OK
