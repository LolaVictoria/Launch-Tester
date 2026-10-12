package com.example.launchcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class K6Runner {

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${launchcheck.allowed-hosts}")
    private String allowedHostsProperty;

    @Value("${launchcheck.p95-limit-ms:20}")
    private double p95LimitMs;

    @Value("${launchcheck.max-failed-percent:1.0}")
    private double maxFailedPercent;

    @Value("${launchcheck.degrade-factor:3.0}")
    private double degradeFactor;

    private record Target(String base, String path) {
    }

    private record Probe(int status, long bytes, long millis) {
    }

    private record Measurement(double p95, double avg, double failedRate, double rps, long total) {
    }

    // ---------- Step 3: one test at one user level ----------

    public RunResult run(RunRequest req) throws Exception {
        Target target = validateTarget(req.url(), req.path());
        int users = req.maxUsers() == null ? 50 : req.maxUsers();
        checkUsers(users);

        Measurement m = measure("/k6/script.js", target, users, null);
        return new RunResult(target.base() + target.path(), users,
                round(m.p95()), round(m.avg()), round(m.failedRate() * 100),
                round(m.rps()), m.total());
    }

    // ---------- Step 4: find where the app starts to struggle ----------

    public BreakingPointResult findBreakingPoint(BreakingPointRequest req) throws Exception {
        Target target = validateTarget(req.url(), req.path());

        List<Integer> levels = (req.levels() == null || req.levels().isEmpty())
                ? List.of(10, 25, 50, 100, 200)
                : req.levels().stream().sorted().toList();
        if (levels.size() > 8) {
            throw new IllegalArgumentException("Use at most 8 levels");
        }
        for (int level : levels) {
            checkUsers(level);
        }
        int seconds = req.secondsPerLevel() == null ? 10 : req.secondsPerLevel();
        if (seconds < 5 || seconds > 30) {
            throw new IllegalArgumentException("secondsPerLevel must be between 5 and 30");
        }

        // One quick request first: is the app reachable, and what does one response look like?
        Probe probe = probe(target);
        if (probe.status() >= 400) {
            throw new IllegalArgumentException("The target answered with HTTP " + probe.status()
                    + ". Check the path before testing.");
        }

        // Short warm-up so the first level is not measured on a cold server. Result is ignored.
        measure("/k6/constant.js", target, 5, 3);

        List<LevelResult> results = new ArrayList<>();
        double baselineP95 = 0;
        Integer breakingUsers = null;
        String reason = null;

        for (int users : levels) {
            Measurement m = measure("/k6/constant.js", target, users, seconds);
            double p95 = round(m.p95());
            double failedPercent = round(m.failedRate() * 100);
            if (results.isEmpty()) {
                baselineP95 = p95;
            }
            String status = statusOf(p95, failedPercent, baselineP95);
            results.add(new LevelResult(users, p95, round(m.avg()), failedPercent,
                    round(m.rps()), m.total(), status));

            if (!status.equals("ok")) {
                // Stop at the first level that struggles. Pushing harder could crash the app under test.
                breakingUsers = users;
                reason = reasonFor(status, users, p95, failedPercent, baselineP95);
                break;
            }
        }

        List<Finding> findings = buildFindings(target, probe, results);
        return new BreakingPointResult(target.base() + target.path(), results,
                breakingUsers, verdict(results, breakingUsers, reason), probe.bytes(), findings);
    }

    private String statusOf(double p95, double failedPercent, double baselineP95) {
        if (failedPercent > maxFailedPercent) {
            return "failing";
        }
        if (p95 > p95LimitMs) {
            return "slow";
        }
        if (baselineP95 > 0 && p95 > degradeFactor * baselineP95 && p95 > 100) {
            return "degrading";
        }
        return "ok";
    }

    private String reasonFor(String status, int users, double p95, double failedPercent, double baselineP95) {
        return switch (status) {
            case "failing" -> "At " + users + " users, " + failedPercent + "% of requests failed.";
            case "slow" -> "At " + users + " users, p95 was " + p95 + " ms, above the "
                    + (long) p95LimitMs + " ms limit.";
            default -> "At " + users + " users, p95 rose from " + baselineP95 + " ms to " + p95 + " ms.";
        };
    }

    private String verdict(List<LevelResult> results, Integer breakingUsers, String reason) {
        if (breakingUsers == null) {
            int last = results.get(results.size() - 1).users();
            return "No breaking point found up to " + last + " users.";
        }
        if (results.size() == 1) {
            return "Not healthy even at " + breakingUsers + " users. " + reason;
        }
        int lastGood = results.get(results.size() - 2).users();
        return "Healthy up to " + lastGood + " users. " + reason;
    }

    // ---------- Step 5: findings with suggested fixes ----------

    private List<Finding> buildFindings(Target target, Probe probe, List<LevelResult> results) {
        List<Finding> findings = new ArrayList<>();
        LevelResult first = results.get(0);
        LevelResult last = results.get(results.size() - 1);

        // 1. Huge response: usually missing pagination
        if (probe.bytes() >= 1_000_000) {
            findings.add(new Finding("large_response", "high",
                    "Each response is " + round(probe.bytes() / 1_000_000.0)
                            + " MB. Returning everything at once usually means missing pagination.",
                    "Paginate (limit and offset, or a cursor) and return only the fields the client needs."));
        }

        // 2. Same slow time for every request, small response: waiting on something slow
        if (first.p95Ms() >= p95LimitMs && first.avgMs() >= 0.9 * first.p95Ms() && probe.bytes() < 100_000) {
            findings.add(new Finding("fixed_wait", "high",
                    "Almost every request takes about " + (long) first.avgMs()
                            + " ms and the response is small. That points to waiting on something slow,"
                            + " such as an external call with no timeout.",
                    "Add a timeout, cache the result, or call the slow service in the background."));
        }

        // 3. Users grow but throughput stays flat: extra users only wait in line
        if (results.size() >= 2 && first.requestsPerSecond() > 0) {
            double userRatio = (double) last.users() / first.users();
            double rpsRatio = last.requestsPerSecond() / first.requestsPerSecond();
            if (userRatio >= 4 && rpsRatio < 1.5) {
                findings.add(new Finding("saturated", "medium",
                        "Users grew " + round(userRatio) + "x but requests per second grew only "
                                + round(rpsRatio) + "x (to about " + (long) last.requestsPerSecond()
                                + " per second), so extra users just wait in line. The limit could be the app,"
                                + " the database, or this machine, because the load tool shares its CPU.",
                        "Find the bottleneck (CPU, database connections, slow queries) before adding servers."));
            }
        }

        // 4. Burst from one client: is there any rate limiting?
        int burst = probe.bytes() > 1_000_000 ? 20 : 100;
        int limited = countRateLimited(target, burst);
        if (limited == 0) {
            findings.add(new Finding("no_rate_limiting", "low",
                    "Sent " + burst + " rapid requests from one client and none were rejected (no HTTP 429).",
                    "Add per-client rate limiting, for example at an API gateway."));
        }

        return findings;
    }

    private Probe probe(Target target) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.base() + target.path()))
                .timeout(Duration.ofSeconds(15)).GET().build();
        try {
            long start = System.nanoTime();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            long millis = (System.nanoTime() - start) / 1_000_000;
            return new Probe(response.statusCode(), response.body().length, millis);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Could not reach " + target.base() + target.path()
                    + ". Is the app running? (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while probing the target");
        }
    }

    /** Sends a quick burst from one client and counts how many got HTTP 429 (Too Many Requests). */
    private int countRateLimited(Target target, int total) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(target.base() + target.path()))
                .timeout(Duration.ofSeconds(15)).GET().build();
        ExecutorService pool = Executors.newFixedThreadPool(20);
        AtomicInteger limited = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            futures.add(pool.submit(() -> {
                try {
                    HttpResponse<Void> r = client.send(request, HttpResponse.BodyHandlers.discarding());
                    if (r.statusCode() == 429) {
                        limited.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // a failed request just does not count as rate limited
                }
            }));
        }
        for (Future<?> f : futures) {
            try {
                f.get(60, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // keep going
            }
        }
        pool.shutdownNow();
        return limited.get();
    }

    // ---------- Shared helpers ----------

    private Target validateTarget(String url, String pathInput) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url is required");
        }
        String base = url.trim().replaceAll("/+$", "");
        URI uri = URI.create(base);
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("url must start with http:// or https://");
        }
        List<String> allowedHosts = Arrays.stream(allowedHostsProperty.split(","))
                .map(String::trim).toList();
        String host = uri.getHost();
        if (host == null || !allowedHosts.contains(host.toLowerCase())) {
            throw new IllegalArgumentException("Host not allowed: " + host
                    + ". Only test apps you own. Allowed hosts: " + allowedHosts);
        }
        String path = (pathInput == null || pathInput.isBlank()) ? "/" : pathInput.trim();
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /");
        }
        return new Target(base, path);
    }

    private void checkUsers(int users) {
        if (users < 1 || users > 500) {
            throw new IllegalArgumentException("users must be between 1 and 500");
        }
    }

    /** Runs k6 once and reads the numbers it wrote. seconds is null for the fixed-length script. */
    private Measurement measure(String scriptResource, Target target, int users, Integer seconds)
            throws Exception {
        Path dir = Files.createTempDirectory("launchcheck-");
        Path script = dir.resolve("script.js");
        try (InputStream in = getClass().getResourceAsStream(scriptResource)) {
            if (in == null) {
                throw new IllegalStateException(scriptResource + " not found in resources");
            }
            Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
        }
        Path summary = dir.resolve("summary.json");
        Path log = dir.resolve("k6.log");

        // Each argument is separate (no shell), so input can't inject commands.
        List<String> command = new ArrayList<>(List.of(
                "k6", "run", "--quiet",
                "--summary-export=" + summary,
                "-e", "TARGET_URL=" + target.base(),
                "-e", "PATH_TO_HIT=" + target.path(),
                "-e", "VUS=" + users));
        if (seconds != null) {
            command.add("-e");
            command.add("DURATION=" + seconds + "s");
        }
        command.add(script.toString());

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();

        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("k6 ran longer than 120 seconds and was stopped");
        }
        if (!Files.exists(summary)) {
            throw new IllegalStateException("k6 produced no results. Its output was: "
                    + Files.readString(log));
        }

        JsonNode metrics = mapper.readTree(summary.toFile()).path("metrics");
        return new Measurement(
                metric(metrics, "http_req_duration", "p(95)"),
                metric(metrics, "http_req_duration", "avg"),
                metric(metrics, "http_req_failed", "value"),
                metric(metrics, "http_reqs", "rate"),
                (long) metric(metrics, "http_reqs", "count"));
    }

    private double metric(JsonNode metrics, String name, String field) {
        JsonNode node = metrics.path(name);
        JsonNode value = node.get(field);
        if (value == null) {
            throw new IllegalStateException("Could not find '" + field + "' in metric '"
                    + name + "'. What k6 wrote for it: " + node);
        }
        return value.asDouble();
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
