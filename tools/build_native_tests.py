"""Build temporary instrumentation with the same key as your local client."""
from pathlib import Path
import argparse
import os
import shutil
import zipfile
from build import ROOT, resolve_sdk, run, executable, signing


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk')
    parser.add_argument('--keystore')
    parser.add_argument('--password-file')
    parser.add_argument('--alias',default='avr-mobile')
    args=parser.parse_args()
    android,aapt,align,signer,d8=resolve_sdk(args.sdk)
    target_classes=ROOT/'build/classes'
    if not (target_classes/'local/avr/codex/AgentService.class').is_file():
        raise RuntimeError('Build the main APK first')
    out=ROOT/'build/native-tests'
    out.mkdir(exist_ok=True)
    for name in ['classes','dex']:
        folder=(out/name).resolve()
        assert folder.is_relative_to(out.resolve())
        if folder.exists():shutil.rmtree(folder)
        folder.mkdir()
    source=ROOT/'tests/native'
    run(aapt,'link','-I',android,'--manifest',source/'AndroidManifest.xml','-o',out/'base.apk')
    run(executable('javac'),'-encoding','UTF-8','-source','8','-target','8','-classpath',str(android)+os.pathsep+str(target_classes),'-d',out/'classes',source/'MobileFeatureTests.java')
    run(executable('java'),'-cp',d8,'com.android.tools.r8.D8','--lib',android,'--classpath',target_classes,'--min-api','29','--output',out/'dex',*list((out/'classes').rglob('*.class')))
    shutil.copyfile(out/'base.apk',out/'unsigned.apk')
    with zipfile.ZipFile(out/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as archive:archive.write(out/'dex/classes.dex','classes.dex')
    run(align,'-f','4',out/'unsigned.apk',out/'aligned.apk')
    keystore,password,alias=signing(args)
    apk=ROOT/'dist/Codex-Mobile-tests.apk'
    run(executable('java'),'-jar',signer,'sign','--ks',keystore,'--ks-key-alias',alias,'--ks-pass','file:'+str(password),'--out',apk,out/'aligned.apk')
    run(executable('java'),'-jar',signer,'verify',apk)
    print(apk)


if __name__=='__main__':main()
