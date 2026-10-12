package com.example.launchcheck;

/** One thing the tool noticed, with a suggested fix. severity: high, medium or low. */
public record Finding(String code, String severity, String message, String fix) {
}
