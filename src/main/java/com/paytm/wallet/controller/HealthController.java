package com.paytm.wallet.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/healthz")
    public String healthz() {
        return "OK";
    }
    @GetMapping("/readyz")
    public ResponseEntity<String> readyz() {
        try {
            jdbcTemplate.execute("SELECT 1");
            return ResponseEntity.ok("READY");
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("Database not ready");
        }
    }
}
