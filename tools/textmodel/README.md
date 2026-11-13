# Text classifier

Judges a passage as a whole, so reworded scams, engagement bait, adult posts and charged posts are caught even when they avoid the exact phrases in `shared/patterns.json`.

- **Embedding model:** Universal Sentence Encoder, the MediaPipe text-embedder build (6 MB, 100 numbers per passage), bundled as `android/app/src/main/assets/text_embedder.tflite`.
- **Classifier:** logistic regression over those numbers, five classes (benign, scam, adult, bait, charge), exported to `text_head.json`.
- **Training data:** `data.py`, 316 passages written for this project. None is scraped from real posts.

## Two confidence levels

| Confidence | What the app does |
|---|---|
| 0.9 or more | Treats it like a rule match: covered, with the reason and confidence shown |
| 0.6 to 0.9 | Mentioned in the pill as "possibly flagged"; not covered |
| below 0.6 | Nothing |

## Measured on the held-out test set

`TEST` in `data.py` is 100 passages (50 harmful, 50 ordinary) worded differently from the training set and from the rules, and never trained on.

| Method | Harmful caught | Ordinary wrongly flagged |
|---|---|---|
| Rules only | 2% (1 of 50) | 0 of 50 |
| Model, confidence 0.9 or more (covered) | 34% | 0 of 50 |
| Model, confidence 0.6 or more (covered or mentioned) | 76% | 4 of 50 |

Read these with care:

- The test set is small and was written by the same author as the training set, so real posts will be harder. With 50 ordinary passages, "0 of 50" is consistent with a true false-alarm rate of up to about 7%.
- The rules score 2% here because the test passages were deliberately worded to avoid them; on text that uses the listed phrases the rules catch far more.
- A larger embedding model (MobileBERT, 26 MB) was tried and did worse (20% caught).

## Retraining

```bash
curl -O https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite
pip install mediapipe scikit-learn numpy        # also needs libegl1 on Linux, and node for the rules baseline
python3 train.py universal_sentence_encoder.tflite text_head.json 0.9
```

## Not verified

The Android library that runs the embedding model ships for ARM phones only, so the classifier could not be run on the x86 emulator used for testing. On such devices the app logs that the model is unavailable and carries on with the rules. Whether the model loads and scores correctly on a real phone is unconfirmed; the settings screen shows "Text model: running" when it does.
