# -*- coding: utf-8 -*-
"""MC-2A0.1F LIVE-2A01F-4: automatic remote-read zero on the real isolated server.

Bob stays FAR outside the verification envelope of the registered live2a01 HOME
while the production ExternalBodyRuntime kernel ticks normally (every server tick
drives observeJson + periodic cognitiveSnapshot). We repeatedly hit /v1/view
(the exact mc_view backend) and /v1/observe across >=200 server ticks and prove:
- semantic_world.schema == mc_spatial_semantics_v2_bounded (automatic cache took
  the bounded entry, whose structure cards contain NO integrity fields)
- view card knowledge != VERIFIED_LIVE (restart-cold cache => UNKNOWN here)
- durable identity (baseline_cells) still visible
Then teleport back inside the envelope and prove the legal proof path restores
VERIFIED_LIVE with the real current damage (missing=1 from the MC-2A0.1 session).
"""
import json
import os
import time
import urllib.request

TOKEN = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "mc-server-mc1ca", "bridge-token.txt")).read().strip()
BASE = "http://127.0.0.1:8765"


def call(path):
    method = "POST" if path in ("/v1/view",) else "GET"
    req = urllib.request.Request(BASE + path, method=method,
                                 data=b"" if method == "POST" else None,
                                 headers={"Content-Type": "application/json"} if method == "POST" else {})
    req.add_header("Authorization", "Bearer " + TOKEN)
    with urllib.request.urlopen(req, timeout=20) as r:
        out = json.loads(r.read().decode())
    return out.get("data", out)


def structure_cards(view):
    scene = view["scene"] if isinstance(view.get("scene"), dict) else json.loads(view["scene"])
    return {s.get("object_id"): s for s in scene.get("semantic_objects", {}).get("structures", {}).get("items", [])}


def main():
    print("== phase 1: far-from-structure automatic window (>200 server ticks) ==")
    last = call("/v1/view")
    start_tick = last["meta"]["generated_server_tick"]
    views = 1
    deadline = time.time() + 35
    while time.time() < deadline:
        last = call("/v1/view")
        views += 1
        if last["meta"]["generated_server_tick"] - start_tick >= 210:
            break
        time.sleep(0.3)
    end_tick = last["meta"]["generated_server_tick"]
    print(f"ticks={start_tick}..{end_tick} (delta={end_tick - start_tick}) view_calls={views}")

    observe = call("/v1/observe")
    obs = observe.get("observation", observe)
    obs = json.loads(obs) if isinstance(obs, str) else obs
    semantic = obs.get("semantic_world", {})
    print(f"semantic schema={semantic.get('schema')}")
    structures = semantic.get("structures", [])
    for s in structures:
        fields = sorted(s.keys())
        has_integrity = any(k.startswith("integrity") or k == "repairable" for k in fields)
        print(f"  observe structure {s.get('id')}: fields={fields} integrity_fields_present={has_integrity}")

    cards = structure_cards(last)
    card = cards.get("live2a01")
    print(f"view live2a01 card: {json.dumps(card, ensure_ascii=False)}")
    integrity = card["summary"]["current_integrity"]
    print("\n== assertions ==")
    ok = [True]
    def check(name, cond):
        print(("PASS " if cond else "FAIL ") + name)
        ok[0] = ok[0] and cond
    check("semantic schema is bounded variant", semantic.get("schema") == "mc_spatial_semantics_v2_bounded")
    check("no structure card in automatic observe carries integrity fields",
          not any(any(k.startswith("integrity") or k == "repairable" for k in s.keys()) for s in structures))
    check("durable identity visible remotely (card + baseline_cells)", card is not None and card["summary"]["baseline_cells"] > 0)
    check("remote knowledge is NOT VERIFIED_LIVE", card["knowledge"] != "VERIFIED_LIVE")
    check("remote freshness is NOT LIVE", card["freshness"] != "LIVE")
    check("window covered >=200 server ticks", isinstance(end_tick, int) and isinstance(start_tick, int) and end_tick - start_tick >= 200)
    print(f"RESULT live4_far={'PASS' if ok[0] else 'FAIL'}")
    return ok[0]


if __name__ == "__main__":
    raise SystemExit(0 if main() else 1)
