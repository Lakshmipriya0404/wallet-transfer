package com.paytm.wallet.controller;

import com.paytm.wallet.dto.WalletResponse;
import com.paytm.wallet.service.WalletService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final WalletService walletService;

    public AccountController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public WalletResponse getOrCreateWallet(Authentication authentication) {
        String userId = authentication.getName();
        return walletService.getOrCreateWallet(userId);
    }

    @GetMapping("/me")
    public WalletResponse getBalance(Authentication authentication) {
        String userId = authentication.getName();
        return walletService.getBalance(userId);
    }
}
