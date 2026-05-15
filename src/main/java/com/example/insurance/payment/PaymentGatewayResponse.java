package com.example.insurance.payment;

public class PaymentGatewayResponse {
    private String externalRef;
    private String status;

    public PaymentGatewayResponse() {}

    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String r) { this.externalRef = r; }
    public String getStatus() { return status; }
    public void setStatus(String s) { this.status = s; }
}
