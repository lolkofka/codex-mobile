"""Prepare pinned ARM64 Codex binaries and install a private root runtime over ADB."""
from pathlib import Path
import argparse
import hashlib
import json
import shlex
import subprocess
import tarfile
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
BASE = '/data/adb/avr-codex'


def download(url, target, digest=None, size=None):
    if target.exists() and digest and hashlib.sha256(target.read_bytes()).hexdigest() == digest:
        return
    temporary = target.with_suffix(target.suffix+'.part')
    request = urllib.request.Request(url, headers={'User-Agent':'codex-mobile-runtime-installer'})
    h = hashlib.sha256()
    with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
        while chunk := response.read(1024*1024):
            output.write(chunk)
            h.update(chunk)
    if digest and h.hexdigest() != digest:
        temporary.unlink()
        raise RuntimeError('SHA-256 mismatch: '+target.name)
    if size and temporary.stat().st_size != size:
        temporary.unlink()
        raise RuntimeError('Unexpected archive size: '+target.name)
    temporary.replace(target)


def prepare(ca_bundle=None):
    lock = json.loads((ROOT/'tools/runtime.lock.json').read_text(encoding='utf-8'))
    cache = ROOT/'.local/runtime-downloads'
    cache.mkdir(parents=True, exist_ok=True)
    binaries = []
    for asset in lock['assets']:
        archive = cache/asset['name']
        url = f'https://github.com/openai/codex/releases/download/rust-v{lock["version"]}/{asset["name"]}'
        print('Verifying '+asset['name'], flush=True)
        download(url, archive, asset['sha256'], asset['size'])
        binary = cache/asset['destination']
        with tarfile.open(archive) as source:
            member = source.getmember(asset['binary'])
            if not member.isfile():
                raise RuntimeError('Expected a regular executable')
            with source.extractfile(member) as data, binary.open('wb') as output:
                while chunk := data.read(1024*1024):
                    output.write(chunk)
        binaries.append(binary)
    if ca_bundle:
        ca = Path(ca_bundle).resolve()
    else:
        ca = cache/'ca-certificates.crt'
        download('https://curl.se/ca/cacert.pem', ca)
    if b'-----BEGIN CERTIFICATE-----' not in ca.read_bytes():
        raise RuntimeError('CA bundle must be PEM encoded')
    return binaries, ca


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Explicit authorized ADB device or wireless endpoint')
    parser.add_argument('--ca-bundle', help='Optional standard PEM CA bundle')
    parser.add_argument('--prepare-only', action='store_true', help='Download and verify without touching a phone')
    parser.add_argument('--update', action='store_true', help='Replace runtime executables; keep login, configuration and workspace')
    args = parser.parse_args()
    if not args.prepare_only and not args.serial:
        parser.error('--serial is required for installation')
    binaries, ca = prepare(args.ca_bundle)
    if args.prepare_only:
        print('Runtime archives verified. No phone modified.')
        return

    def adb(*parts, source=None):
        p = subprocess.run(['adb','-s',args.serial,*map(str,parts)], input=source,
                           capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
        if p.returncode:
            raise RuntimeError(p.stdout+p.stderr)
        return p.stdout

    if adb('shell','uname -m').strip() != 'aarch64':
        raise RuntimeError('The pinned runtime requires an ARM64 Android device')
    if adb('shell','su -c id -u').strip() != '0':
        raise RuntimeError('Grant root to the authorized ADB shell first')
    if not args.update:
        adb('shell','su -c sh',source=f'test ! -e {BASE}/bin/codex || {{ echo "Runtime already exists; use --update" >&2; exit 73; }}\n')
    stage='/data/local/tmp/codex-mobile-install-'+uuid.uuid4().hex
    adb('shell','mkdir',stage)
    try:
        for file in binaries+[ca]:
            name='ca-certificates.crt' if file == ca else file.name
            adb('push',file,stage+'/'+name)
        for name in ['run','docker','config.toml','AGENTS.md']:
            adb('push',ROOT/'runtime'/name,stage+'/'+name)
        q=shlex.quote(stage)
        script=f'''set -e
umask 077
mkdir -p {BASE}/bin {BASE}/home {BASE}/tmp {BASE}/workspace
cp {q}/codex {BASE}/bin/codex
cp {q}/codex-code-mode-host {BASE}/bin/codex-code-mode-host
cp {q}/run {BASE}/run
cp {q}/ca-certificates.crt {BASE}/ca-certificates.crt
if [ ! -e {BASE}/home/config.toml ]; then cp {q}/config.toml {BASE}/home/config.toml; fi
if [ ! -e {BASE}/workspace/AGENTS.md ]; then cp {q}/AGENTS.md {BASE}/workspace/AGENTS.md; fi
if [ -x /data/adb/avr-docker/ctl ]; then cp {q}/docker {BASE}/bin/docker; chmod 700 {BASE}/bin/docker; fi
chmod 700 {BASE} {BASE}/home {BASE}/tmp {BASE}/bin {BASE}/workspace {BASE}/run {BASE}/bin/codex {BASE}/bin/codex-code-mode-host
chmod 600 {BASE}/ca-certificates.crt
{BASE}/bin/codex --version
'''
        print(adb('shell','su -c sh',source=script))
    finally:
        if stage.startswith('/data/local/tmp/codex-mobile-install-') and len(stage.rsplit('-',1)[-1]) == 32:
            adb('shell','su -c sh',source='rm -rf '+shlex.quote(stage)+'\n')
    print('Private runtime ready. Open Codex Mobile and sign in on the official OpenAI page.')


if __name__ == '__main__':
    main()
