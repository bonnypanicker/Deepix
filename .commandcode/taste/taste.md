# Taste (Continuously Learned by [CommandCode][cmd])

[cmd]: https://commandcode.ai/

# workflow
See [workflow/taste.md](workflow/taste.md)
# communication
- Reports bugs by pasting detailed change summaries plus adb log snippets, and explicitly asks for careful fixes ("fix it very carefully"). Confidence: 0.80
- Writes terse, informal, lowercase commands with typos (e.g. "crossphoto similarity not workng... Fix it"); expects the agent to infer intent and proceed without lengthy clarification. Confidence: 0.80
- Sometimes arrives with a complete root-cause analysis and fix plan (detailed code snippets, verification of alternatives, specific threshold values) and expects the agent to implement it faithfully without re-investigation or deviation. Confidence: 0.75

# environment
- Windows dev machine; Android builds via gradlew.bat using Android Studio's bundled JBR as JAVA_HOME; device debugging via adb logcat. Confidence: 0.85

# project
- Android gallery app (Kotlin) with on-device CLIP semantic search; iterates on model formats (ONNX → TFLite → ORT) and precisions (fp16 vision, int8 text). User is fluent in conversion specifics (e.g. --optimization_style Runtime to keep fp16 from upconverting to fp32). Confidence: 0.80

- Face pipeline: YuNet detection + SFace (int8) embeddings, 5-pt landmark alignment to a 112×112 canonical crop, cosine-threshold person clustering. Verified SFace input contract: RGB, raw [0,255], no mean/scale (per cv::FaceRecognizerSF) — not the BGR [-1,1] the previous MobileFaceNet used. Confidence: 0.75


