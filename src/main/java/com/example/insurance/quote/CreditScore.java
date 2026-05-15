package com.example.insurance.quote;

/**
 * The wire shape returned by the credit-bureau MI mediator (which itself
 * proxies WireMock). Real bureau responses carry more fields — tradelines,
 * inquiries, freeze status — and the demo keeps just what the rating
 * algorithm uses.
 */
public record CreditScore(String vin, Integer score) {}
