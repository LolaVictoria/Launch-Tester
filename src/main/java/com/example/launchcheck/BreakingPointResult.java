package com.example.launchcheck;

import java.util.List;

/** breakingPointUsers is null when no breaking point was found. */
public record BreakingPointResult(
        String target,
        List<LevelResult> levels,
        Integer breakingPointUsers,
        String verdict,
        long responseBytes,
        List<Finding> findings) {
}
