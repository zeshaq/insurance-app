package com.example.insurance.payment;

import java.math.BigDecimal;

public record PaymentGatewayChargeRequest(String policyNumber, BigDecimal amount, String currency) {}
