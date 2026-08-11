# workflow
- After coding sessions, update CONTEXT.md, SYMBOLS.md, and GRAPH.md with full file replacements (not diffs) reflecting all changes made. Keep concise, remove stale content, no speculation. Confidence: 0.80
- After implementation and documentation updates, commit ALL tracked changes (not just session-related files) and push; user explicitly directs this ("git commit and push") and delegates commit-message authorship to the agent. Confidence: 0.90
- Skip Debug build verification (assembleDebug); user does not require it. Confidence: 0.70
- During focused debugging/review tasks, keep scope strictly on the code; user declined doc updates mid-task ("no doc update required, review the tflite wiring"). Confidence: 0.75
- When changing model formats or inference libraries, wire end-to-end: gradle dependencies, encoder code, asset configs, and verification (user: "Wire it correctly after getting the context, lib change etc"). Confidence: 0.85
- When swapping inference models, re-tune ALL downstream thresholds to the new model's output distribution — not just the preprocessing/pipeline wiring. Clustering, matching, split/merge thresholds left at old-model values silently break downstream behavior (e.g. singleton "persons" instead of clusters). Also bump the model version tag to trigger migration and wipe stale cached embeddings/derived data. Confidence: 0.85
