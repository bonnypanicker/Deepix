package com.devomind.gallerysearch

object OnnxOutput {
    fun flattenFloatArray(value: Any): FloatArray {
        val floats = ArrayList<Float>()
        appendFloats(value, floats)
        return floats.toFloatArray()
    }

    private fun appendFloats(value: Any?, output: MutableList<Float>) {
        when (value) {
            null -> return
            is Float -> output += value
            is FloatArray -> value.forEach { output += it }
            is Array<*> -> value.forEach { appendFloats(it, output) }
            else -> throw IllegalArgumentException("Unsupported ONNX output type: ${value.javaClass.name}")
        }
    }
}
