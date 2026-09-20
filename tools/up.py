# -*- coding: utf-8 -*-
# MC-RCF-1-R1:旧时代环境看门狗已退役。唯一生命周期入口是
# tools/rcf1_lifecycle.py(跨进程互斥+意图预写+预算)。本文件保留为
# 历史参考,任何执行路径直接退出。
import sys
sys.stderr.write("up.py retired: use tools/rcf1_lifecycle.py\n")
sys.exit(2)
