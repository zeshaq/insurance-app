package com.example.insurance.policy;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PolicyRequest(
        @NotNull
        @Positive(message = "quoteId must be a positive integer")
        Long quoteId
) {}
