package com.devomind.gallerysearch

object SearchTuning {
    // Centralized knobs for quick relevance tuning.
    const val DefaultTopK = 20
    const val ScoreThreshold = 0.22f
    const val FallbackCount = 5
    const val MaxScoreDropRatio = 0.65f
}
