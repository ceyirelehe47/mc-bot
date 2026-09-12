# -*- coding: utf-8 -*-
"""R1 closure LIVE 驱动: 读取图列表/详情(只读, 不占租约)。"""
import importlib.util, json, sys

_spec = importlib.util.spec_from_file_location("graph_live", r"D:\code\mc-experiment\graph_live.py")
gl = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(gl)

def graph_list():
    return gl.call("GET", "/v1/graphs")

def graph_inspect(graph_id):
    # 与上轮一致: 查询参数需双重编码
    import urllib.parse
    q = urllib.parse.quote(graph_id, safe="")
    return gl.call("GET", "/v1/graphs/" + q + "/inspect")

def status():
    return gl.call("GET", "/v1/status")

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    if cmd == "status":
        print(json.dumps(status(), ensure_ascii=False, indent=1))
    elif cmd == "list":
        print(json.dumps(graph_list(), ensure_ascii=False, indent=1))
    elif cmd == "inspect":
        print(json.dumps(graph_inspect(sys.argv[2]), ensure_ascii=False, indent=1))
