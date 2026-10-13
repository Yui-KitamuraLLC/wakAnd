import importlib.util
import json
import hashlib
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
        for source in ['Config', 'Core', 'Database', 'OAuthService', 'Http', 'Admin', '_crypto']:
            (self.root / ('src/' + source + '.php')).write_text('<?php // No secrets')
        (self.root / 'schema').mkdir()
        (self.root / 'schema/001_oauth_mysql.sql').write_bytes(b'-- test schema\n')
        (self.root / 'public/index.php').write_text('<?php echo "test";')
        (self.root / 'secret.php').write_text('private')
        self.manifest(['index.php'])
        self.vendor = self.root / 'src/_vendor/sodium_compat'
        self.vendor.mkdir(parents=True)
        for name in ['autoload.php', 'LICENSE']:
            (self.vendor / name).write_bytes(b'reviewed dependency fixture')
        self.vendor_manifest = {'package': 'paragonie/sodium_compat', 'version': '2.5.2',
                                'commit': '3246b36803c177847e677862fedeb7f937869adb',
                                'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in self.vendor.iterdir()}}
        self.write_vendor_manifest()
        self.env = dict(FTP_SERVER='ftp.example.com', FTP_SERVER_DIR='/wakAnd/')

    def manifest(self, files):
        (self.root / 'deploy-files.json').write_text(json.dumps(files))

    def write_vendor_manifest(self):
        (self.root / 'sodium-compat-manifest.json').write_text(json.dumps(self.vendor_manifest))

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
        self.assertEqual(sorted(p.name for p in output.iterdir()), ['_admin.php', '_config.php', '_core.php', '_crypto.php', '_database.php', '_http.php', '_oauth.php', '_schema.php', '_vendor', 'index.php'])
        self.assertEqual((output / 'index.php').read_bytes(), (self.root / 'public/index.php').read_bytes())
        self.assertEqual((output / '_vendor/sodium_compat/LICENSE').read_bytes(), (self.vendor / 'LICENSE').read_bytes())
        with self.assertRaises(FileExistsError):
            deploy.stage(self.root, self.env)

    def test_dependency_tamper_or_extra_file_fails_before_staging(self):
        (self.vendor / 'autoload.php').write_bytes(b'modified')
        with self.assertRaises(ValueError):
            deploy.stage(self.root, self.env)
        self.assertFalse((self.root / 'dist').exists())
        (self.vendor / 'autoload.php').write_bytes(b'reviewed dependency fixture')
        (self.vendor / 'private.php').write_text('must not enter deployment')
        with self.assertRaises(ValueError):
            deploy.stage(self.root, self.env)
        self.assertFalse((self.root / 'dist').exists())

    def test_dependency_paths_and_version_are_rejected(self):
        for name in ['../private.php', '/private.php', 'src/../../private.php', 'src\\file.php', '.env']:
            with self.subTest(name=name):
                self.vendor_manifest['files'] = {name: '0' * 64}
                self.write_vendor_manifest()
                with self.assertRaises(ValueError):
                    deploy.vendor_plan(self.root)
        self.vendor_manifest['version'] = 'unreviewed'
        self.write_vendor_manifest()
        with self.assertRaises(ValueError):
            deploy.vendor_plan(self.root)

    def test_real_dependency_is_complete_and_byte_identical(self):
        root = Path(__file__).parents[1]
        plan = deploy.vendor_plan(root)
        self.assertEqual(len(plan), 49)
        self.assertIn('_vendor/sodium_compat/LICENSE', [name for name, _ in plan])

    def test_release_contains_no_schema_or_private_configuration(self):
        root = Path(__file__).parents[1]
        plan = [name for name, _ in deploy.upload_plan(root)]
        self.assertEqual(sorted(plan), ['.htaccess', 'admin.php', 'config-check.php', 'index.php'])
        self.assertNotIn('oauth.example.php', plan)
        self.assertNotIn('001_oauth_mysql.sql', plan)
        self.assertNotIn('admin-access.php', plan)
        self.assertNotIn('generated-keys.php', plan)


if __name__ == '__main__':
    unittest.main()
