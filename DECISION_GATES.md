# Decision gates

Two P0-tier items that are decisions rather than code. Recorded 2026-10-06 with what the primary
sources say, so the choice is made on facts and not re-litigated later.

---

## Gate A — Bundled model licences

**Status: open. Blocks a paid or public release; does not block personal use.**

`THIRD_PARTY_NOTICES.md` has the register. The short version: the YuNet detector is MIT and fine, while
Apple's MobileCLIP S2 weights (semantic search) and InsightFace's `w600k_mbf` (face recognition) are
both research-only at the *weights* layer. Neither is curable by attribution — a notices entry records
a restriction, it doesn't lift it.

Decided: keep `w600k_mbf` as shipped. No model swap, no `FaceEmbedder.ModelVersion` bump, no
re-embedding or re-clustering pass, so existing People data stays valid.

Still to decide before any commercial build:

1. Written terms from Apple (`MLModelResearch_use@group.apple.com`) and InsightFace
   (`recognition-oss-pack@insightface.ai`), **or**
2. Permissively-weighted replacements — an Apache/MIT-weighted CLIP-family encoder plus SFace — which
   costs a full re-index and a face re-embedding run, **or**
3. Ship as a free, non-commercial personal app and say so in the listing.

A `LICENSE` for Pixa's own source is a separate one-line decision (all rights reserved vs Apache-2.0)
and is deliberately not assumed here.

---

## Gate B — Android 15 `dataSync` foreground-service limit

**Status: the crash is bounded app-side (`ForegroundBudget`) — the dependency question stays open and is
now a tidiness call rather than a safety one.**

What the app does today: `targetSdk 35`, and three workers call `setForeground` with
`ForegroundInfo(..., ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)` — `IndexWorker`,
`CompressionWorker`, `CleanupWorker` — with `android:foregroundServiceType="dataSync"` declared in
`AndroidManifest.xml` and `FOREGROUND_SERVICE_MEDIA_PROCESSING`-style typing done by hand rather than
by WorkManager.

Documented behavior on Android 15 ([FGS timeouts][t], [15 behavior changes][b]):

- The `dataSync` budget is **6 hours total per rolling 24 hours for the whole app**, not per service.
  All three workers draw on one budget.
- At the cap the system calls `Service.onTimeout(int, int)` (API 35) and the app has a few seconds to
  `stopSelf()`. Not stopping is fatal: `android.app.RemoteServiceException: A foreground service of
  type dataSync did not stop within its timeout`.
- Later starts throw `ForegroundServiceStartNotAllowedException` ("Time limit already exhausted") until
  the user brings the app to the foreground, which is the only documented reset. Starting a `dataSync`
  service from `BOOT_COMPLETED` is banned outright.
- No documented exemption (device-owner, high-priority, `shortService`). Opting out of battery
  optimization explicitly does **not** lift an FGS time limit.
- `mediaProcessing` shares the same 6 h/24 h budget; the ~10 minute figure people quote is WorkManager's
  *expedited* quota, not the FGS type limit.

Why it bites here: a first indexing pass on a large library is exactly the multi-hour background job the
limit targets. At 33k photos, staying inside 6 hours needs roughly 92 photos encoded per minute
sustained; `IndexWorker` now logs a throughput line per pass, so the real rate — and therefore the
exposure — is measurable on the first device run instead of guessed.

What already limits the damage: the embedding index is checkpointed (`IndexCheckpoint`, base + append
journal) and folded on every exit path, including a pause or a thermal wait, so a hard stop costs at
most the records appended since the last 10-second flush, and can never leave the index without a valid
base. The next pass reconciles against disk and resumes.

What closed the crash itself (2026-10-06): [ForegroundBudget] keeps the app's own hour-by-hour ledger of
`dataSync` foreground time, all three workers book their stretch into it, and `IndexWorker` neither takes
the foreground nor continues past a batch once less than the 20-minute reserve is left. A pass stopped by
the budget queues itself again with the delay the rolling window actually needs ([IndexWorker.scheduleResumeAfterForegroundRefill])
and says so in the drawer as "Waiting for today's background time". Verified from the pinned artifact
rather than from the release notes: `javap` on `work-runtime-2.9.1.aar` shows `SystemForegroundService`
implementing only `onStartCommand` and `onDestroy`, so no `onTimeout` answer exists at that version, and
2.10.0's notes carry the fix (b/364508145).

Residual, deliberately not fixed: a single compression or cleanup run longer than the whole allowance is
recorded but not bounded, because stopping an action the user started by hand is a worse trade than the
hours it could cost. Indexing is the only pass long enough to need the guard.

Next steps, in order:

1. Reproduce on a device: the only claim in this file a build cannot prove is that a pass which runs into
   the reserve stops cleanly. Force it by lowering [ForegroundBudget.StopReserveMillis] to 0 against a
   large library and watch for "Foreground-time allowance spent" plus a checkpointed index afterwards —
   no emulator or device was attached while writing this.
2. Owner decision on the dependency, now that the cap is never reached: bump to 2.10/2.11 for the timeout
   handling as a second layer, or stay on 2.9.1. Two things make the bump not-free — only 2.9.1 is in the
   local Gradle cache, so the first sync must run online, and a timed-out worker returns STOP, which for a
   unique one-time request means CANCELLED, a state the indexing observer currently reads as "the pause
   path" (`MainActivity`, the `ENQUEUED/BLOCKED/CANCELLED` branch). That branch would have to learn the
   difference before the bump is safe, and it would only ever be exercised by the very pass this change
   now stops early.
3. Only after 1–2: consider whether `CleanupWorker` and `CompressionWorker` should keep the `dataSync`
   type at all, since they draw from the same 6-hour pool that indexing needs.

[t]: https://developer.android.com/develop/background-work/services/fgs/timeout
[b]: https://developer.android.com/about/versions/15/behavior-changes-15
