package com.paytm.wallet.service;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.dto.TransferDetailsResponse;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InsufficientFundsException;
import com.paytm.wallet.exception.SelfTransferException;
import com.paytm.wallet.exception.TransferNotFoundException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    public TransferService(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED, noRollbackFor = {InsufficientFundsException.class})
    public TransferResponse transfer(String fromUserId, TransferRequest request) {
        if (fromUserId.equals(request.toUser())) {
            throw new SelfTransferException("Cannot transfer funds to yourself");
        }

        String requestHash = request.toUser() + "|" + request.amountPaise();

        // 1. Check/Claim Idempotency Key
        int inserted = jdbcTemplate.update(
            "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, status) VALUES (?, ?, ?, 'PENDING') ON CONFLICT DO NOTHING",
            fromUserId, request.idempotencyKey(), requestHash
        );

        if (inserted == 0) {
            return handleReplay(fromUserId, request.idempotencyKey(), requestHash);
        }

        try {
            // 2. Determine deterministic locking order
            String firstId = fromUserId.compareTo(request.toUser()) < 0 ? fromUserId : request.toUser();
            String secondId = fromUserId.compareTo(request.toUser()) < 0 ? request.toUser() : fromUserId;

            // 3. Upsert wallets deterministically
            jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 0) ON CONFLICT (user_id) DO NOTHING", firstId);
            jdbcTemplate.update("INSERT INTO wallets (user_id, balance_paise) VALUES (?, 0) ON CONFLICT (user_id) DO NOTHING", secondId);

            // 4. Lock wallets deterministically
            jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ? FOR UPDATE", Long.class, firstId);
            jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ? FOR UPDATE", Long.class, secondId);

            // 5. Validate balance
            Long senderBalance = jdbcTemplate.queryForObject("SELECT balance_paise FROM wallets WHERE user_id = ?", Long.class, fromUserId);
            if (senderBalance == null || senderBalance < request.amountPaise()) {
                markIdempotencyFailed(fromUserId, request.idempotencyKey(), "INSUFFICIENT_FUNDS");
                meterRegistry.counter("wallet.transfers.rejected", "reason", "insufficient_funds").increment();
                log.warn("event=INSUFFICIENT_FUNDS_REJECTED from_user={} amount={}", fromUserId, request.amountPaise());
                throw new InsufficientFundsException("Insufficient funds");
            }

            // 6. Mutate balances
            jdbcTemplate.update("UPDATE wallets SET balance_paise = balance_paise - ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", request.amountPaise(), fromUserId);
            jdbcTemplate.update("UPDATE wallets SET balance_paise = balance_paise + ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?", request.amountPaise(), request.toUser());

            // 7. Create transfer record
            UUID transferId = UUID.randomUUID();
            jdbcTemplate.update(
                "INSERT INTO transfers (id, idempotency_key, from_user_id, to_user_id, amount_paise) VALUES (?, ?, ?, ?, ?)",
                transferId, request.idempotencyKey(), fromUserId, request.toUser(), request.amountPaise()
            );

            // 8. Update idempotency record to SUCCESS
            Long newBalance = senderBalance - request.amountPaise();
            jdbcTemplate.update(
                "UPDATE idempotency_keys SET status = 'SUCCESS', transfer_id = ?, response_balance = ? WHERE user_id = ? AND idempotency_key = ?",
                transferId, newBalance, fromUserId, request.idempotencyKey()
            );

            meterRegistry.counter("wallet.transfers.applied").increment();
            log.info("event=TRANSFER_APPLIED transfer_id={} amount_paise={} from_user={} to_user={}", transferId, request.amountPaise(), fromUserId, request.toUser());

            return new TransferResponse(transferId, newBalance);

        } catch (Exception e) {
            // For any unexpected internal errors, try to mark as FAILED (will rollback if it's a runtime exception not in noRollbackFor, which is fine for 500s)
            if (!(e instanceof InsufficientFundsException)) {
                log.error("Unexpected error during transfer", e);
            }
            throw e;
        }
    }

    private TransferResponse handleReplay(String fromUserId, String idempotencyKey, String currentHash) {
        meterRegistry.counter("wallet.transfers.idempotent.replay").increment();
        
        IdempotencyRecord record = jdbcTemplate.queryForObject(
            "SELECT request_hash, status, transfer_id, response_balance, error_code FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
            (rs, rowNum) -> new IdempotencyRecord(
                rs.getString("request_hash"),
                rs.getString("status"),
                rs.getObject("transfer_id", UUID.class),
                rs.getObject("response_balance", Long.class),
                rs.getString("error_code")
            ),
            fromUserId, idempotencyKey
        );

        if (record == null) {
            throw new IllegalStateException("Idempotency record disappeared");
        }

        if (!record.requestHash.equals(currentHash)) {
            log.warn("event=IDEMPOTENCY_CONFLICT user={} key={}", fromUserId, idempotencyKey);
            throw new IdempotencyConflictException("Idempotency key reused with different transfer body");
        }

        log.info("event=IDEMPOTENT_REPLAY user={} key={} status={}", fromUserId, idempotencyKey, record.status);

        if ("SUCCESS".equals(record.status)) {
            return new TransferResponse(record.transferId, record.responseBalance);
        } else if ("FAILED".equals(record.status)) {
            if ("INSUFFICIENT_FUNDS".equals(record.errorCode)) {
                throw new InsufficientFundsException("Insufficient funds");
            }
            throw new IllegalStateException("Original transfer failed with: " + record.errorCode);
        } else {
            throw new IdempotencyConflictException("Concurrent request processing or unknown state");
        }
    }

    private void markIdempotencyFailed(String userId, String key, String errorCode) {
        jdbcTemplate.update(
            "UPDATE idempotency_keys SET status = 'FAILED', error_code = ? WHERE user_id = ? AND idempotency_key = ?",
            errorCode, userId, key
        );
    }

    public TransferDetailsResponse getTransfer(UUID id) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT id, from_user_id, to_user_id, amount_paise, created_at FROM transfers WHERE id = ?",
                this::mapTransferRow,
                id
            );
        } catch (EmptyResultDataAccessException e) {
            throw new TransferNotFoundException("Transfer not found: " + id);
        }
    }

    private TransferDetailsResponse mapTransferRow(ResultSet rs, int rowNum) throws SQLException {
        return new TransferDetailsResponse(
            rs.getObject("id", UUID.class),
            rs.getString("from_user_id"),
            rs.getString("to_user_id"),
            rs.getLong("amount_paise"),
            rs.getTimestamp("created_at").toInstant()
        );
    }

    private record IdempotencyRecord(String requestHash, String status, UUID transferId, Long responseBalance, String errorCode) {}
}
