# 工具使用

`verify_package.py` 只校验ZIP解压目录的文件清单与SHA256，不执行游戏或证明验收通过。

从包根目录运行：

```text
python tools/verify_package.py
python tools/probe_checker_cli.py --checker <仓库/tools/rcf1_checker.py> --report <仓库外/probe.json>
```

第二条对ff29078旧checker预期检出7次误接受，退出1；3个旧拒绝对照仍拒绝。探针输出非零不是包损坏。修复目标是新主checker，不是包内历史副本。

当前探针调用CLI：`python checker.py entry input.json`。要求stdout为单个JSON并明确entry、accept布尔及reason；通过exit0，明确拒绝exit1；用法/异常/崩溃/超时等记ERROR。生产接口重构时可用薄调用适配器，但不得在适配器另写判定或补事实。

另运行成对正反例：

```text
python tools/probe_checker_cli.py --checker <最终主入口.py> --pairs <pairs.json> --pairs-only --report <pair-report.json>
```

pairs文件是列表，每项有id、entry、positive、negative和purpose；positive/negative是相对于pairs文件的证据路径。它只配置本探针，不固定应用schema。正例必须经过真实采集，反例是保留正确格式且改变一个验收语义的副本；不得复制无效旧示例冒充真实正例。适配器/格式拒绝不是语义修复，记录拒绝具体原因。

两种模式都不能证明全部Minecraft验收完成。历史10个拒绝全部绿而没有有效正例仍然只是兼容性拒绝门；成对测试防全拒绝器，但不能代替独立审查这些“正例”是否真实。工具不限制目标checker的Shell权限，只运行明确指定的本地代码，使用已审查的离线入口，不传秘密环境。

执行包测试：`PYTHONDONTWRITEBYTECODE=1 python -m unittest discover -s package_tests -v`。Windows可用环境变量同名设置，避免生成包内未列入清单的pycache。所有输出写在临时/仓库外目录，参考原件不改。
