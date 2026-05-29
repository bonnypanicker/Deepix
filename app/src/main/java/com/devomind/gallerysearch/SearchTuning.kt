package com.devomind.gallerysearch

object SearchTuning {
    const val DefaultTopK       = Int.MAX_VALUE  // no artificial cap
    const val PageSize          = 30             // show 30 at a time
    const val ScoreThreshold    = 0.19f          // this is the real filter
    const val FallbackCount     = 0
    const val MaxScoreDropRatio = 0.75f
}
