"""Selected-app diagnostics; credentials are never included in the saved report."""
import json,re,time
from urllib.parse import urlsplit,parse_qsl,urlencode,urlunsplit
from pathlib import Path
from mitmproxy import http

SENSITIVE=re.compile(r'auth|token|password|passwd|secret|cookie|session|api.?key|credential',re.I)
def clean(value,depth=0):
    if depth>10:return '[nested data omitted]'
    if isinstance(value,dict):return {k:('[redacted]' if SENSITIVE.search(k) else clean(v,depth+1)) for k,v in list(value.items())[:300]}
    if isinstance(value,list):return [clean(x,depth+1) for x in value[:200]]
    if isinstance(value,str):return value[:4000]
    return value
def body(message):
    if not message or 'json' not in message.headers.get('content-type','').lower():return None
    raw=message.raw_content or b''
    if len(raw)>65536:return '[body over 64 KiB omitted]'
    try:return clean(message.json())
    except Exception:return None
def record(flow):
    request=flow.request
    parts=urlsplit(request.pretty_url)
    query=urlencode([(k,'[redacted]' if SENSITIVE.search(k) else v[:1000]) for k,v in parse_qsl(parts.query,keep_blank_values=True)])
    row={'time':time.time(),'method':request.method,'url':urlunsplit((parts.scheme,parts.netloc,parts.path,query,'')),'status':flow.response.status_code if flow.response else None,'request_json':body(request),'response_json':body(flow.response),'error':str(flow.error)[:300] if flow.error else None}
    with Path('/session/results/requests.jsonl').open('a') as out:out.write(json.dumps(row,ensure_ascii=False)+'\n')
def response(flow:http.HTTPFlow):record(flow)
def error(flow:http.HTTPFlow):record(flow)
