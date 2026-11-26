"""Build a disposable background display probe, without installing or launching it."""
from argparse import ArgumentParser, Namespace
import zipfile
from build import ROOT, executable, resolve_sdk, run, signing


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--sdk')
    args = parser.parse_args()
    android, aapt, align, signer, d8 = resolve_sdk(args.sdk)
    out = ROOT/'build/background-tests'
    for name in ['classes', 'dex']:
        (out/name).mkdir(parents=True, exist_ok=True)
    run(executable('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-classpath', android, '-d', out/'classes', ROOT/'tests/native/BackgroundDisplayProbe.java')
    run(executable('java'), '-cp', d8, 'com.android.tools.r8.D8', '--lib', android,
        '--min-api', '29', '--output', out/'dex', *list((out/'classes').rglob('*.class')))
    manifest = out/'AndroidManifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="local.avr.codex.displaytest" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36"/><application android:label="Codex display test" android:allowBackup="false"><activity android:name="local.avr.codex.copy.BackgroundDisplayProbe" android:exported="true" android:resizeableActivity="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''')
    run(aapt, 'link', '-I', android, '--manifest', manifest, '-o', out/'base.apk')
    with zipfile.ZipFile(out/'base.apk', 'a', compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(out/'dex/classes.dex', 'classes.dex')
    run(align, '-f', '4', out/'base.apk', out/'aligned.apk')
    key, password, alias = signing(Namespace(keystore=None))
    apk = ROOT/'dist/background-display-tests.apk'
    run(executable('java'), '-jar', signer, 'sign', '--ks', key, '--ks-key-alias', alias,
        '--ks-pass', 'file:'+str(password), '--out', apk, out/'aligned.apk')
    run(executable('java'), '-jar', signer, 'verify', apk)
    print(apk)


if __name__ == '__main__':
    main()
