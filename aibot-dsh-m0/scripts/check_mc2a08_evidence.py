#!/usr/bin/env python3
"""Validate MC-2A0.8 omnidirectional semantic-perception LIVE evidence."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


def close(a: float,b: float,tolerance: float=.01)->bool:
    return abs(float(a)-float(b))<=tolerance


def main()->None:
    parser=argparse.ArgumentParser()
    parser.add_argument("--evidence",required=True,type=Path)
    parser.add_argument("--out",type=Path)
    args=parser.parse_args()
    data=json.loads(args.evidence.read_text(encoding="utf-8"))

    attestation=data["freeze_attestation"]
    omni=data["omni_behind"]
    occlusion=data["occlusion"]
    strict=data["strict_fov"]
    blocks=data["visible_blocks"]
    authority=data["crosshair_authority"]
    session=data["session_reset"]
    bounds=data["bounds"]

    checks={
        "attestation_v2":attestation["attestation_version"]==2,
        "attestation_pass":attestation["pass"] is True,
        "attestation_exact_head":attestation["audited_head"]
            =="090cd8b5de2bdaeddd1096f64e5238b92faacec5",

        "omni_mode":omni["mode"]=="omni_semantic",
        "omni_coverage_360":int(omni["coverage_degrees"])==360,
        "behind_current_visible":omni["knowledge"]=="CURRENT_VISIBLE"
            and omni["line_of_sight"] is True,
        "behind_sector":omni["sector"] in {
            "BACK","BACK_LEFT","BACK_RIGHT"},
        "hostile_or_item_detected":omni["category"] in {
            "HOSTILE","ITEM","PLAYER","PASSIVE"},
        "observe_did_not_turn_body":close(
            omni["yaw_before"],omni["yaw_after"],.01)
            and close(omni["pitch_before"],omni["pitch_after"],.01),

        "wall_hides_current_state":occlusion["current_visible_behind_wall"] is False,
        "memory_is_explicit":occlusion["last_known_present"] is True
            and occlusion["last_known_line_of_sight"] is False
            and occlusion["last_known_knowledge"]=="LAST_KNOWN",
        "memory_expires":occlusion["expired_after_bound"] is True,
        "hidden_ore_not_leaked":occlusion["hidden_ore_present"] is False,

        "strict_mode":strict["mode"]=="strict_player_fov",
        "strict_excludes_behind":strict["behind_present"] is False,

        "barrel_visible":blocks["barrel_category"]=="INTERACTABLE",
        "hazard_visible":blocks["hazard_category"]=="HAZARD",
        "visible_ore_awareness_only":blocks["ore_category"]=="RESOURCE_SURFACE"
            and blocks["ore_actionability"]=="awareness_only",

        "awareness_before_action":authority["awareness_present_before"] is True,
        "no_opportunity_before_crosshair":int(
            authority["resource_opportunities_before"])==0,
        "opportunity_after_crosshair":int(
            authority["resource_opportunities_after"])>=1,
        "physical_action_completed":authority["mine_execution_state"]=="completed",

        "session_reset":session["old_memory_after_new_game_session"] is False,
        "dimension_reset":session["old_memory_after_dimension_change"] is False,

        "view_under_limit":int(bounds["scene_bytes"])<=32768,
        "entity_bound":int(bounds["entity_items"])<=20,
        "block_bound":int(bounds["block_items"])<=16,
        "entity_truncation_explicit":(
            not bounds["entity_truncated"]
            or int(bounds["entity_omitted_count"])>0),
        "block_truncation_explicit":(
            not bounds["block_truncated"]
            or int(bounds["block_omitted_count"])>0),
        "tool_count_29":int(data["dsh_tool_count"])==29,
    }
    result={
        "checks":checks,
        "failed":[key for key,value in checks.items() if not value],
        "pass":all(checks.values()),
    }
    encoded=json.dumps(result,ensure_ascii=False,indent=2)+"\n"
    if args.out:
        args.out.parent.mkdir(parents=True,exist_ok=True)
        args.out.write_text(encoded,encoding="utf-8")
    print(encoded,end="")
    if not result["pass"]:
        raise SystemExit(1)


if __name__=="__main__":
    main()
