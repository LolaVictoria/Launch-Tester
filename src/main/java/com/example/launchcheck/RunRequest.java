package com.example.launchcheck;

/** What the user sends: which app, which path, how many virtual users. */
public record RunRequest(String url, String path, Integer maxUsers) {
}
