package com.example.insurance.payment;

import java.math.BigDecimal;

public record PaymentRequest(String policyNumber, BigDecimal amount, String currency) {}
