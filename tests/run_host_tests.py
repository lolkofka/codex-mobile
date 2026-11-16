"""Check the login lifecycle without a phone, network, or stored credentials."""
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
                    *map(str, (ROOT/'tests/host').glob('*.java'))], check=True)
    for name in ['LoginFlowTest', 'EngineNetworkProxyTest']:
        subprocess.run([java_tool('java'), '-cp', str(out), 'local.avr.codex.'+name], check=True, timeout=30)


if __name__ == '__main__':
    main()
