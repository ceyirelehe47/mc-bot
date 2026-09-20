# -*- coding: utf-8 -*-
"""L5 假后端进程:受 rcf1_lifecycle 以 FAKE 模式拉起的长驻轻量进程。

行为由环境变量控制(注入故障):
- RCF1_FAKE_READY_S=<秒>  延迟打印 FAKE_READY(慢启动注入)
- RCF1_FAKE_DIE_S=<秒>    启动后即退出(spawn 后死亡注入)
- RCF1_FAKE_HANG=1        拒绝退出(停止失败注入;收到 SIGTERM 忽略,
                           Stop-Process -Force 仍可杀——用于验证强杀路径)
命令行含 -Drcf1.instance.marker=<token> 供 marker 扫描识别。
"""
import os
import sys
import time

ready_s = float(os.environ.get("RCF1_FAKE_READY_S", "0"))
die_s = os.environ.get("RCF1_FAKE_DIE_S")
hang = os.environ.get("RCF1_FAKE_HANG") == "1"

t0 = time.time()
if die_s is not None:
    time.sleep(float(die_s))
    sys.exit(1)

if ready_s > 0:
    time.sleep(ready_s)
print("FAKE_READY", flush=True)

try:
    while True:
        time.sleep(1)
except KeyboardInterrupt:
    if not hang:
        sys.exit(0)
