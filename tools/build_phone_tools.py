"""Compile the personal copy's static root helper bundle, without touching a phone."""
from pathlib import Path
from argparse import ArgumentParser
import shutil
from build import ROOT, executable, resolve_sdk, run


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument('--sdk')
    args = parser.parse_args()
    android, _, _, _, d8 = resolve_sdk(args.sdk)
    source = ROOT/'phone-tools'
    out = ROOT/'build/phone-tools'
    for name in ['classes', 'dex']:
        folder = out/name
        if folder.exists():
            shutil.rmtree(folder)
        folder.mkdir(parents=True)
    run(executable('javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8',
        '-classpath', android, '-d', out/'classes', *sorted(source.glob('*.java')))
    run(executable('java'), '-cp', d8, 'com.android.tools.r8.D8', '--lib', android,
        '--min-api', '29', '--output', out/'dex', *sorted((out/'classes').rglob('*.class')))
    bundle = ROOT/'dist/phone-tools'
    (bundle/'bin').mkdir(parents=True, exist_ok=True)
    shutil.copyfile(out/'dex/classes.dex', bundle/'phone-tools.dex')
    for name in ['codex-apps', 'codex-phone-access', 'codex-servers', 'codex-traffic',
                 'https-cleanup', 'https-watchdog', 'prepare_ca.py', 'capture_addon.py',
                 'boot-cleanup.sh', 'install-when-idle.sh']:
        target = bundle/('bin/'+name if name.startswith('codex-') else name)
        shutil.copyfile(source/name, target)
        target.chmod(0o700 if not name.endswith('.py') else 0o600)
    print(bundle)


if __name__ == '__main__':
    main()
