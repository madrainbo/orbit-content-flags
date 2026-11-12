"""Rebuilds the NSFWJS MobileNetV2 (Keras 2.2.4 topology in model.json, uint8
quantised weights in weights.bin) in current Keras and writes a TFLite file."""
import json, sys
import numpy as np
import tensorflow as tf
from tensorflow import keras
from tensorflow.keras import layers as L

src, out = sys.argv[1], sys.argv[2]
model_json = json.load(open(src + '/model.json'))
cfg = model_json['modelTopology']['model_config']['config']

tensors = {}
built = {}
def inputs_of(layer):
    names = [n[0] for n in layer['inbound_nodes'][0]]
    xs = [tensors[n] for n in names]
    return xs[0] if len(xs) == 1 else xs

for layer in cfg['layers']:
    c, name, kind = layer['config'], layer['name'], layer['class_name']
    if kind == 'InputLayer':
        tensors[name] = keras.Input(shape=tuple(c['batch_input_shape'][1:]), name=name)
        continue
    if kind == 'ZeroPadding2D':
        l = L.ZeroPadding2D(padding=tuple(tuple(p) for p in c['padding']), name=name)
    elif kind == 'Conv2D':
        assert c['activation'] == 'linear' and c['dilation_rate'] == [1, 1]
        l = L.Conv2D(c['filters'], tuple(c['kernel_size']), strides=tuple(c['strides']),
                     padding=c['padding'], use_bias=c['use_bias'], name=name)
    elif kind == 'DepthwiseConv2D':
        assert c['activation'] == 'linear' and c['depth_multiplier'] == 1
        l = L.DepthwiseConv2D(tuple(c['kernel_size']), strides=tuple(c['strides']),
                              padding=c['padding'], use_bias=c['use_bias'], name=name)
    elif kind == 'BatchNormalization':
        l = L.BatchNormalization(axis=c['axis'], momentum=c['momentum'], epsilon=c['epsilon'], name=name)
    elif kind == 'ReLU':
        assert c.get('negative_slope', 0) == 0 and c.get('threshold', 0) == 0
        l = L.ReLU(max_value=c['max_value'], name=name)
    elif kind == 'Add':
        l = L.Add(name=name)
    elif kind == 'AveragePooling2D':
        l = L.AveragePooling2D(pool_size=tuple(c['pool_size']), strides=tuple(c['strides']),
                               padding=c['padding'], name=name)
    elif kind == 'Flatten':
        l = L.Flatten(name=name)
    elif kind == 'Dense':
        l = L.Dense(c['units'], activation=c['activation'], use_bias=c['use_bias'], name=name)
    elif kind == 'Dropout':
        l = L.Dropout(c['rate'], name=name)
    else:
        raise SystemExit('unhandled layer type ' + kind)
    tensors[name] = l(inputs_of(layer))
    built[name] = l

model = keras.Model(tensors[cfg['input_layers'][0][0]], tensors[cfg['output_layers'][0][0]])

# Weights: dequantise (value = q * scale + min) and assign by layer/weight name.
raw = open(src + '/weights.bin', 'rb').read()
offset = 0
by_layer = {}
for group in model_json['weightsManifest']:
    for w in group['weights']:
        q = w['quantization']
        assert q['dtype'] == 'uint8'
        n = int(np.prod(w['shape']))
        arr = np.frombuffer(raw, dtype=np.uint8, count=n, offset=offset).astype(np.float32) * q['scale'] + q['min']
        offset += n
        layer_name, weight_name = w['name'].rsplit('/', 1)
        by_layer.setdefault(layer_name, {})[weight_name] = arr.reshape(w['shape'])
assert offset == len(raw), (offset, len(raw))

assigned = 0
for name, l in built.items():
    if not l.weights:
        assert name not in by_layer, name
        continue
    got = by_layer.pop(name)
    values = []
    for v in l.weights:
        key = v.path.rsplit('/', 1)[-1] if hasattr(v, 'path') else v.name.split('/')[-1].split(':')[0]
        arr = got.pop(key) if key in got else got.pop('depthwise_' + key)  # older Keras named it depthwise_kernel
        assert tuple(arr.shape) == tuple(v.shape), (name, key, arr.shape, v.shape)
        values.append(arr)
    assert not got, (name, list(got))
    l.set_weights(values)
    assigned += len(values)
assert not by_layer, list(by_layer)[:5]
print('layers', len(cfg['layers']), '| weight tensors assigned', assigned, '| params', model.count_params())
print('output shape', model.output_shape, '| last conv filters', built['Conv_1'].filters if 'Conv_1' in built else '?')

converter = tf.lite.TFLiteConverter.from_keras_model(model)
converter.optimizations = [tf.lite.Optimize.DEFAULT]
converter.target_spec.supported_types = [tf.float16]
tflite = converter.convert()
open(out, 'wb').write(tflite)
print('tflite bytes', len(tflite))

# The converted file must agree with the rebuilt network.
interp = tf.lite.Interpreter(model_content=tflite)
interp.allocate_tensors()
i, o = interp.get_input_details()[0], interp.get_output_details()[0]
print('tflite input', i['shape'], i['dtype'].__name__, '| output', o['shape'], o['dtype'].__name__)
rng = np.random.default_rng(0)
worst = 0
for _ in range(5):
    x = rng.random((1, 224, 224, 3), dtype=np.float32)
    interp.set_tensor(i['index'], x); interp.invoke()
    a = interp.get_tensor(o['index'])[0]
    b = model(x, training=False).numpy()[0]
    worst = max(worst, float(np.abs(a - b).max()))
print('max |tflite - keras| over 5 random inputs', round(worst, 5))
model.save(out.replace('.tflite', '.keras'))
