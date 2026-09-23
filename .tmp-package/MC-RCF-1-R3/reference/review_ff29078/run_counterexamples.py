"""Offline probes against the byte-identical committed checker, not Minecraft LIVE.
All evidence below is SYNTHETIC and deliberately invalid. No remote mutation.
"""
from __future__ import annotations
import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import rcf1_checker as C

ROOT = Path(__file__).resolve().parent
EXPECTED_BLOB = '8ad34b34a0d3addf69f009544d6fb57b386dc630'
HEAD = 'ff29078ab3cd52d6cfceb928344c90e0c25ab2a7'
CANDIDATE = '304da18010fa8e1ad1faa2442225ba00d59258d9'
source = (ROOT / 'rcf1_checker.py').read_bytes()
blob = hashlib.sha1(b'blob '+str(len(source)).encode()+b'\0'+source).hexdigest()
assert blob == EXPECTED_BLOB, (blob, EXPECTED_BLOB)

def skeletal_ia():
    cases = []
    for cid, (pos, neg) in C.IA_REQUIRED.items():
        rows = []
        if pos:
            rows.append({'run_id':'synthetic-ia', 'state':'completed',
                         'reason':'server_authoritative'})
        if neg:
            rows.append({'run_id':'synthetic-ia', 'state':'failed',
                         'precondition_established':True,
                         'zero_extra_effect':True})
        cases.append({'id':cid,'results':rows})
    return {'runs':{'synthetic-ia':{'candidate':CANDIDATE}},'cases':cases}

def skeletal_g4():
    return [
        {'run':f'synthetic-b{i}', 'session_id':f'synthetic-session-{i}',
         'candidate':CANDIDATE, 'result':'PASS', 'duration_s':60,
         'chain':{key:True for key in C.G4_CHAIN_REQUIRED},
         'final_inventory':{'minecraft:wooden_pickaxe':1,
                            'minecraft:stone_pickaxe':1},
         'events':[{'kind':'unverified-placeholder','nonce':i}]}
        for i in range(1,6)
    ]

cases = []
ia = skeletal_ia()
cases.append(('IA01_no_physical_facts','judge-ia',ia,
              'All required IDs, but only success text and negative booleans; no physical facts.'))
ia_conflict = copy.deepcopy(ia)
a01 = next(c for c in ia_conflict['cases'] if c['id']=='A01')['results'][0]
a01.update(reason='server_authoritative_native_craft:minecraft:oak_planks:0->8:delta=8',
           request={'item':'minecraft:oak_planks','count':32},
           before={'inventory':{'minecraft:oak_planks':0,'minecraft:oak_log':8}},
           after={'inventory':{'minecraft:oak_planks':0,'minecraft:oak_log':8}})
cases.append(('IA02_receipt_conflicts_with_snapshots','judge-ia',ia_conflict,
              'Reason is internally consistent, but actual supplied snapshots show no craft and request is 32.'))
ia_duplicate = copy.deepcopy(ia)
ia_duplicate['cases'].insert(0,{'id':'A01','results':[{
    'run_id':'synthetic-ia','state':'outcome_unknown','reason':'unreconciled'}]})
cases.append(('IA03_duplicate_id_hides_unknown','judge-ia',ia_duplicate,
              'An earlier A01 outcome_unknown is silently overwritten by the later same-ID case.'))
ia_missing_candidate = copy.deepcopy(ia)
ia_missing_candidate['runs']={'synthetic-ia':{}}
cases.append(('IA04_candidate_absent','judge-ia',ia_missing_candidate,
              'Run registry has no candidate identity at all.'))
g4 = skeletal_g4()
cases.append(('G401_no_action_history','judge-g4',g4,
              'Five unique labels and nonce-only event lists; no observation, mining or crafting.'))
g4_conflict = copy.deepcopy(g4)
for i,r in enumerate(g4_conflict):
    r['events']=[{'kind':'mine','target':[i,100,0],'state':'failed','reason':'never_broken'},
                 {'kind':'cursor-observation','slot':'cursor','item':'minecraft:stone_pickaxe','count':1,'nonce':i}]
cases.append(('G402_history_explicitly_failed_cursor_occupied','judge-g4',g4_conflict,
              'Events explicitly say mining failed and cursor still holds the stone pickaxe, contradicting summaries.'))
g4_pregiven = copy.deepcopy(g4)
for i,r in enumerate(g4_pregiven):
    r['events']=[{'kind':'initial-inventory','inv':{'minecraft:wooden_pickaxe':1,'minecraft:stone_pickaxe':1}},
                 {'kind':'final','inv':{'minecraft:wooden_pickaxe':1,'minecraft:stone_pickaxe':1},'nonce':i}]
cases.append(('G403_tools_present_before_start','judge-g4',g4_pregiven,
              'Initial and final inventories are the same pre-given tools; chain still claims empty-start collection.'))

# Controls confirm that obvious already-fixed errors are in fact rejected.
controls=[]
bad_numbers=copy.deepcopy(ia)
next(c for c in bad_numbers['cases'] if c['id']=='A01')['results'][0]['reason']='server_authoritative_native_craft:x:0->8:delta=32'
controls.append(('CTRL01_reason_math_rejected','judge-ia',bad_numbers,'Contradictory numbers inside reason.'))
controls.append(('CTRL02_four_runs_rejected','judge-g4',g4[:4],'Only four runs.'))
missing_id=copy.deepcopy(ia); missing_id['cases']=[c for c in missing_id['cases'] if c['id']!='I08']
controls.append(('CTRL03_missing_required_case_rejected','judge-ia',missing_id,'Missing I08.'))

out=[]
for tid,entry,payload,description in cases+controls:
    p=ROOT/'synthetic_inputs'/f'{tid}.json';p.parent.mkdir(exist_ok=True)
    p.write_text(json.dumps(payload,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    process=subprocess.run([sys.executable,str(ROOT/'rcf1_checker.py'),entry,str(p)],
                           capture_output=True,text=True,timeout=10)
    try: response=json.loads(process.stdout)
    except json.JSONDecodeError: response={'parse_error':process.stdout,'stderr':process.stderr}
    accepted=response.get('accept') is True and process.returncode==0
    out.append({'id':tid,'input_kind':'SYNTHETIC_INVALID_EVIDENCE',
                'description':description,'checker_entry':entry,'expected_accept':False,
                'actual_accept':accepted,'exit_code':process.returncode,'response':response,
                'input_file':str(p.relative_to(ROOT)),
                'control':tid.startswith('CTRL')})
report={'reviewed_head':HEAD,'checker_git_blob':blob,'exact_source_blob_verified':True,
        'scope':'Offline actual CLI execution; no Windows or Minecraft LIVE execution',
        'invalid_probes':len(cases),
        'invalid_probes_incorrectly_accepted':sum(r['actual_accept'] for r in out if not r['control']),
        'rejection_controls':len(controls),
        'rejection_controls_correctly_rejected':sum(not r['actual_accept'] for r in out if r['control']),
        'results':out}
(ROOT/'checker_counterexamples.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(json.dumps({k:v for k,v in report.items() if k!='results'},ensure_ascii=False,indent=2))
for row in out:print(row['id'],row['actual_accept'],row['response'])
# Intentionally nonzero until all invalid evidence is rejected.
raise SystemExit(1 if report['invalid_probes_incorrectly_accepted'] else 0)
