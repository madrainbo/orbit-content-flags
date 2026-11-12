import sys, glob, os, warnings
warnings.filterwarnings('ignore')
import numpy as np
from PIL import Image, ImageDraw
import tensorflow as tf
interp = tf.lite.Interpreter(model_path=sys.argv[1]); interp.allocate_tensors()
i, o = interp.get_input_details()[0], interp.get_output_details()[0]
CLASSES = ['Drawing', 'Hentai', 'Neutral', 'Porn', 'Sexy']
def run(img):
    x = np.asarray(img.convert('RGB').resize((224, 224), Image.BILINEAR), dtype=np.float32)[None] / 255
    interp.set_tensor(i['index'], x); interp.invoke()
    return interp.get_tensor(o['index'])[0]
imgs = {}
import skimage, sklearn, matplotlib
for root in [os.path.dirname(skimage.__file__) + '/data', os.path.dirname(sklearn.__file__) + '/datasets/images',
             os.path.dirname(matplotlib.__file__) + '/mpl-data/sample_data']:
    for f in sorted(glob.glob(root + '/*.jpg') + glob.glob(root + '/*.png') + glob.glob(root + '/*.jpeg')):
        try:
            im = Image.open(f)
            if min(im.size) >= 200: imgs[os.path.basename(f)] = im
        except Exception: pass
d = Image.new('RGB', (400, 400), 'white'); dr = ImageDraw.Draw(d)
dr.ellipse((60, 60, 340, 340), outline='black', width=6); dr.line((60, 200, 340, 200), fill='red', width=8); dr.rectangle((150, 150, 250, 250), fill='#3366cc')
imgs['(synthetic line drawing)'] = d
imgs['(random noise)'] = Image.fromarray((np.random.default_rng(1).random((300, 300, 3)) * 255).astype('uint8'))
adult_hits = 0
for name, im in imgs.items():
    p = run(im)
    top = CLASSES[int(p.argmax())]
    adult = p[1] + p[3]
    flag = adult >= 0.6 or p[4] >= 0.85
    adult_hits += flag
    print(f"{name[:34]:34s} top={top:8s} " + ' '.join(f"{c[:4]}={v:.2f}" for c, v in zip(CLASSES, p)) + ('  <-- WOULD FLAG' if flag else ''))
print('images', len(imgs), '| would flag', adult_hits)
