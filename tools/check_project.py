"""Lightweight source/package checks. Does not read the private runtime."""
from pathlib import Path
import ast
import json
import re
import shutil
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    manifest = ET.parse(ROOT/'android/AndroidManifest.xml').getroot()
    ns = '{http://schemas.android.com/apk/res/android}'
    assert manifest.get('package') == 'local.avr.codex.copy'
    assert manifest.get(ns+'versionName') == '0.6.6-copy'
    provider = manifest.find('application/provider')
    assert provider.get(ns+'exported') == 'false'
    assert provider.get(ns+'grantUriPermissions') == 'true'
    assert manifest.find('application').get(ns+'allowBackup') == 'false'
    lock = json.loads((ROOT/'tools/runtime.lock.json').read_text(encoding='utf-8'))
    assert lock['version'] == '0.165.0'
    assert len(lock['assets']) == 2
    assert all(re.fullmatch('[a-f0-9]{64}', a['sha256']) for a in lock['assets'])
    public_files = []
    for folder in ['android','runtime','tools','tests','docs','.github','phone-tools']:
        for path in (ROOT/folder).rglob('*'):
            if not path.is_file() or '__pycache__' in path.parts:
                continue
            assert path.name not in ['auth.json','profile.json','identity.enc','identity.pub','password.txt']
            assert path.suffix not in ['.key','.p12','.jks','.keystore','.log','.db']
            if path.suffix == '.py':
                ast.parse(path.read_text(encoding='utf-8'), filename=str(path.relative_to(ROOT)))
            public_files.append(path)
    for name in ['run','docker']:
        assert b'\r\n' not in (ROOT/'runtime'/name).read_bytes(), 'Android shell files require LF'
    node = shutil.which('node')
    if not node:
        raise RuntimeError('Node.js is required for JavaScript syntax checks')
    for js in [ROOT/'android/assets/ui/chat.js', ROOT/'tests/ui.test.js']:
        subprocess.run([node,'--check',str(js)],check=True)
    print(json.dumps({'status':'PASS','public_source_files':len(public_files),'version':manifest.get(ns+'versionName')}))


if __name__ == '__main__':
    main()
