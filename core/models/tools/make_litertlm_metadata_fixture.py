"""Build small test fixtures from the REAL headers of pinned .litertlm files.

For each catalog entry id given on the command line, downloads only the first
WINDOW bytes of its pinned downloadUrl, keeps the container header and the
LlmMetadataProto section byte-for-byte, and neutralises every other section
entry (begin = end = 0, data_type = TFLiteModel) so the engine's public
ContainerMetadataReader.parse(bytes) can read the fixture without the
multi-GB payload. The LlmMetadata bytes (model type, chat template, stop
tokens) are untouched — they are what the family resolver consumes.

Usage:
  python -I core/models/tools/make_litertlm_metadata_fixture.py <out_dir> <catalog id>...
"""
import hashlib, json, os, struct, sys, urllib.request

WINDOW = 1 << 20
HEADER_BASE = 32
DATA_TYPE_TFLITE_MODEL = 3
DATA_TYPE_LLM_METADATA = 5
CAT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'assets', 'catalog_v1.json')


def fetch(url, n):
    req = urllib.request.Request(url, headers={'User-Agent': 'catalog-check', 'Range': 'bytes=0-%d' % (n - 1)})
    with urllib.request.urlopen(req, timeout=120) as f:
        return bytearray(f.read())


def i32(b, p):
    return struct.unpack_from('<i', b, HEADER_BASE + p)[0]


def vtable(b, t):
    return t - i32(b, t)


def field(b, vt, idx):
    size = i32(b, vt) & 0xFFFF
    slot = 4 + idx * 2
    if slot + 2 > size:
        return None
    off = i32(b, vt + slot) & 0xFFFF
    return off or None


def sections(b):
    root = i32(b, 0)
    sm_f = field(b, vtable(b, root), 1)
    sm = root + sm_f + i32(b, root + sm_f)
    ob_f = field(b, vtable(b, sm), 0)
    vec = sm + ob_f + i32(b, sm + ob_f)
    n = i32(b, vec)
    out, cur = [], vec + 4
    for _ in range(n):
        e = cur + i32(b, cur)
        cur += 4
        vt = vtable(b, e)
        bp, ep, tp = (e + field(b, vt, 1), e + field(b, vt, 2), e + field(b, vt, 3))
        begin = struct.unpack_from('<Q', b, HEADER_BASE + bp)[0]
        end = struct.unpack_from('<Q', b, HEADER_BASE + ep)[0]
        out.append(dict(begin=begin, end=end, type=b[HEADER_BASE + tp], bp=bp, ep=ep, tp=tp))
    return out


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    models = {m['id']: m for m in json.load(open(CAT, encoding='utf-8'))['models']}
    index = {}
    for mid in sys.argv[2:]:
        m = models[mid]
        b = fetch(m['downloadUrl'], WINDOW)
        assert bytes(b[:8]) == b'LITERTLM', mid
        secs = sections(b)
        meta = [s for s in secs if s['type'] == DATA_TYPE_LLM_METADATA]
        assert len(meta) == 1, mid
        meta = meta[0]
        assert meta['end'] <= len(b), '%s: metadata beyond window' % mid
        for s in secs:
            if s is meta:
                continue
            struct.pack_into('<Q', b, HEADER_BASE + s['bp'], 0)
            struct.pack_into('<Q', b, HEADER_BASE + s['ep'], 0)
            b[HEADER_BASE + s['tp']] = DATA_TYPE_TFLITE_MODEL
        fixture = bytes(b[:meta['end']])
        name = mid + '.header.bin'
        open(os.path.join(out_dir, name), 'wb').write(fixture)
        index[mid] = dict(
            file=name, revision=m['revision'], fileName=m['fileName'], sha256=m['sha256'],
            llmMetadataSha256=hashlib.sha256(bytes(b[meta['begin']:meta['end']])).hexdigest(),
            llmMetadataBytes=meta['end'] - meta['begin'],
        )
        print(mid, len(fixture), 'bytes, metadata', meta['end'] - meta['begin'])
    open(os.path.join(out_dir, 'index.json'), 'w', encoding='utf-8', newline='\n').write(
        json.dumps(index, indent=2) + '\n')


if __name__ == '__main__':
    main()
