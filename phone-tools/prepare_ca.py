from pathlib import Path
from mitmproxy.certs import CertStore
import subprocess

directory=Path('/session/ca');directory.mkdir(mode=0o700)
CertStore.create_store(directory,'mitmproxy',2048,organization='Codex Mobile Temporary',cn='Codex Mobile Temporary HTTPS')
cert=directory/'mitmproxy-ca-cert.pem'
digest=subprocess.check_output(['openssl','x509','-in',str(cert),'-noout','-subject_hash_old'],text=True).strip()
Path('/session/ca-name').write_text(digest+'.0\n')
print('TEMPORARY_CA_READY')
