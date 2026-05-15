package com.example.insurance.claim;

public class PartnerResponse {
    private boolean covers;
    private String policyNumber;
    private String carrier;
    private String verifiedAt;

    public PartnerResponse() {}

    public boolean isCovers() { return covers; }
    public void setCovers(boolean c) { this.covers = c; }
    public String getPolicyNumber() { return policyNumber; }
    public void setPolicyNumber(String p) { this.policyNumber = p; }
    public String getCarrier() { return carrier; }
    public void setCarrier(String c) { this.carrier = c; }
    public String getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(String t) { this.verifiedAt = t; }
}
