# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# workflow
- After coding sessions, update CONTEXT.md, SYMBOLS.md, and GRAPH.md with full file replacements (not diffs) reflecting all changes made. Keep concise, remove stale content, no speculation. Confidence: 0.80
- After implementation and documentation updates, commit ALL tracked changes (not just session-related files) and push. Confidence: 0.85
- Skip Debug build verification (assembleDebug); user does not require it. Confidence: 0.70
- During focused debugging/review tasks, keep scope strictly on the code; user declined doc updates mid-task ("no doc update required, review the tflite wiring"). Confidence: 0.75
- When changing model formats or inference libraries, wire end-to-end: gradle dependencies, encoder code, asset configs, and verification (user: "Wire it correctly after getting the context, lib change etc"). Confidence: 0.85

# communication
- Reports bugs by pasting detailed change summaries plus adb log snippets, and explicitly asks for careful fixes ("fix it very carefully"). Confidence: 0.80
- Writes terse, informal, lowercase commands with typos; expects the agent to infer intent and proceed without lengthy clarification. Confidence: 0.75

# environment
- Windows dev machine; Android builds via gradlew.bat using Android Studio's bundled JBR as JAVA_HOME; device debugging via adb logcat. Confidence: 0.85

# project
- Android gallery app (Kotlin) with on-device CLIP semantic search; iterates on model formats (ONNX → TFLite → ORT) and precisions (fp16 vision, int8 text). User is fluent in conversion specifics (e.g. --optimization_style Runtime to keep fp16 from upconverting to fp32). Confidence: 0.80

