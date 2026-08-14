package com.paytm.wallet;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.service.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@Testcontainers
class TransferConcurrencyIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testConcurrentGetOrCreateAndTransferRace() throws InterruptedException {
        String userA = "userA_" + UUID.randomUUID();
        String userB = "userB_" + UUID.randomUUID();

        // Bootstrap sender funds
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 10000)", userA);

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();

        // 50 concurrent first-transfers from A to B
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                TransferRequest req = new TransferRequest(userB, 100L, UUID.randomUUID().toString());
                transferService.transfer(userA, req);
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        // Ensure all succeeded
        futures.forEach(f -> {
            try {
                f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Verify Conservation of Money
        Long balanceA = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userA);
        Long balanceB = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userB);

        assertEquals(10000 - (100 * threads), balanceA);
        assertEquals((long) (100 * threads), balanceB);

        // Verify exactly two wallets exist
        Integer countB = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets WHERE user_id = ?", Integer.class, userB);
        assertEquals(1, countB);
    }

    @Test
    void testConcurrentIdempotentRetries() throws InterruptedException {
        String userC = "userC_" + UUID.randomUUID();
        String userD = "userD_" + UUID.randomUUID();
        
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 5000)", userC);

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();

        // Same idempotency key for all concurrent requests
        String idempotencyKey = UUID.randomUUID().toString();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                TransferRequest req = new TransferRequest(userD, 500L, idempotencyKey);
                transferService.transfer(userC, req);
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        // Verify Money moved exactly ONCE
        Long balanceC = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userC);
        Long balanceD = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userD);

        assertEquals(4500, balanceC);
        assertEquals(500, balanceD);
    }
}
