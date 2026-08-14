package com.paytm.wallet.service;

import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.dto.TransferDetailsResponse;
import com.paytm.wallet.exception.IdempotencyConflictException;
import com.paytm.wallet.exception.InsufficientFundsException;
import com.paytm.wallet.exception.SelfTransferException;
import com.paytm.wallet.exception.TransferNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    public TransferService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResponse transfer(String fromUserId, TransferRequest request) {
        if (fromUserId.equals(request.toUser())) {
            throw new SelfTransferException("Cannot transfer funds to yourself");
        }

        UUID transferId = UUID.randomUUID();

        // 1. Ensure both sender and recipient wallets exist (Race-free get-or-create) to satisfy FK constraints
        jdbcTemplate.update(
            "INSERT INTO wallets (user_id, balance_paise) VALUES (?, 0) ON CONFLICT (user_id) DO NOTHING",
            fromUserId
        );
        jdbcTemplate.update(
            "INSERT INTO wallets (user_id, balance_paise) VALUES (?, 0) ON CONFLICT (user_id) DO NOTHING",
            request.toUser()
        );

        // 2. Idempotency Guard (Insert immediately, ON CONFLICT handles concurrent retries)
        int inserted = jdbcTemplate.update(
            "INSERT INTO transfers (id, idempotency_key, from_user_id, to_user_id, amount_paise) VALUES (?, ?, ?, ?, ?) ON CONFLICT (from_user_id, idempotency_key) DO NOTHING",
            transferId, request.idempotencyKey(), fromUserId, request.toUser(), request.amountPaise()
        );

        if (inserted == 0) {
            // Replay detected, abort regular flow and return standard response/conflict
            return handleReplay(fromUserId, request);
        }

        // 3. Deterministic Balance Mutation to prevent deadlocks
        if (fromUserId.compareTo(request.toUser()) < 0) {
            deductFromSender(fromUserId, request.amountPaise());
            addToRecipient(request.toUser(), request.amountPaise());
        } else {
            addToRecipient(request.toUser(), request.amountPaise());
            deductFromSender(fromUserId, request.amountPaise());
        }

        // 4. Return new balance
        Long newBalance = jdbcTemplate.queryForObject(
            "SELECT balance_paise FROM wallets WHERE user_id = ?",
            Long.class,
            fromUserId
        );

        log.info("Transfer applied successfully. transferId={}", transferId);
        return new TransferResponse(transferId, newBalance);
    }

    private TransferResponse handleReplay(String fromUserId, TransferRequest request) {
        log.info("Idempotent replay detected for key: {}", request.idempotencyKey());
        
        TransferDetailsResponse existing;
        try {
            existing = getTransferByIdempotencyKey(fromUserId, request.idempotencyKey());
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException("Transfer should exist but was not found for replay");
        }

        if (!existing.toUser().equals(request.toUser()) || !existing.amountPaise().equals(request.amountPaise())) {
            throw new IdempotencyConflictException("Idempotency key reused with different transfer body");
        }

        Long currentBalance = jdbcTemplate.queryForObject(
            "SELECT balance_paise FROM wallets WHERE user_id = ?",
            Long.class,
            fromUserId
        );

        return new TransferResponse(existing.id(), currentBalance);
    }

    private void deductFromSender(String userId, Long amount) {
        int updated = jdbcTemplate.update(
            "UPDATE wallets SET balance_paise = balance_paise - ? WHERE user_id = ? AND balance_paise >= ?",
            amount, userId, amount
        );
        if (updated == 0) {
            throw new InsufficientFundsException("Insufficient funds or sender wallet not found");
        }
    }

    private void addToRecipient(String userId, Long amount) {
        jdbcTemplate.update(
            "UPDATE wallets SET balance_paise = balance_paise + ? WHERE user_id = ?",
            amount, userId
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

    private TransferDetailsResponse getTransferByIdempotencyKey(String fromUserId, String idempotencyKey) {
        return jdbcTemplate.queryForObject(
            "SELECT id, from_user_id, to_user_id, amount_paise, created_at FROM transfers WHERE from_user_id = ? AND idempotency_key = ?",
            this::mapTransferRow,
            fromUserId, idempotencyKey
        );
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
}
