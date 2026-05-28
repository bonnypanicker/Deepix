package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer

class TextEncoder(context: Context) : AutoCloseable {
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val inputIdsName: String
    private val attentionMaskName: String?
    private val outputName: String
    val tokenizer = ClipTokenizer(context)

    init {
        val modelBytes = context.assets.open("text_model_int8.onnx").use { it.readBytes() }
        val options = OnnxSessionOptions.create(Tag)
        session = environment.createSession(modelBytes, options)
        val inputNames = session.inputNames.toList()
        inputIdsName = inputNames.firstOrNull { it.contains("input_ids", ignoreCase = true) }
            ?: inputNames.first()
        attentionMaskName = inputNames.firstOrNull { it.contains("attention", ignoreCase = true) }
        outputName = session.outputNames.first()
        Log.d(Tag, "Text model inputs: ${session.inputNames}")
        Log.d(Tag, "Text model outputs: ${session.outputNames}")
    }

    fun encode(query: String): FloatArray {
        val tokenized = tokenizer.encode(query)
        val shape = longArrayOf(1, ClipTokenizer.ContextLength.toLong())

        OnnxTensor.createTensor(environment, LongBuffer.wrap(tokenized.inputIds), shape).use { inputIds ->
            val tensors = LinkedHashMap<String, OnnxTensor>()
            tensors[inputIdsName] = inputIds

            if (attentionMaskName != null) {
                OnnxTensor.createTensor(environment, LongBuffer.wrap(tokenized.attentionMask), shape).use { mask ->
                    tensors[attentionMaskName] = mask
                    return runTextModel(tensors)
                }
            }

            return runTextModel(tensors)
        }
    }

    private fun runTextModel(inputs: Map<String, OnnxTensor>): FloatArray {
        session.run(inputs).use { result ->
            val value = result.get(outputName).orElseThrow {
                IllegalStateException("Text model did not return output '$outputName'")
            }.value
            return EmbeddingUtils.l2Normalize(OnnxOutput.flattenFloatArray(value))
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val Tag = "CLIP"
    }
}
