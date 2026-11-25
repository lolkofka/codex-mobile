"""Render the bundled UI using synthetic account/server state, never phone data."""
from pathlib import Path
import functools
import http.server
import json
import shutil
import subprocess
import threading

ROOT = Path(__file__).resolve().parents[1]


def main():
    out = ROOT/'.local/design-preview'
    out.mkdir(parents=True, exist_ok=True)
    for name in ['chat.js', 'chat.css', 'index.html']:
        shutil.copyfile(ROOT/'android/assets/ui'/name, out/name)
    page = (out/'index.html').read_text().replace('</head>', '<style>*{animation:none!important;transition:none!important;}</style><script defer src="preview.js"></script></head>')
    (out/'index.html').write_text(page)
    state = {'replace': True, 'ready': True, 'busy': False, 'chatLoading': False,
             'loggedIn': True, 'account': 'you@example.test · Plus', 'status': 'Готов',
             'chatKey': 'design', 'chatId': 'design', 'target': 'phone', 'appVersion': '0.6.6-copy',
             'model': 'gpt-6.1', 'effort': 'high', 'order': [], 'updates': [], 'threads': [],
             'models': [{'id': 'gpt-6.1', 'model': 'gpt-6.1', 'displayName': 'GPT-6.1'}],
             'servers': [{'id': 'work', 'name': 'Рабочий сервер', 'host': 'work.example', 'port': 22, 'user': 'dev', 'hasKey': True}],
             'phoneAccess': {'background': True, 'traffic': False, 'status': {}},
             'tunnel': {'enabled': True, 'working': False, 'selected': 'sub:one',
                        'selectedName': 'Франкфурт', 'status': 'Xray · Франкфурт', 'error': '',
                        'subscriptions': [{'id': 'sub', 'name': 'Личная подписка', 'refreshable': True, 'updated': 1795600000000,
                                           'hosts': [{'id': 'sub:one', 'name': 'Франкфурт', 'host': 'de.example', 'port': 443, 'protocol': 'vless'},
                                                     {'id': 'sub:two', 'name': 'Амстердам', 'host': 'nl.example', 'port': 443, 'protocol': 'vless'}]}]}}
    script = "window.Phone={post(){}};setTimeout(()=>{applyState("+json.dumps(state, ensure_ascii=False)+");render();const kind=new URLSearchParams(location.search).get('view')||'settings';openSheet(kind,kind==='subscription'?state.tunnel.subscriptions[0]:null);},80);"
    (out/'preview.js').write_text(script)
    class Quiet(http.server.SimpleHTTPRequestHandler):
        def log_message(self, *args):
            pass
    handler = functools.partial(Quiet, directory=str(out))
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        for width in [360, 412]:
            for view in ['settings', 'tunnel', 'subscription', 'phoneAccess', 'servers']:
                subprocess.run(['/usr/bin/chromium', '--headless=new', '--no-sandbox', '--disable-dev-shm-usage',
                                '--disable-gpu', '--hide-scrollbars', '--no-first-run',
                                '--user-data-dir='+str(out/f'profile-{width}-{view}'),
                                f'--window-size={width},915', '--force-device-scale-factor=1',
                                '--virtual-time-budget=1400', '--run-all-compositor-stages-before-draw', '--screenshot='+str(out/f'{view}-{width}.png'),
                                f'http://127.0.0.1:{server.server_port}/index.html?view={view}'],
                               capture_output=True, check=True, timeout=30)
    finally:
        server.shutdown()
        server.server_close()
    print('Rendered settings, tunnel, hosts, access and servers at 360/412px')


if __name__ == '__main__':
    main()
