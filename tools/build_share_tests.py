"""Build two disposable app UIDs to verify Android file-share URI grants."""
from pathlib import Path
from argparse import ArgumentParser, Namespace
import os
import zipfile
from build import ROOT, resolve_sdk, run, executable, signing


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--sdk')
    args = parser.parse_args()
    android, aapt, align, signer, d8 = resolve_sdk(args.sdk)
    out = ROOT/'build/share-tests'
    for name in ['classes', 'dex']:
        (out/name).mkdir(parents=True, exist_ok=True)
    target = ROOT/'build/classes'
    run(executable('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-classpath', str(android)+os.pathsep+str(target), '-d', out/'classes',
        ROOT/'tests/native/FileSharingProbe.java')
    run(executable('java'), '-cp', d8, 'com.android.tools.r8.D8', '--lib', android,
        '--classpath', target, '--min-api', '29', '--output', out/'dex',
        *list((out/'classes').rglob('*.class')))
    ks, password, alias = signing(Namespace(keystore=None))
    for role, package, activity in [
        ('sender', 'local.avr.codex.sharetest', 'local.avr.codex.copy.FileSharingProbe'),
        ('receiver', 'local.avr.codex.sharereceiver', 'local.avr.codex.copy.FileSharingProbe$Receiver')
    ]:
        provider = (f'<provider android:name="local.avr.codex.copy.SharedFileProvider" android:authorities="{package}.files" android:exported="false" android:grantUriPermissions="true"/>' if role == 'sender' else '')
        manifest = out/f'{role}.xml'
        manifest.write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/><application android:label="Codex file-share test" android:allowBackup="false" android:theme="@style/AppTheme">{provider}<activity android:name="{activity}" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''')
        apk = out/f'{role}.apk'
        run(aapt, 'link', '-I', android, '--manifest', manifest, '-R', ROOT/'build/resources.zip', '-o', apk, '--auto-add-overlay')
        with zipfile.ZipFile(apk, 'a', compression=zipfile.ZIP_DEFLATED) as z:
            z.write(ROOT/'build/dex/classes.dex', 'classes.dex')
            z.write(out/'dex/classes.dex', 'classes2.dex')
        run(align, '-f', '4', apk, out/f'{role}-aligned.apk')
        result = ROOT/'dist'/f'file-share-{role}-tests.apk'
        run(executable('java'), '-jar', signer, 'sign', '--ks', ks, '--ks-key-alias', alias,
            '--ks-pass', 'file:'+str(password), '--out', result, out/f'{role}-aligned.apk')
        run(executable('java'), '-jar', signer, 'verify', result)
        print(result)


if __name__ == '__main__':
    main()
