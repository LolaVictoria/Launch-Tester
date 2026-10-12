# launch-check

A small load-testing tool built with Spring Boot and k6. You give it the URL of an app you own,
and it ramps up virtual users to find where the app starts to struggle.

## What it does
- `POST /runs`: runs one load test and returns p95 latency, average latency, error rate and requests per second.
- `POST /runs/breaking-point`: tries 10, 25, 50, 100 and 200 users (10 seconds each) and reports the
  first level where the app is failing, slow, or degrading.
- Safety guard: only localhost and 127.0.0.1 can be tested, so it can't be pointed at other people's sites.

## Rules for "struggling" (in application.properties)
- failing: more than 1% of requests fail
- slow: p95 above 500 ms
- degrading: p95 more than 3x the first level's p95, and above 100 ms

## Tested against flawed-shop (a practice app with planted problems)
| Endpoint | Result |
|---|---|
| /products/1 (healthy) | no breaking point up to 200 users, p95 about 90 ms at 200 |
| /recommendations (a 1 second wait, like a slow external call) | slow even at 10 users, p95 about 1,008 ms |
| /products (returns all 50,000 rows, 3.4 MB) | slow even at 1 user, p95 about 597 ms |

Observation: on /products/1, users grew 20x but requests per second grew only about 1.2x, so extra
users mostly waited in line. The load tool shared the laptop's CPU, so this limit may be partly the machine.

## Limitations
- The app must be running before it can be tested, with realistic data. It cannot flag a problem in
  code that has not been run yet, unlike a static analyzer that reads the code while you type.
- It finds symptoms (slow, errors, flat throughput) and can only suggest the cause. It does not name the
  exact missing index or the exact slow line of code.
- Results depend on the test data. A table with 50 rows hides a missing index.
- It only sends simple GET requests, with no login, no request bodies, and one endpoint per run.
- The load tool and the app under test share one machine, so numbers are comparisons between
  endpoints, not production numbers.
- It only tests localhost, by design, so it cannot test a deployed app yet.

## Status
Working and tested: the single run and the breaking-point finder.
Added but not tested yet: a findings list (large response, fixed wait, flat throughput, no rate limiting).

## Run it
1. Install Java 21 or newer, Maven, and k6.
2. Start the app you want to test (for example flawed-shop on port 8080).
3. In this folder: `mvn spring-boot:run` (it listens on port 8081).
4. `curl.exe -s -X POST http://localhost:8081/runs/breaking-point -H "Content-Type: application/json" --data "@breaking-body.json"`

## Next
- Plain-English report, background runs, and a live dashboard.
- Run it in CI against a temporary copy of the app, so a slow endpoint blocks a merge before release.

## Status
Working and tested: the single run, the breaking-point finder, and the findings list
(large response, fixed wait, flat throughput, no rate limiting).

Note: results vary between runs on a shared laptop (for example /products/1 at 200 users had
p95 of about 90 ms in one run and 242 ms in another), so repeat a test before trusting one number.