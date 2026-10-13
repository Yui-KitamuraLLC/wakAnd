import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('deploy', Path(__file__).parents[1] / 'scripts/deploy.py')
deploy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(deploy)


class DeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'public').mkdir()
        (self.root / 'src').mkdir()
        (self.root / 'src/Config.php').write_text('<?php // No secrets')
        (self.root / 'public/index.php').write_text('<?php echo "test";')
        (self.root / 'secret.php').write_text('private')
        self.manifest(['index.php'])
        self.env = dict(FTP_SERVER='ftp.example.com', FTP_SERVER_DIR='/wakAnd/')

    def manifest(self, files):
        (self.root / 'deploy-files.json').write_text(json.dumps(files))

    def test_only_explicit_public_files_are_uploaded(self):
        (self.root / 'public/config.local.php').write_text('not in allowlist')
        self.assertEqual([name for name, _ in deploy.upload_plan(self.root)], ['index.php'])

    def test_paths_cannot_escape_or_inject_ftp_commands(self):
        for name in ['../secret.php', '/secret.php', 'sub/../../secret.php', 'a\r\nDELE x', 'sub\\file.php']:
            with self.subTest(name=name):
                self.manifest([name])
                with self.assertRaises(ValueError):
                    deploy.upload_plan(self.root)

    def test_missing_target_fails_before_staging(self):
        del self.env['FTP_SERVER_DIR']
        with self.assertRaises(ValueError):
            deploy.stage(self.root, self.env)
        self.assertFalse((self.root / 'dist').exists())

    def test_shared_root_and_wrong_case_are_rejected(self):
        for target in ['/', '/android/', '~/www/webroot/android/wakAnd/', '/android/wakand/', '/a/../wakAnd/']:
            with self.subTest(target=target):
                self.env['FTP_SERVER_DIR'] = target
                with self.assertRaises(ValueError):
                    deploy.stage(self.root, self.env)

    def test_stage_copies_only_allowlist_and_refuses_stale_output(self):
        (self.root / 'public/private.php').write_text('not public')
        self.env['FTP_SERVER_DIR'] = '/android/wakAnd/'
        output = deploy.stage(self.root, self.env)
        self.assertEqual(sorted(p.name for p in output.iterdir()), ['_config.php', 'index.php'])
        self.assertEqual((output / 'index.php').read_bytes(), (self.root / 'public/index.php').read_bytes())
        with self.assertRaises(FileExistsError):
            deploy.stage(self.root, self.env)


if __name__ == '__main__':
    unittest.main()
