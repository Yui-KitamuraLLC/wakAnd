"""Stage only reviewed public files for FTP-Deploy-Action; never connect here."""

import json
import hashlib
import argparse
import os
from pathlib import Path, PurePosixPath
import shutil
import sys


def upload_plan(root):
    public = root / 'public'
    entries = json.loads((root / 'deploy-files.json').read_text(encoding='utf-8'))
    if not isinstance(entries, list) or not entries or len(entries) != len(set(entries)):
        raise ValueError('Invalid deployment allowlist')
    plan = []
    for name in entries:
        if not isinstance(name, str) or not name or any(c in name for c in '\r\n\\'):
            raise ValueError('Invalid public file name')
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or relative.as_posix() != name:
            raise ValueError('Invalid public file path')
        local = public / name
        if any(p.is_symlink() for p in [local, *local.parents]):
            raise ValueError('Symlinks must not be deployed')
        if not local.is_file() or not local.resolve().is_relative_to(public.resolve()):
            raise ValueError('Public file missing or outside public directory')
        plan.append((name, local))
    return plan


def required(env, name):
    value = env.get(name, '')
    if not value or any(c in value for c in '\r\n\0'):
        raise ValueError(f'Missing or invalid {name}')
    return value


def vendor_plan(root):
    manifest = json.loads((root / 'sodium-compat-manifest.json').read_text(encoding='utf-8'))
    if (manifest.get('package') != 'paragonie/sodium_compat' or manifest.get('version') != '2.5.2'
            or manifest.get('commit') != '3246b36803c177847e677862fedeb7f937869adb'):
        raise ValueError('Unreviewed crypto dependency')
    entries = manifest.get('files')
    if not isinstance(entries, dict) or not entries:
        raise ValueError('Missing crypto file manifest')
    directory = root / 'src/_vendor/sodium_compat'
    actual = {p.relative_to(directory).as_posix() for p in directory.rglob('*') if p.is_file()}
    if actual != set(entries):
        raise ValueError('Unexpected or missing dependency files')
    plan = []
    for name, checksum in entries.items():
        path = PurePosixPath(name)
        if (path.is_absolute() or '..' in path.parts or '\\' in name or path.as_posix() != name
                or not (name in ('LICENSE', 'autoload.php', 'src/Core/Curve25519/README.md') or
                        (name.startswith(('src/', 'lib/')) and name.endswith('.php')))):
            raise ValueError('Invalid dependency file path')
        source = directory / name
        if any(p.is_symlink() for p in [source, *source.parents]) or not source.is_file():
            raise ValueError('Invalid dependency file')
        if hashlib.sha256(source.read_bytes()).hexdigest() != checksum:
            raise ValueError('Dependency checksum mismatch')
        plan.append(('_vendor/sodium_compat/' + name, source))
    return plan


def stage(root, env, output=None):
    plan = upload_plan(root)
    required(env, 'FTP_SERVER')
    destination = required(env, 'FTP_SERVER_DIR')
    if (not destination.startswith('/') or not destination.endswith('/wakAnd/')
            or '..' in destination.split('/') or '\\' in destination or '~' in destination):
        raise ValueError('FTP target must be an absolute path ending in /wakAnd/')
    dependency = vendor_plan(root)
    # A fresh staging directory prevents old/private files from joining the upload.
    output = Path(output) if output is not None else root / 'dist'
    output.mkdir(exist_ok=False)
    for name, local in plan:
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(local, target)
    # This library contains no credentials. The private config stays on the server.
    for source, target in [('Config.php', '_config.php'), ('Core.php', '_core.php'),
                           ('Database.php', '_database.php'), ('OAuthService.php', '_oauth.php'), ('Http.php', '_http.php'), ('Admin.php', '_admin.php'),
                           ('_crypto.php', '_crypto.php')]:
        library = root / 'src' / source
        if library.is_symlink() or not library.is_file():
            raise ValueError('Missing or invalid runtime library')
        shutil.copyfile(library, output / target)
    for name, source in dependency:
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, target)
    # Release checksum includes the two known checkout encodings of this exact DDL.
    # Neither variant changes SQL. Admin imports may come from Windows or GitHub.
    ddl = (root / 'schema/001_oauth_mysql.sql').read_bytes().replace(b'\r\n', b'\n')
    hashes = [hashlib.sha256(ddl).hexdigest(), hashlib.sha256(ddl.replace(b'\n', b'\r\n')).hexdigest()]
    (output / '_schema.php').write_text('<?php return ' + repr(hashes) + ';\n', encoding='utf-8')
    return output


if __name__ == '__main__':
    try:
        parser = argparse.ArgumentParser()
        parser.add_argument('--output', type=Path, help='Fresh review directory; default: authWeb/dist')
        stage(Path(__file__).resolve().parents[1], os.environ, parser.parse_args().output)
    except Exception:
        print('Staging failed. Check the allowlist, FTP_SERVER, FTP_SERVER_DIR and fresh dist directory.', file=sys.stderr)
        sys.exit(1)
