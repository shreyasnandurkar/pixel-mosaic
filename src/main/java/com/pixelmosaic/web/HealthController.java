package com.pixelmosaic.web;

import ai.onnxruntime.OrtSession;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    private static final String VERSION = "1.0-SNAPSHOT";

    private final ObjectProvider<OrtSession> ortSession;
    private final int maxConcurrent;

    public HealthController(ObjectProvider<OrtSession> ortSession,
                            @Value("${pixelmosaic.max-concurrent}") int maxConcurrent) {
        this.ortSession = ortSession;
        this.maxConcurrent = maxConcurrent;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("OK");
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        return Map.of(
                "version", VERSION,
                "maxConcurrent", maxConcurrent,
                "modelLoaded", ortSession.getIfAvailable() != null);
    }
}
