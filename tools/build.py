"""Build the Android client with JDK + Android SDK, without Gradle."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import urllib.request
import io
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
JSCH_SHA256 = '2afca1357d816028f2ac33f22407d2488a68406682973949cebb1b8ea6688a8b'
JSCH_URL = 'https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/2.28.8/jsch-2.28.8.jar'


def run(*args):
    p = subprocess.run(list(map(str, args)), capture_output=True, text=True,
                       encoding='utf-8', errors='replace', timeout=180)
    if p.returncode:
        raise RuntimeError(p.stdout + p.stderr)
    return p.stdout


def executable(name):
    java_home = os.environ.get('JAVA_HOME')
    if java_home:
        candidate = Path(java_home) / 'bin' / (name + ('.exe' if os.name == 'nt' else ''))
        if candidate.is_file():
            return candidate
    return shutil.which(name) or name


def resolve_sdk(value):
    value = value or os.environ.get('ANDROID_SDK_ROOT') or os.environ.get('ANDROID_HOME')
    if not value:
        raise RuntimeError('Set ANDROID_HOME or pass --sdk /path/to/android-sdk')
    sdk = Path(value).expanduser().resolve()
    jars = sorted(sdk.rglob('android.jar'))
    jars = [p for p in jars if p.parent.name.startswith(('android-36', 'android-37'))]
    names = ['aapt2.exe', 'zipalign.exe'] if os.name == 'nt' else ['aapt2', 'zipalign']
    aapts = sorted(sdk.rglob(names[0]))
    for aapt in reversed(aapts):
        folder = aapt.parent
        align, signer, d8 = folder/names[1], folder/'lib/apksigner.jar', folder/'lib/d8.jar'
        if all(p.is_file() for p in [align, signer, d8]) and jars:
            return jars[-1], aapt, align, signer, d8
    raise RuntimeError('Android platform 36 and complete Build Tools 36.0.0 are required')


def signing(args):
    if args.keystore:
        if not args.password_file:
            raise RuntimeError('--keystore requires --password-file')
        return Path(args.keystore).resolve(), Path(args.password_file).resolve(), args.alias
    directory = ROOT/'.local/private-signing'
    directory.mkdir(parents=True, exist_ok=True)
    keystore, password = directory/'development.p12', directory/'password.txt'
    if not keystore.exists():
        password.write_text(secrets.token_urlsafe(32), encoding='utf-8')
        try:
            password.chmod(0o600)
        except OSError:
            pass
        run(executable('keytool'), '-genkeypair', '-alias', 'codex-mobile-dev',
            '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650',
            '-storetype', 'PKCS12', '-keystore', keystore,
            '-storepass:file', password,
            '-dname', 'CN=Codex Mobile Development,O=Local Build')
    if not password.is_file():
        raise RuntimeError('Development signing password is missing; restore your backup')
    return keystore, password, 'codex-mobile-dev'


def build(args):
    android, aapt, align, signer, d8 = resolve_sdk(args.sdk)
    out = ROOT/'build'
    out.mkdir(exist_ok=True)
    dist = ROOT/'dist'
    dist.mkdir(exist_ok=True)
    deps = ROOT/'.local/deps'
    deps.mkdir(parents=True, exist_ok=True)
    jsch = deps/'jsch-2.28.8.jar'
    if not jsch.exists():
        with urllib.request.urlopen(JSCH_URL, timeout=60) as response:
            jsch.write_bytes(response.read())
    if hashlib.sha256(jsch.read_bytes()).hexdigest() != JSCH_SHA256:
        raise RuntimeError('JSch SHA-256 mismatch; dependency not used')
    native_lock = json.loads((ROOT/'android/native/xray.lock.json').read_text())
    native_binary = ROOT/'android/native/arm64-v8a/libxray.so'
    if not native_binary.is_file():
        with urllib.request.urlopen(native_lock['url'], timeout=90) as response:
            archive_bytes = response.read(40*1024*1024+1)
        if len(archive_bytes) > 40*1024*1024 or 'sha256:'+hashlib.sha256(archive_bytes).hexdigest() != native_lock['digest']:
            raise RuntimeError('Xray release archive SHA-256 mismatch')
        with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
            payload = archive.read('xray')
        if hashlib.sha256(payload).hexdigest() != native_lock['binary_sha256']:
            raise RuntimeError('Xray ARM64 SHA-256 mismatch')
        native_binary.parent.mkdir(parents=True, exist_ok=True)
        native_binary.write_bytes(payload)
    if hashlib.sha256(native_binary.read_bytes()).hexdigest() != native_lock['binary_sha256']:
        raise RuntimeError('Xray ARM64 SHA-256 mismatch')
    for name in ['generated', 'classes', 'dex']:
        target = (out/name).resolve()
        if not target.is_relative_to(out.resolve()):
            raise RuntimeError('Build path escaped output directory')
        if target.exists():
            shutil.rmtree(target)
        target.mkdir()
    source = ROOT/'android'
    run(aapt, 'compile', '--dir', source/'res', '-o', out/'resources.zip')
    run(aapt, 'link', '-I', android, '--manifest', source/'AndroidManifest.xml',
        '-R', out/'resources.zip', '-o', out/'base.apk',
        '--java', out/'generated', '--auto-add-overlay', '-A', source/'assets')
    java_sources = list((source/'src').rglob('*.java')) + list((out/'generated').rglob('*.java'))
    run(executable('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-classpath', str(android)+os.pathsep+str(jsch), '-d', out/'classes', *java_sources)
    run(executable('java'), '-cp', d8, 'com.android.tools.r8.D8', '--lib', android,
        '--min-api', '29', '--output', out/'dex', jsch, *list((out/'classes').rglob('*.class')))
    shutil.copyfile(out/'base.apk', out/'unsigned.apk')
    with zipfile.ZipFile(out/'unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(out/'dex/classes.dex', 'classes.dex')
        for native in (source/'native').rglob('*.so'):
            archive.write(native, 'lib/'+native.relative_to(source/'native').as_posix(), compress_type=zipfile.ZIP_STORED)
    run(align, '-f', '4', out/'unsigned.apk', out/'aligned.apk')
    keystore, password, alias = signing(args)
    version = ET.parse(source/'AndroidManifest.xml').getroot().get('{http://schemas.android.com/apk/res/android}versionName')
    apk = dist/f'Codex-Mobile-{version}.apk'
    run(executable('java'), '-jar', signer, 'sign', '--ks', keystore,
        '--ks-key-alias', alias, '--ks-pass', 'file:'+str(password),
        '--out', apk, out/'aligned.apk')
    verification = run(executable('java'), '-jar', signer, 'verify', '--verbose', apk)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (dist/'SHA256SUMS').write_text(digest+'  '+apk.name+'\n', encoding='utf-8')
    print(json.dumps({'apk': str(apk), 'version': version, 'sha256': digest,
                      'signature_verified': 'Verifies' in verification}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', help='Android SDK containing platform 36 and build tools')
    parser.add_argument('--keystore', help='Optional private release signing keystore')
    parser.add_argument('--password-file', help='Private UTF-8 file containing its password')
    parser.add_argument('--alias', default='avr-mobile')
    build(parser.parse_args())


if __name__ == '__main__':
    main()
