package com.example.insurance.quote;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Quote request body. Jakarta Bean Validation constraints (issue #62)
 * enforce schema-level invariants before the value reaches the service
 * layer — a long VIN (>17 chars) used to crash the Postgres write with
 * "value too long for type character varying(17)" and surface as a 500;
 * the constraints below turn it into a 400 via
 * {@link com.example.insurance.error.ConstraintViolationExceptionMapper}.
 */
public record QuoteRequest(
        @NotBlank
        @Size(min = 3, max = 17, message = "vehicleVin must be 3-17 characters (ISO 3779 caps at 17)")
        String vehicleVin,

        @NotNull
        @Min(value = 16, message = "driverAge must be at least 16")
        @Max(value = 99, message = "driverAge must be at most 99")
        Integer driverAge,

        @NotBlank
        @Pattern(regexp = "BASIC|STANDARD|PREMIUM",
                 message = "coverageType must be BASIC, STANDARD, or PREMIUM")
        String coverageType
) {}
