package com.example.launchcheck;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/runs")
public class RunController {

    private final K6Runner runner;

    public RunController(K6Runner runner) {
        this.runner = runner;
    }

    // One test at one user level. Waits about 30 seconds, then returns the numbers.
    @PostMapping
    public ResponseEntity<Object> run(@RequestBody RunRequest request) {
        try {
            return ResponseEntity.ok(runner.run(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // Tries rising user levels and reports where the app starts to struggle. Takes about a minute.
    @PostMapping("/breaking-point")
    public ResponseEntity<Object> breakingPoint(@RequestBody BreakingPointRequest request) {
        try {
            return ResponseEntity.ok(runner.findBreakingPoint(request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}
