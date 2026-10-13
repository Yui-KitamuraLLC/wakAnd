"""Stage only reviewed public files for FTP-Deploy-Action; never connect here."""

import json
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


def stage(root, env):
    plan = upload_plan(root)
    required(env, 'FTP_SERVER')
    destination = required(env, 'FTP_SERVER_DIR')
    if (not destination.startswith('/') or not destination.endswith('/wakAnd/')
            or '..' in destination.split('/') or '\\' in destination or '~' in destination):
        raise ValueError('FTP target must be an absolute path ending in /wakAnd/')
    # A fresh staging directory prevents old/private files from joining the upload.
    output = root / 'dist'
    output.mkdir(exist_ok=False)
    for name, local in plan:
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(local, target)
    # This library contains no credentials. The private config stays on the server.
    for source, target in [('Config.php', '_config.php'), ('Core.php', '_core.php'),
                           ('Database.php', '_database.php'), ('OAuthService.php', '_oauth.php'), ('Http.php', '_http.php')]:
        library = root / 'src' / source
        if library.is_symlink() or not library.is_file():
            raise ValueError('Missing or invalid runtime library')
        shutil.copyfile(library, output / target)
    # Release checksum includes the two known checkout encodings of this exact DDL.
    # Neither variant changes SQL. Admin imports may come from Windows or GitHub.
    import hashlib
    ddl = (root / 'schema/001_oauth_mysql.sql').read_bytes().replace(b'\r\n', b'\n')
    hashes = [hashlib.sha256(ddl).hexdigest(), hashlib.sha256(ddl.replace(b'\n', b'\r\n')).hexdigest()]
    (output / '_schema.php').write_text('<?php return ' + repr(hashes) + ';\n', encoding='utf-8')
    return output


if __name__ == '__main__':
    try:
        stage(Path(__file__).resolve().parents[1], os.environ)
    except Exception:
        print('Staging failed. Check the allowlist, FTP_SERVER, FTP_SERVER_DIR and fresh dist directory.', file=sys.stderr)
        sys.exit(1)
