#!/usr/bin/env python3
"""Windows desktop coexistence probe: foreground PID, cursor visibility and clip rectangle."""
from __future__ import annotations
import argparse, ctypes, json, time
from ctypes import wintypes
from pathlib import Path

user32=ctypes.WinDLL("user32",use_last_error=True)

class POINT(ctypes.Structure):
    _fields_=[("x",wintypes.LONG),("y",wintypes.LONG)]
class RECT(ctypes.Structure):
    _fields_=[("left",wintypes.LONG),("top",wintypes.LONG),
              ("right",wintypes.LONG),("bottom",wintypes.LONG)]
class CURSORINFO(ctypes.Structure):
    _fields_=[("cbSize",wintypes.DWORD),("flags",wintypes.DWORD),
              ("hCursor",wintypes.HANDLE),("ptScreenPos",POINT)]

def sample():
    hwnd=user32.GetForegroundWindow()
    pid=wintypes.DWORD()
    user32.GetWindowThreadProcessId(hwnd,ctypes.byref(pid))
    rect=RECT(); user32.GetClipCursor(ctypes.byref(rect))
    cursor=CURSORINFO(); cursor.cbSize=ctypes.sizeof(CURSORINFO)
    user32.GetCursorInfo(ctypes.byref(cursor))
    return {
        "ts":time.time(),"foreground_hwnd":int(hwnd),"foreground_pid":int(pid.value),
        "clip":[rect.left,rect.top,rect.right,rect.bottom],
        "cursor_visible":bool(cursor.flags&1),
        "cursor_pos":[cursor.ptScreenPos.x,cursor.ptScreenPos.y],
    }

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--out",required=True,type=Path)
    p.add_argument("--seconds",type=float,default=60)
    p.add_argument("--interval",type=float,default=.1)
    args=p.parse_args()
    args.out.parent.mkdir(parents=True,exist_ok=True)
    deadline=time.time()+args.seconds
    with args.out.open("w",encoding="utf-8") as f:
        while time.time()<deadline:
            f.write(json.dumps(sample(),ensure_ascii=False)+"\n"); f.flush()
            time.sleep(args.interval)
if __name__=="__main__":
    main()
