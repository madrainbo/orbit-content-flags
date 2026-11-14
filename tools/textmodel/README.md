# Text classifier

Judges a passage as a whole, so reworded scams, engagement bait, adult posts and charged posts are caught even when they avoid the exact phrases in `shared/patterns.json`.

- **Embedding model:** Universal Sentence Encoder, the MediaPipe text-embedder build (6 MB, 100 numbers per passage), bundled as `android/app/src/main/assets/text_embedder.tflite`.
- **Classifier:** logistic regression over those numbers, five classes (benign, scam, adult, bait, charge), exported to `text_head.json`.
- **Training data:** `data.py`, 361 passages written for this project, including 45 interface labels (the names apps give their own buttons, pictures and counters) as ordinary examples. None is scraped from real posts.

## Two confidence levels

| Confidence | What the app does |
|---|---|
| 0.9 or more | Treats it like a rule match: covered, with the reason and confidence shown |
| 0.75 to 0.9 | Listed as "possible" on the flags screen and counted in the pill; not covered |
| below 0.75 | Nothing |

The model only judges passages of at least six words and 30 characters, and never sees control labels (see `UiChrome` in `android/core`).

## Measured on the held-out test set

`TEST` in `data.py` is 112 passages (50 harmful, 62 ordinary, 12 of them interface labels) worded differently from the training set and from the rules, and never trained on.

| Method | Harmful caught | Ordinary wrongly flagged |
|---|---|---|
| Rules only | 2% (1 of 50) | 0 of 62 |
| Model, confidence 0.9 or more (covered) | 28% | 1 of 62 |
| Model, confidence 0.75 or more (covered or listed) | 58% | 1 of 62 |

Read these with care:

- The test set is small and was written by the same author as the training set, so real posts will be harder.
- **First real-phone result (Instagram, November 2026):** the earlier version listed Instagram's own control labels as possible flags ("Send post. 49 shares. Button…", "Profile picture of…", "More actions for this post") at 65% to 82% confidence. Nothing like them was in the training data. They are recorded in `data.py` as `REPORTED_FALSE_ALARMS`; after retraining four of the five score 0.40 to 0.51 and one still scores 0.74, which is why control labels are also filtered out by rule before the model runs.
- The rules score 2% here because the test passages were deliberately worded to avoid them; on text that uses the listed phrases the rules catch far more.
- A larger embedding model (MobileBERT, 26 MB) was tried and did worse (20% caught).

## Retraining

```bash
curl -O https://storage.googleapis.com/mediapipe-models/text_embedder/universal_sentence_encoder/float32/latest/universal_sentence_encoder.tflite
pip install mediapipe scikit-learn numpy        # also needs libegl1 on Linux, and node for the rules baseline
python3 train.py universal_sentence_encoder.tflite text_head.json 0.9
```

## Device coverage

The Android library that runs the embedding model ships for ARM phones only, so the classifier cannot run on the x86 emulator used for testing; there the app logs that the model is unavailable and carries on with the rules. On a real ARM phone the model has been seen running (it produced the Instagram results above).
