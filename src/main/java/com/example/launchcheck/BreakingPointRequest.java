package com.example.launchcheck;

import java.util.List;

/** levels and secondsPerLevel are optional. Defaults: 10, 25, 50, 100, 200 users, 10 seconds each. */
public record BreakingPointRequest(String url, String path, List<Integer> levels, Integer secondsPerLevel) {
}
