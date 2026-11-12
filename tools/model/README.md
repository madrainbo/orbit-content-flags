# Adult-image model

`android/app/src/main/assets/nsfw_mobilenet_v2.tflite` is the MobileNetV2 model from [NSFWJS](https://github.com/infinitered/nsfwjs) (Infinite Red, MIT licence), converted to TFLite. Its own README reports about 90% accuracy for this model.

- **Input:** 224x224 RGB, values 0..1.
- **Output:** five probabilities in the order drawing, hentai, neutral, porn, sexy.
- **Rule in the app:** a picture is flagged when hentai + porn is at least 0.7, or sexy is at least 0.85. These thresholds are starting choices.

## Rebuilding the file

```bash
npm pack nsfwjs@4.3.0 && tar xzf nsfwjs-4.3.0.tgz
python3 extract_model.py package/dist/models/mobilenet_v2 out      # parses the files as text; runs nothing from the package
python3 convert_model.py out out/nsfw_mobilenet_v2.tflite          # needs tensorflow
python3 sanity.py out/nsfw_mobilenet_v2.tflite                     # needs scikit-image, scikit-learn, matplotlib
```

`convert_model.py` rebuilds the network layer by layer from the published topology, loads all 266 weight tensors by name with shape checks, converts with float16 weights, and confirms the TFLite output matches the rebuilt network (largest difference 0.0009 on random inputs).

## What has been checked

- 28 ordinary images (sample photographs bundled with scikit-image, scikit-learn and matplotlib, plus synthetic images) : 27 scored "neutral" as the top class. One microscope image scored 0.61 for porn, which is why the threshold is 0.7.
- **Not checked: any adult image.** The true-positive rate of this converted file has not been measured here; the 90% figure is the upstream project's claim for the original model.
