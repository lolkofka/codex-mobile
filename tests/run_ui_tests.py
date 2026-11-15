"""Run the bundled UI against a synthetic native bridge in a separate browser."""
from pathlib import Path
import argparse
import functools
import html
import http.server
import json
import os
import re
import shutil
import struct
import subprocess
import threading
import zlib

ROOT=Path(__file__).resolve().parents[1]


def png_fixture():
    def chunk(kind, data):
        return struct.pack('!I',len(data))+kind+data+struct.pack('!I',zlib.crc32(kind+data)&0xffffffff)
    width,height=320,200
    rows=[]
    for y in range(height):
        row=bytearray([0])
        for x in range(width):row.extend((210,232,240) if 80<x<240 and 50<y<150 else (38,70,96))
        rows.append(bytes(row))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!IIBBBBB',width,height,8,2,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows)))+chunk(b'IEND',b'')


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--browser',default=os.environ.get('BROWSER'))
    args=parser.parse_args()
    browser=args.browser or next((shutil.which(name) for name in ['google-chrome','chromium','chromium-browser','msedge'] if shutil.which(name)),None)
    if not browser:parser.error('Pass --browser /path/to/chrome-or-edge')
    out=ROOT/'.local/ui-tests'
    out.mkdir(parents=True,exist_ok=True)
    for name in ['chat.js','chat.css','index.html']:shutil.copyfile(ROOT/'android/assets/ui'/name,out/name)
    page=(out/'index.html').read_text(encoding='utf-8').replace('</head>','<script defer src="tests.js"></script></head>')
    (out/'index.html').write_text(page,encoding='utf-8')
    shutil.copyfile(ROOT/'tests/ui.test.js',out/'tests.js')
    (out/'media-fixture.png').write_bytes(png_fixture())
    (out/'media').mkdir(exist_ok=True)
    shutil.copyfile(out/'media-fixture.png',out/'media/00000000-0000-0000-0000-000000000001.png')
    class Handler(http.server.SimpleHTTPRequestHandler):
        def log_message(self,*args):pass
    server=http.server.ThreadingHTTPServer(('127.0.0.1',0),functools.partial(Handler,directory=str(out)))
    threading.Thread(target=server.serve_forever,daemon=True).start()
    command=[str(browser),'--headless=new','--disable-gpu','--no-first-run','--no-default-browser-check','--user-data-dir='+str(out/'profile'),'--window-size=520,1000','--virtual-time-budget=2500','--dump-dom','--screenshot='+str(out/'screenshot.png'),f'http://127.0.0.1:{server.server_port}/index.html']
    try:p=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
    finally:server.shutdown();server.server_close()
    (out/'dom.html').write_text(p.stdout,encoding='utf-8')
    failure=re.search(r'<pre id="test-failure">(.*?)</pre>',p.stdout,re.S)
    if failure:raise RuntimeError(html.unescape(failure.group(1)))
    match=re.search(r'<pre id="test-report"[^>]*>(.*?)</pre>',p.stdout,re.S)
    if not match:raise RuntimeError('No browser report: '+p.stderr[-1000:])
    report=json.loads(html.unescape(match.group(1)))
    (out/'result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    assert report['status']=='PASS'
    print(json.dumps({'status':'PASS','checks':len(report['checks'])}))


if __name__=='__main__':main()
