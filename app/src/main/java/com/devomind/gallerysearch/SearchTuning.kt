package com.devomind.gallerysearch

object SearchTuning {
    // Centralized knobs for quick relevance tuning.
    const val DefaultTopK = 10
    const val ScoreThreshold = 0.19f
    const val FallbackCount = 0
    const val MaxScoreDropRatio = 0.75f
}
