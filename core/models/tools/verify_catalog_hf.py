"""HEAD every catalog downloadUrl (no redirect follow) and compare X-Linked-Etag (LFS sha256)
and X-Linked-Size with the catalog; also read the LITERTLM container version (32-byte range).

Usage: python -I core/models/tools/verify_catalog_hf.py [path/to/catalog_v1.json]
No model bytes are downloaded except the 32-byte header. Exit code 1 when any entry drifts."""
import json, os, struct, sys, urllib.request, urllib.error
CAT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'assets', 'catalog_v1.json')
UA = {'User-Agent': 'catalog-check'}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


op = urllib.request.build_opener(NoRedirect)


def head(url):
    req = urllib.request.Request(url, method='HEAD', headers=UA)
    try:
        r = op.open(req, timeout=60)
        code = r.status
    except urllib.error.HTTPError as e:
        r = e
        code = e.code
    h = r.headers
    return code, (h.get('X-Linked-Etag') or '').strip('"'), h.get('X-Linked-Size')


def container(url):
    req = urllib.request.Request(url, headers={**UA, 'Range': 'bytes=0-31'})
    with urllib.request.urlopen(req, timeout=60) as f:
        b = f.read(32)
    if b[:8] != b'LITERTLM':
        return '-'
    return '%d.%d.%d' % struct.unpack('<III', b[8:20])


d = json.load(open(CAT, encoding='utf-8'))
bad = 0
for m in d['models']:
    code, etag, size = head(m['downloadUrl'])
    ok = code in (302, 307) and etag == m['sha256'] and size is not None and int(size) == m['sizeBytes']
    v = container(m['downloadUrl']) if m['fileName'].endswith('.litertlm') else 'tflite'
    bad += 0 if ok else 1
    print('%-40s %s HTTP %s sha %s size %s v%s' % (m['id'], 'OK ' if ok else 'BAD', code,
          'ok' if etag == m['sha256'] else etag[:12], 'ok' if size and int(size) == m['sizeBytes'] else size, v))
print('total', len(d['models']), 'bad', bad)
sys.exit(1 if bad else 0)
