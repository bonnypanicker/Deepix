# Debug Session: tab-switch-freeze [OPEN]

## Symptoms
- Switching from an album back to collections can stall for several seconds.
- Scroll position may restore incorrectly instead of resetting to the top.
- The app can sometimes crash after long browsing sessions.

## User Evidence
- adb analysis shows CLIP text model logs around the navigation event.
- adb analysis shows heavy GC pressure near the same time.
- adb analysis shows repeated WorkManager foreground transitions.

## Initial Hypotheses
1. Encoders or ONNX sessions are being recreated during section/tab transitions instead of being retained for the app lifetime.
2. Search/navigation jobs are still active during section switches and are updating UI after the screen context changed.
3. Background indexing work is repeatedly competing for memory/CPU during navigation and worsening GC pauses.
4. Recycler/grid rendering is rebuilding too much state on tab switch, causing large allocations and delayed scroll reset.
5. NNAPI/session configuration is increasing warm-up cost during runtime session creation.

## Plan
- Inspect encoder/session lifecycle, MainActivity navigation flow, and IndexWorker scheduling.
- Add runtime instrumentation first to confirm whether encoder/session creation or indexing restarts happen during tab switches.
- Apply the smallest fix supported by the evidence.

## Evidence So Far
- The app uses a single `MainActivity`, not a fragment-per-tab architecture.
- Main encoders are warmed once from `MainActivity.initializeCore()/loadEncodersInBackground()`, so tab switching alone does not explain encoder recreation.
- `IndexWorker` was creating a fresh `ImageEncoder` and calling `setForegroundAsync` on every progress callback.
- Album -> collection and other major screen changes were rebuilding timelines and diffing unrelated cell lists, which is a plausible freeze/crash source under memory pressure.

## Confirmed / Rejected
- Rejected: "fragment recreation on every tab switch" as the primary explanation for this project structure.
- Confirmed: worker-side encoder duplication and overly chatty foreground promotion add memory/CPU pressure.
- Confirmed: large cross-screen `DiffUtil` work on navigation can stall the UI and amplify GC pauses.
- Partially confirmed: extra startup windows are likely system permission UI, not extra app activities; trimmed startup permissions to reduce this.
- Rejected: "WorkManager restart loop" as stated; repeated foreground transitions were caused by frequent foreground updates from the same worker.
- Partially confirmed: large bitmap/tensor churn is plausible, but viewer image loading was already using Glide sizing and cleanup; only lighter decode format was added.
