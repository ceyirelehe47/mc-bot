from __future__ import annotations
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]

def module(name, rel):
    spec = importlib.util.spec_from_file_location(name, ROOT / rel)
    obj = importlib.util.module_from_spec(spec); spec.loader.exec_module(obj)
    return obj

V = module('package_verify', 'tools/verify_package.py')
P = module('checker_cli_probe', 'tools/probe_checker_cli.py')

class IntegrityTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.root = Path(self.tmp.name)
        (self.root/'x.txt').write_text('original', encoding='utf-8')
        self.line = hashlib.sha256(b'original').hexdigest()+'  x.txt\n'
        (self.root/'SHA256SUMS').write_text(self.line, encoding='utf-8')
    def tearDown(self): self.tmp.cleanup()
    def test_valid(self): self.assertTrue(V.verify(self.root)['ok'])
    def test_tamper(self):
        (self.root/'x.txt').write_text('changed'); self.assertFalse(V.verify(self.root)['ok'])
    def test_missing(self):
        (self.root/'x.txt').unlink(); self.assertFalse(V.verify(self.root)['ok'])
    def test_extra(self):
        (self.root/'unknown').write_text('x'); self.assertFalse(V.verify(self.root)['ok'])
    def test_traversal(self):
        (self.root/'SHA256SUMS').write_text('0'*64+'  ../outside\n'); self.assertFalse(V.verify(self.root)['ok'])
    def test_empty(self):
        (self.root/'SHA256SUMS').write_text(''); self.assertFalse(V.verify(self.root)['ok'])
    def test_malformed(self):
        (self.root/'SHA256SUMS').write_text('looks good'); self.assertFalse(V.verify(self.root)['ok'])
    def test_duplicate(self):
        (self.root/'SHA256SUMS').write_text(self.line*2); self.assertFalse(V.verify(self.root)['ok'])
    def test_case_collision(self):
        (self.root/'SHA256SUMS').write_text(self.line+self.line.replace('x.txt','X.txt'))
        self.assertFalse(V.verify(self.root)['ok'])
    def test_missing_manifest(self):
        (self.root/'SHA256SUMS').unlink(); self.assertFalse(V.verify(self.root)['ok'])
    def test_symlink(self):
        (self.root/'x.txt').unlink()
        try: (self.root/'x.txt').symlink_to(self.root/'SHA256SUMS')
        except OSError: self.skipTest('platform disallows symlink creation')
        self.assertFalse(V.verify(self.root)['ok'])

class CliTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.root=Path(self.tmp.name)
        self.checker=self.root/'checker.py'; self.evidence=self.root/'input.json'
        self.evidence.write_text('{}',encoding='utf-8')
    def tearDown(self): self.tmp.cleanup()
    def source(self,s): self.checker.write_text(s,encoding='utf-8')
    def standard(self,accept,code):
        self.source('import json,sys\nprint(json.dumps({"entry":sys.argv[1],"accept":'+repr(accept)+',"reason":"test"}))\nsys.exit('+str(code)+')\n')
    def call(self,timeout=2): return P.invoke(self.checker,'judge-ia',self.evidence,timeout)
    def test_accept(self): self.standard(True,0); self.assertEqual(self.call()['status'],'ACCEPT')
    def test_reject(self): self.standard(False,1); self.assertEqual(self.call()['status'],'REJECT')
    def test_accept_wrong_code(self): self.standard(True,1); self.assertEqual(self.call()['status'],'ERROR')
    def test_reject_wrong_code(self): self.standard(False,0); self.assertEqual(self.call()['status'],'ERROR')
    def test_usage_not_reject(self): self.standard(False,2); self.assertEqual(self.call()['status'],'ERROR')
    def test_crash(self): self.source('raise RuntimeError("boom")'); self.assertEqual(self.call()['status'],'ERROR')
    def test_invalid_json(self): self.source('print("not-json")'); self.assertEqual(self.call()['status'],'ERROR')
    def test_wrong_bool(self): self.standard('false',1); self.assertEqual(self.call()['status'],'ERROR')
    def test_missing_reason(self):
        self.source('import sys\nprint(\'{"entry":"judge-ia","accept":false}\')\nsys.exit(1)')
        self.assertEqual(self.call()['status'],'ERROR')
    def test_wrong_entry(self):
        self.source('import sys\nprint(\'{"entry":"elsewhere","accept":false,"reason":"no"}\')\nsys.exit(1)')
        self.assertEqual(self.call()['status'],'ERROR')
    def test_duplicate_json_keys(self):
        self.source('import sys\nprint(\'{"entry":"judge-ia","accept":true,"accept":false,"reason":"no"}\')\nsys.exit(1)')
        self.assertEqual(self.call()['status'],'ERROR')
    def test_timeout(self): self.source('import time\ntime.sleep(2)'); self.assertEqual(self.call(0.1)['status'],'ERROR')
    def test_missing_checker(self): self.assertEqual(self.call()['status'],'ERROR')
    def test_reject_all_fails_positive(self):
        self.standard(False,1)
        self.assertFalse(P.evaluate(self.checker,'judge-ia',self.evidence,True,2)['ok'])
    def test_accept_all_fails_negative(self):
        self.standard(True,0)
        self.assertFalse(P.evaluate(self.checker,'judge-ia',self.evidence,False,2)['ok'])
    def test_pair_load(self):
        (self.root/'negative.json').write_text('{}')
        manifest=self.root/'pairs.json'
        manifest.write_text(json.dumps([{'id':'case','entry':'judge-ia','positive':'input.json','negative':'negative.json','purpose':'test'}]))
        self.assertEqual(len(P.load_pairs(manifest)),1)
    def test_empty_pairs(self):
        p=self.root/'pairs.json'; p.write_text('[]')
        with self.assertRaises(ValueError): P.load_pairs(p)
    def test_duplicate_pairs(self):
        (self.root/'negative.json').write_text('{}')
        p=self.root/'pairs.json'
        pair={'id':'case','entry':'judge-ia','positive':'input.json','negative':'negative.json','purpose':'test'}
        p.write_text(json.dumps([pair,pair]))
        with self.assertRaises(ValueError): P.load_pairs(p)
    def test_same_pair_file(self):
        p=self.root/'pairs.json'
        p.write_text(json.dumps([{'id':'case','entry':'judge-ia','positive':'input.json','negative':'input.json','purpose':'test'}]))
        with self.assertRaises(ValueError): P.load_pairs(p)

class ReferenceTests(unittest.TestCase):
    def test_reference_hashes(self):
        entries=json.loads((ROOT/'reference/FILE_PROVENANCE.json').read_text())
        self.assertEqual(len(entries),46)
        for row in entries:
            self.assertEqual(hashlib.sha256((ROOT/row['package_path']).read_bytes()).hexdigest(),row['sha256'],row['package_path'])
    def test_legacy_count(self): self.assertEqual(len(P.legacy_cases()),10)
    def test_pinned_source_blobs(self):
        provenance=json.loads((ROOT/'reference/review_ff29078/PROVENANCE.json').read_text())
        for row in provenance['sources']:
            raw=(ROOT/'reference/review_ff29078'/row['local_file']).read_bytes()
            self.assertEqual(hashlib.sha1(b'blob '+str(len(raw)).encode()+b'\0'+raw).hexdigest(),row['git_blob_sha1'])

if __name__=='__main__': unittest.main()
