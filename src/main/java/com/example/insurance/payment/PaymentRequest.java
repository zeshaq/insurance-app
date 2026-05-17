package com.example.insurance.payment;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PaymentRequest(
        @NotBlank
        @Pattern(regexp = "POL-[A-F0-9]+",
                 message = "policyNumber must be of the form POL-XXXXXXXX")
        String policyNumber,

        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        BigDecimal amount,

        // Optional; defaulted to USD when null in PaymentService.
        // When provided, must be a 3-letter ISO 4217 alpha code.
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be uppercase ISO 4217")
        String currency
) {}
