package com.paytm.wallet.service;

import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.exception.WalletNotFoundException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WalletService {

    private final JdbcTemplate jdbcTemplate;

    public WalletService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public WalletResponse getOrCreateWallet(String userId) {
        jdbcTemplate.update(
            "INSERT INTO wallets (user_id, balance_paise) VALUES (?, 0) ON CONFLICT (user_id) DO NOTHING",
            userId
        );
        return getBalance(userId);
    }

    public WalletResponse getBalance(String userId) {
        try {
            Long balance = jdbcTemplate.queryForObject(
                "SELECT balance_paise FROM wallets WHERE user_id = ?",
                Long.class,
                userId
            );
            return new WalletResponse(balance);
        } catch (EmptyResultDataAccessException e) {
            throw new WalletNotFoundException("Wallet not found for user: " + userId);
        }
    }
}
