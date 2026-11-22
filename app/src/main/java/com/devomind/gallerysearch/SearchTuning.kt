package com.devomind.gallerysearch

object SearchTuning {
    const val DefaultTopK       = Int.MAX_VALUE  // no artificial cap
    const val PageSize          = 30             // show 30 at a time
    /**
     * The only score gate. Measured on 224 labelled photos with the shipped models
     * (tools/search_calibration): a flat 0.179 kept 75% of each subject's photos with 1.6% unrelated
     * ones. 0.175 was the F1 optimum (78% / 2.1%) and 0.180 is past a cluster of borderline
     * positives where recall drops to 74%; 0.179 takes the precision before that cliff. The old 0.19
     * plus a 75%-of-best rule kept 55%. Deliberately absolute - see the note in
     * GalleryRepository.search().
     */
    const val ScoreThreshold    = 0.179f
    /** TEMP test-build switch: the stricter floor this build can be flipped to on-device. */
    const val ScoreThresholdStrict = 0.19f
    const val FallbackCount     = 0
}
