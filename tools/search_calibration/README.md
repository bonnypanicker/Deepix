# Search threshold calibration

Measures the app's semantic-search cutoff on photos whose subject is known, using the app's own
models and preprocessing.

```bash
python tools/search_calibration/fetch_set.py        # download the labelled photo set (once)
python tools/search_calibration/calibrate.py        # selftest, sweep, report, results.json
python tools/search_calibration/calibrate.py --selftest
python tools/search_calibration/calibrate.py --inspect "blue shirt"
```

## What is being measured

The app used to keep a photo when both of these held:

```
score >= best_score * SearchTuning.MaxScoreDropRatio     // 0.75, the "relative" cutoff
score >= SearchTuning.ScoreThreshold                     // 0.19
```

It now ships the second line only, at 0.179. The sweep still covers both terms, because the
relative cutoff is what this calibration found to be harmful: one strong photo raised the bar for
its whole query, and a subject whose best hit scored 0.33 lost the photos scoring 0.18 that it
exists to return. `results.json` keeps the pre-change setting as its baseline row. Nothing else
filters, so the threshold is the whole rule: `DefaultTopK` is `Int.MAX_VALUE` and `FallbackCount` is
0. `calibrate.py` sweeps the pair `(threshold, ratio)` over this rule and reports recall,
false-positive rate, precision and F1 per query plus the macro average.

0.179 rather than the 0.175 the sweep also names: 0.175 is the F1 maximum (78% recall, 2.1%
unrelated), but 0.180 falls off a measured cliff to 74% for a tenth of a point of precision, and
0.179 is the last point on the near side of it - 75% recall, 1.6% unrelated. Don't read that ledge
as a stable optimum: it is where this photo set's borderline positives happen to cluster, and a
score shift of half a point (the gap between these 640px thumbnails and full-resolution phone
photos) moves the operating point further than the 0.004 step does.

## Why it can be trusted

`app_clip.py` is a port of the shipped inference path, not a similar model: 256px shortest-edge
bilinear resize, centre crop, `/255`, **no mean/std** (`preprocessor_config.json` says
`do_normalize: false` - `s1.ort` normalizes internally), EXIF rotation like
`decodeOrientedBitmap`, L2-normalised embeddings, CLIP BPE with the `"a photo of "` prefix, and the
same query variants the app encodes.

`calibrate.py` refuses to run the sweep unless its selftest passes: the prefix is idempotent, ids
are 77 wide, embeddings are unit length and deterministic, query variants are distinct, and dog
photos outscore non-dogs on average. A wrong preprocessing pipeline fails that last check, which is
how the earlier mean/std port was caught.

## Labels

Positives are Wikimedia Commons category members photographed for that subject; every other
category is a negative. That keeps the ground truth independent of any search engine's ranking.

Known noise, reported rather than hidden: Commons categories include paintings, statues and museum
shots that survive the title filter, and the photos are 640px thumbnails while a phone indexes
full-resolution files. Absolute scores on a real library can sit slightly higher, so treat the
recommended threshold as a range, not a precise constant.

`photos/` and `embeddings.npz` are ignored; `manifest.json` and `results.json` are committed so a
number in a commit message can be traced back to the photo set and model version that produced it.
