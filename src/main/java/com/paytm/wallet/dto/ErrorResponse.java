package com.paytm.wallet.dto;

public record ErrorResponse(
    String error,
    String message
) {}
