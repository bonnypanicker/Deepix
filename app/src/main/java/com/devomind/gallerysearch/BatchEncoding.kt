package com.devomind.gallerysearch

/**
 * One batch of inference, with a per-image fallback for a batch call that fails.
 *
 * An [OutOfMemoryError] is not a per-image problem: it says this batch was too big for the heap, and
 * grinding through the same batch one image at a time is the same allocation ceiling reached from the
 * other side. So it escapes here and lets [IndexWorker] clamp the batch cap and retry — the branch it
 * has always had, which the old blanket `catch (Throwable)` made unreachable.
 */
object BatchEncoding {

    /**
     * Returns one slot per entry of [inputs]: the embedding, or null for an image the encoder could
     * not produce a vector for. [onImageFailure] reports those by index into [inputs].
     */
    fun encode(
        inputs: List<FloatArray>,
        encodeBatch: (List<FloatArray>) -> List<FloatArray>,
        encodeOne: (FloatArray) -> FloatArray,
        onBatchFailure: (Throwable) -> Unit,
        onImageFailure: (Int, Throwable) -> Unit
    ): List<FloatArray?> {
        try {
            return encodeBatch(inputs)
        } catch (oom: OutOfMemoryError) {
            throw oom
        } catch (error: Throwable) {
            onBatchFailure(error)
        }
        return inputs.mapIndexed { index, floats ->
            try {
                encodeOne(floats)
            } catch (oom: OutOfMemoryError) {
                throw oom
            } catch (error: Throwable) {
                onImageFailure(index, error)
                null
            }
        }
    }
}
