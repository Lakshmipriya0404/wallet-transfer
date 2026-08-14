package com.paytm.wallet;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InsufficientFundsException;
import com.paytm.wallet.service.TransferService;
import com.paytm.wallet.service.WalletService;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "JWT_SECRET=supersecretkeythatisatleast32byteslongforhmacsha256")
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
    private WalletService walletService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void testReciprocalRaceWithoutDeadlock() throws InterruptedException, ExecutionException, TimeoutException {
        String userA = "userA_" + UUID.randomUUID();
        String userB = "userB_" + UUID.randomUUID();

        // Give them both 10,000 initially, but wait, the test requires testing them when initially absent.
        // We can create them with 10,000 by direct insert to bypass the service for funding, but wallet creation is what we want to test.
        // Let's just fund them safely.
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 5000)", userA);
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 5000)", userB);

        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads * 2);
        List<Callable<Void>> tasks = new ArrayList<>();

        AtomicInteger successfulTransfers = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    TransferRequest req = new TransferRequest(userB, 100L, UUID.randomUUID().toString());
                    transferService.transfer(userA, req);
                    successfulTransfers.incrementAndGet();
                } catch (Exception e) {
                    if (!(e instanceof InsufficientFundsException)) throw e;
                }
                return null;
            });
            tasks.add(() -> {
                try {
                    TransferRequest req = new TransferRequest(userA, 100L, UUID.randomUUID().toString());
                    transferService.transfer(userB, req);
                    successfulTransfers.incrementAndGet();
                } catch (Exception e) {
                    if (!(e instanceof InsufficientFundsException)) throw e;
                }
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        // 10 second timeout for deadlock detection
        for (Future<Void> f : futures) {
            f.get(10, TimeUnit.SECONDS); // throws TimeoutException on deadlock
        }

        Long balanceA = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userA);
        Long balanceB = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userB);

        // Conservation of money: A + B must equal 10000 exactly
        assertEquals(10000L, balanceA + balanceB);

        // Exactly one wallet per user
        Integer countA = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets WHERE user_id = ?", Integer.class, userA);
        Integer countB = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets WHERE user_id = ?", Integer.class, userB);
        assertEquals(1, countA);
        assertEquals(1, countB);
        
        assertEquals(threads * 2, successfulTransfers.get());
    }

    @Test
    void testConcurrentAccountsCreation() throws InterruptedException, ExecutionException, TimeoutException {
        String user = "user_" + UUID.randomUUID();
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Callable<Void>> tasks = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                walletService.getOrCreateWallet(user);
                return null;
            });
        }

        List<Future<Void>> futures = executor.invokeAll(tasks);
        executor.shutdown();

        for (Future<Void> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM wallets WHERE user_id = ?", Integer.class, user);
        assertEquals(1, count); // exactly one wallet created
    }

    @Test
    void testIdempotencyReplayReturnsOriginalBalance() {
        String userSender = "userS_" + UUID.randomUUID();
        String userReceiver = "userR_" + UUID.randomUUID();
        
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 10000)", userSender);
        
        String key = UUID.randomUUID().toString();
        
        // 1. Initial transfer (1000)
        TransferResponse res1 = transferService.transfer(userSender, new TransferRequest(userReceiver, 1000L, key));
        assertEquals(9000L, res1.newBalance());
        
        // 2. Another completely different transfer drops balance to 8000
        transferService.transfer(userSender, new TransferRequest(userReceiver, 1000L, UUID.randomUUID().toString()));
        Long currentBalance = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, userSender);
        assertEquals(8000L, currentBalance);
        
        // 3. Replay original idempotency key
        TransferResponse res2 = transferService.transfer(userSender, new TransferRequest(userReceiver, 1000L, key));
        
        // MUST return 9000 (original outcome), not 8000 (current balance)
        assertEquals(9000L, res2.newBalance());
        assertEquals(res1.transferId(), res2.transferId());
    }

    @Test
    void testSameKeyDifferentBody() {
        String userSender = "userS_" + UUID.randomUUID();
        String userReceiver = "userR_" + UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 10000)", userSender);
        
        String key = UUID.randomUUID().toString();
        transferService.transfer(userSender, new TransferRequest(userReceiver, 1000L, key));
        
        // Use same key for different amount
        assertThrows(IdempotencyConflictException.class, () -> {
            transferService.transfer(userSender, new TransferRequest(userReceiver, 2000L, key));
        });
    }
}
