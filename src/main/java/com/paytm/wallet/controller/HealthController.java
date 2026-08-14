package com.paytm.wallet.controller;

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
    public String readyz() {
        try {
            jdbcTemplate.execute("SELECT 1");
            return "READY";
        } catch (Exception e) {
            throw new RuntimeException("Database not ready", e);
        }
    }
}
