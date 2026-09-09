"""Installer safety unit tests. Synthetic fixtures are NOT a full upstream-application test."""
from __future__ import annotations
import contextlib, hashlib, importlib.util, io, pathlib, subprocess, sys, tempfile, unittest
from unittest.mock import patch
P=pathlib.Path(__file__).parent
spec=importlib.util.spec_from_file_location('apply_bridge', P/'apply_to_aibot.py'); module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)

class InstallerTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup);self.root=pathlib.Path(self.temp.name)
    def repo(self):
        subprocess.run(['git','init','-q',str(self.root)],check=True)
        subprocess.run(['git','-C',str(self.root),'config','user.name','Fixture'],check=True)
        subprocess.run(['git','-C',str(self.root),'config','user.email','fixture@example.invalid'],check=True)
        (self.root/'keep.txt').write_text('keep\n')
        subprocess.run(['git','-C',str(self.root),'add','.'],check=True)
        subprocess.run(['git','-C',str(self.root),'commit','-qm','test fixture'],check=True)
        return subprocess.check_output(['git','-C',str(self.root),'rev-parse','HEAD'],text=True).strip()
    def one_fixture(self):
        relative='Example.java';data=b'original_anchor\n'
        path=self.root/(module.PREFIX+relative);path.parent.mkdir(parents=True);path.write_bytes(data)
        return path,{relative:(module.blob_id(data), [('original_anchor','replacement_anchor',1)])}
    def test_git_blob_algorithm_matches_git(self):
        data=b'hello\n';expected=subprocess.check_output(['git','hash-object','--stdin'],input=data).decode().strip()
        self.assertEqual(module.blob_id(data),expected)
    def test_exact_anchor_replacement(self):
        self.assertEqual(module.transform('a OLD z',[('OLD','NEW',1)]),'a NEW z')
    def test_missing_anchor_fails(self):
        with self.assertRaises(ValueError):module.transform('a',[('missing','NEW',1)])
    def test_ambiguous_anchor_fails(self):
        with self.assertRaises(ValueError):module.transform('OLD OLD',[('OLD','NEW',1)])
    def test_planning_never_writes_original(self):
        path,changes=self.one_fixture()
        with patch.object(module,'CHANGES',changes):old,writes=module.plan(self.root,validate_head=False)
        self.assertEqual(path.read_bytes(),b'original_anchor\n');self.assertEqual(writes[path],b'replacement_anchor\n')
        self.assertEqual(len(old),1);self.assertGreater(len(writes),1)
    def test_blob_drift_fails_before_writes(self):
        path,changes=self.one_fixture();path.write_text('changed\n')
        with patch.object(module,'CHANGES',changes),self.assertRaisesRegex(ValueError,'blob mismatch'):module.plan(self.root,validate_head=False)
        self.assertEqual(path.read_text(),'changed\n')
    def test_existing_overlay_file_not_overwritten(self):
        path,changes=self.one_fixture();target=self.root/(module.PREFIX+'external/BridgeKernel.java');target.parent.mkdir();target.write_text('user implementation')
        with patch.object(module,'CHANGES',changes),self.assertRaisesRegex(ValueError,'already exists'):module.plan(self.root,validate_head=False)
        self.assertEqual(target.read_text(),'user implementation');self.assertEqual(path.read_text(),'original_anchor\n')
    def test_wrong_head_refuses_without_touching_worktree(self):
        self.repo()
        with self.assertRaisesRegex(ValueError,'Wrong AIBot HEAD'):module.plan(self.root)
        self.assertEqual((self.root/'keep.txt').read_text(),'keep\n');self.assertFalse((self.root/'src').exists())
    def test_dirty_tracked_checkout_refused_even_matching_head(self):
        head=self.repo();(self.root/'keep.txt').write_text('local change')
        with patch.object(module,'BASE',head),self.assertRaisesRegex(ValueError,'dirty'):module.plan(self.root)
        self.assertEqual((self.root/'keep.txt').read_text(),'local change')
    def test_all_modification_manifests_have_valid_blob_ids_and_counts(self):
        # M0 提交时即有 12 个上游锚点文件(旧断言 10 从未在本机执行过,属陈年漂移,此处对齐真实值)
        self.assertEqual(len(module.CHANGES),12)
        for name,(sha,rules) in module.CHANGES.items():
            self.assertRegex(sha,r'^[a-f0-9]{40}$');self.assertGreater(len(rules),0)
            for old,new,count in rules:self.assertTrue(old);self.assertNotEqual(old,new);self.assertGreater(count,0)
    def test_dsh_wrong_head_does_not_create_scratch_dir(self):
        self.repo();r=subprocess.run([sys.executable,str(P/'install_dsh_plugin.py'),'--repo',str(self.root)],capture_output=True,text=True)
        self.assertNotEqual(r.returncode,0);self.assertIn('HEAD mismatch',r.stderr);self.assertFalse((self.root/'scratch-aibot-body').exists())

if __name__=='__main__':unittest.main(verbosity=2)
