"""Check login, network and chat ordering without stored credentials."""
from pathlib import Path
import os
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def java_tool(name):
    candidate = Path(os.environ.get('JAVA_HOME', ''))/'bin'/(name+('.exe' if os.name == 'nt' else ''))
    return str(candidate) if candidate.is_file() else shutil.which(name) or name


def main():
    out = ROOT/'.local/host-tests'
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run([java_tool('javac'), '-encoding', 'UTF-8', '-d', str(out),
                    str(ROOT/'android/src/local/avr/codex/LoginFlow.java'),
                    str(ROOT/'android/src/local/avr/codex/EngineNetworkProxy.java'),
                    str(ROOT/'android/src/local/avr/codex/ChatRecency.java'),
                    str(ROOT/'phone-tools/BackgroundDisplayPolicy.java'),
                    *map(str, (ROOT/'tests/host').glob('*.java'))], check=True)
    for name in ['LoginFlowTest', 'EngineNetworkProxyTest', 'ChatRecencyTest', 'BackgroundDisplayPolicyTest']:
        subprocess.run([java_tool('java'), '-cp', str(out), 'local.avr.codex.copy.'+name], check=True, timeout=30)


if __name__ == '__main__':
    main()
