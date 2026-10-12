package com.example.launchcheck;

/** What we send back after k6 finishes. Times are in milliseconds. */
public record RunResult(
        String target,
        int maxUsers,
        double p95Ms,
        double avgMs,
        double failedPercent,
        double requestsPerSecond,
        long totalRequests) {
}
