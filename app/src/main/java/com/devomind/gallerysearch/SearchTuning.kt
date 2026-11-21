package com.devomind.gallerysearch

object SearchTuning {
    const val DefaultTopK       = Int.MAX_VALUE  // no artificial cap
    const val PageSize          = 30             // show 30 at a time
    /**
     * The only score gate. Measured on 224 labelled photos with the shipped models
     * (tools/search_calibration): a flat 0.175 kept 78% of each subject's photos with 2% unrelated
     * ones; the old 0.19 plus a 75%-of-best rule kept 55%. Deliberately absolute - see the note in
     * GalleryRepository.search().
     */
    const val ScoreThreshold    = 0.175f
    const val FallbackCount     = 0
}
