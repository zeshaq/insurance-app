package com.example.insurance.quote;

public record QuoteRequest(String vehicleVin, Integer driverAge, String coverageType) {}
