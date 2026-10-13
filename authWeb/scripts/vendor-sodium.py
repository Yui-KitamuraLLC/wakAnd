"""Fetch the reviewed immutable sodium_compat runtime; never installs server software."""
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import urllib.request
import zipfile

VERSION = '2.5.2'
COMMIT = '3246b36803c177847e677862fedeb7f937869adb'
TAG_OBJECT = '5cbae9d0ad987e0104e390d74cca92a128777aa8'
ARCHIVE_SHA256 = '7351bd9281771223b7bdb1763cfe5b9f0f38689ea49a9e3649a41b6034c64750'
URL = f'https://codeload.github.com/paragonie/sodium_compat/zip/{COMMIT}'


def vendor(root):
    destination = root / 'src/_vendor/sodium_compat'
    if destination.exists():
        raise ValueError('Refuse to replace an existing dependency')
    with urllib.request.urlopen(URL, timeout=60) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise ValueError('Archive too large')
    if hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise ValueError('Archive checksum does not match reviewed bytes')
    files = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for entry in archive.infolist():
            if entry.is_dir():
                continue
            path = PurePosixPath(entry.filename)
            if path.is_absolute() or '..' in path.parts or path.parts[0] != f'sodium_compat-{COMMIT}':
                raise ValueError('Unexpected archive path')
            name = PurePosixPath(*path.parts[1:]).as_posix()
            if name in ('autoload.php', 'LICENSE') or name.startswith(('src/', 'lib/')):
                if name in files or (entry.external_attr >> 16) & 0o170000 == 0o120000:
                    raise ValueError('Duplicate or symlink entry')
                files[name] = archive.read(entry)
    if not all(name in files for name in ('autoload.php', 'LICENSE', 'src/Compat.php', 'lib/php72compat.php')):
        raise ValueError('Missing dependency files')
    destination.mkdir(parents=True)
    for name, contents in files.items():
        target = destination / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(contents)
    manifest = {'package': 'paragonie/sodium_compat', 'version': VERSION, 'commit': COMMIT,
                'tag_object': TAG_OBJECT, 'archive_url': URL, 'archive_sha256': hashlib.sha256(data).hexdigest(),
                'files': {name: hashlib.sha256(contents).hexdigest() for name, contents in sorted(files.items())}}
    (root / 'sodium-compat-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'Vendored sodium_compat {VERSION}: {len(files)} reviewed runtime files; no server changes.')


if __name__ == '__main__':
    vendor(Path(__file__).resolve().parents[1])
