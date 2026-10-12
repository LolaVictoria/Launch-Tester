package com.example.launchcheck;

/** The measurements at one user level. status is ok, slow, degrading or failing. */
public record LevelResult(
        int users,
        double p95Ms,
        double avgMs,
        double failedPercent,
        double requestsPerSecond,
        long totalRequests,
        String status) {
}
