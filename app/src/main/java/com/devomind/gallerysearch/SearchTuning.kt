package com.devomind.gallerysearch

object SearchTuning {
    const val DefaultTopK       = Int.MAX_VALUE  // no artificial cap
    const val PageSize          = 30             // show 30 at a time
    const val ScoreThreshold    = 0.175f         // blocks gibberish (~0.165) while allowing edge-case searches (~0.188)
    const val FallbackCount     = 0
    const val MaxScoreDropRatio = 0.75f
}
