"""Trains a small classifier on sentence embeddings and reports how it does on
the separately worded TEST set, next to the existing rules.
Usage: train.py <universal_sentence_encoder.tflite> <out.json>"""
import json, subprocess, sys
import numpy as np
from sklearn.linear_model import LogisticRegression
from sklearn.model_selection import StratifiedKFold, cross_val_predict
from mediapipe.tasks import python as mp_python
from mediapipe.tasks.python import text
from data import TRAIN, TEST, REPORTED_FALSE_ALARMS

CLASSES = ['benign', 'scam', 'adult', 'bait', 'charge']
embedder = text.TextEmbedder.create_from_options(text.TextEmbedderOptions(
    base_options=mp_python.BaseOptions(model_asset_path=sys.argv[1]), l2_normalize=True))
embed = lambda rows: np.array([embedder.embed(r).embeddings[0].embedding for r in rows], dtype=np.float32)

def flat(d):
    xs, ys = [], []
    for k in CLASSES:
        xs += d[k]; ys += [CLASSES.index(k)] * len(d[k])
    return xs, np.array(ys)

train_x, train_y = flat(TRAIN)
test_x, test_y = flat(TEST)
E_train, E_test = embed(train_x), embed(test_x)

def fit(C):
    return LogisticRegression(C=C, max_iter=2000, class_weight='balanced')

# Pick regularisation by cross-validation on TRAIN only.
best = None
for C in [1, 3, 10, 30, 100]:
    pred = cross_val_predict(fit(C), E_train, train_y, cv=StratifiedKFold(5, shuffle=True, random_state=0))
    acc = (pred == train_y).mean()
    if best is None or acc > best[1]: best = (C, acc)
C = best[0]
print(f'cross-validated accuracy on TRAIN: {best[1]:.3f} (C={C})')
model = fit(C).fit(E_train, train_y)

# Threshold: flag only when the top non-benign class is confident, tuned on TRAIN cross-validation.
cv_proba = cross_val_predict(fit(C), E_train, train_y, cv=StratifiedKFold(5, shuffle=True, random_state=0), method='predict_proba')
def decide(proba, thr):
    out = []
    for p in proba:
        k = int(np.argmax(p[1:])) + 1
        out.append(k if p[k] >= thr else 0)
    return np.array(out)
benign = train_y == 0
for thr in [0.5, 0.6, 0.7, 0.8, 0.9]:
    d = decide(cv_proba, thr)
    print(f'  TRAIN-cv thr {thr}: harmful caught {(d[~benign] != 0).mean():.2f}, benign wrongly flagged {(d[benign] != 0).mean():.3f}')
THR = float(sys.argv[3]) if len(sys.argv) > 3 else 0.7

def report(name, pred):
    harm = test_y != 0
    caught = (pred[harm] != 0).mean()
    right_kind = (pred[harm] == test_y[harm]).mean()
    fp = (pred[~harm] != 0).mean()
    per = ' '.join(f"{CLASSES[k]}={(pred[test_y == k] != 0).mean():.2f}" for k in range(1, 5))
    print(f'{name:22s} harmful caught {caught:.2f} (right kind {right_kind:.2f}) | benign wrongly flagged {fp:.2f} ({int((pred[~harm] != 0).sum())}/{int((~harm).sum())}) | {per}')
    return pred

rules = np.array([CLASSES.index(k) for k in json.loads(subprocess.run(
    ['node', 'rules_baseline.js'], input=json.dumps(test_x), capture_output=True, text=True, check=True).stdout)])
print(f'\nTEST set ({len(test_x)} passages, worded differently from TRAIN), model threshold {THR}:')
report('rules only (current)', rules)
m = report('model only', decide(model.predict_proba(E_test), THR))
both = np.where(rules != 0, rules, m)
report('rules + model', both)
print('\nbenign TEST passages the model wrongly flags:')
for i in np.where((m != 0) & (test_y == 0))[0]: print('  [' + CLASSES[m[i]] + ']', test_x[i])
print('harmful TEST passages still missed by rules + model:')
for i in np.where((both == 0) & (test_y != 0))[0]: print('  [' + CLASSES[test_y[i]] + ']', test_x[i])

# The app computes softmax(W.x + b) itself; make sure that reproduces the trained model.
z = E_test @ model.coef_.T + model.intercept_
manual = np.exp(z - z.max(axis=1, keepdims=True)); manual /= manual.sum(axis=1, keepdims=True)
assert np.abs(manual - model.predict_proba(E_test)).max() < 1e-5
print('\nconfidence of some TEST passages (for on-device comparison):')
P = model.predict_proba(E_test)
for i in np.argsort(-P[:, 1:].max(axis=1))[:6]:
    k = int(np.argmax(P[i, 1:])) + 1
    print(f'  {P[i, k]:.3f} [{CLASSES[k]}] {test_x[i]}')
print('\nlabels reported as false alarms from a real phone (top non-benign confidence after retraining):')
for row, p in zip(REPORTED_FALSE_ALARMS, model.predict_proba(embed(REPORTED_FALSE_ALARMS))):
    k = int(np.argmax(p[1:])) + 1
    print(f'  {p[k]:.2f} [{CLASSES[k]}] {row}')
for thr in [0.6, 0.7, 0.75, 0.8, 0.9]:
    d = decide(model.predict_proba(E_test), thr); harm = test_y != 0
    print(f'  TEST thr {thr}: harmful caught {(d[harm] != 0).mean():.2f}, ordinary wrongly flagged {int((d[~harm] != 0).sum())}/{int((~harm).sum())}')
LABEL = float(sys.argv[4]) if len(sys.argv) > 4 else 0.7
json.dump({'classes': CLASSES, 'hide': 0.9, 'label': LABEL, 'dim': int(E_train.shape[1]),
           'weights': [[round(float(v), 5) for v in row] for row in model.coef_],
           'bias': [round(float(v), 5) for v in model.intercept_]}, open(sys.argv[2], 'w'))
print('\nwrote', sys.argv[2])
