package com.paytm.wallet.controller;

import com.paytm.wallet.dto.TransferDetailsResponse;
import com.paytm.wallet.dto.TransferRequest;
import com.paytm.wallet.dto.TransferResponse;
import com.paytm.wallet.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request, Authentication authentication) {
        String fromUserId = authentication.getName();
        return transferService.transfer(fromUserId, request);
    }

    @GetMapping("/{id}")
    public TransferDetailsResponse getTransfer(@PathVariable UUID id, Authentication authentication) {
        String userId = authentication.getName();
        TransferDetailsResponse transfer = transferService.getTransfer(id);
        
        if (!transfer.fromUser().equals(userId) && !transfer.toUser().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not authorized to view this transfer");
        }
        
        return transfer;
    }
}
