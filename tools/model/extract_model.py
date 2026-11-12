"""Reads the packaged model files as text and writes model.json + weights.bin.
Nothing from the package is executed."""
import base64, json, re, sys, math
from collections import Counter
src, out = sys.argv[1], sys.argv[2]
js = open(src + '/model.min.js', encoding='utf8').read()
start = js.index('{modelTopology:')
depth = 0; i = start; in_str = False; esc = False
while True:
    c = js[i]
    if in_str:
        if esc: esc = False
        elif c == '\\': esc = True
        elif c == '"': in_str = False
    else:
        if c == '"': in_str = True
        elif c == '{': depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0: break
    i += 1
lit = js[start:i+1]
# Tokenise so string contents are never rewritten.
parts = re.split(r'("(?:[^"\\]|\\.)*")', lit)
for k in range(0, len(parts), 2):
    p = parts[k]
    p = re.sub(r'([{,])([A-Za-z_$][\w$]*):', r'\1"\2":', p)
    p = p.replace('!0', 'true').replace('!1', 'false').replace('void 0', 'null')
    p = re.sub(r'(?<![\d.])(-?)\.(\d)', r'\g<1>0.\2', p)
    parts[k] = p
model = json.loads(''.join(parts))
json.dump(model, open(out + '/model.json', 'w'))
shard = open(src + '/group1-shard1of1.min.js', encoding='utf8').read()
b64 = max(re.findall(r'"([A-Za-z0-9+/=]{1000,})"', shard), key=len)
raw = base64.b64decode(b64)
open(out + '/weights.bin', 'wb').write(raw)

mt = model['modelTopology']
cfg = mt['model_config']
layers = cfg['config']['layers'] if isinstance(cfg['config'], dict) else cfg['config']
print('format', model.get('format'), '| keras', mt.get('keras_version'), '| class', cfg['class_name'], '| layers', len(layers))
print(Counter(l['class_name'] for l in layers))
for l in layers[:4] + layers[-7:]:
    c = l['config']
    print(' ', l['class_name'], l['name'], {k: c[k] for k in ('filters','units','activation','batch_input_shape','rate','strides','kernel_size','padding','use_bias') if k in c})
ws = [w for g in model['weightsManifest'] for w in g['weights']]
need = sum(math.prod(w['shape']) * (1 if (w.get('quantization') or {}).get('dtype') == 'uint8' else 2 if (w.get('quantization') or {}).get('dtype') == 'uint16' else 4) for w in ws)
print('weights', len(ws), '| bytes expected', need, '| bytes found', len(raw), '| quant', Counter((w.get('quantization') or {}).get('dtype') for w in ws))
