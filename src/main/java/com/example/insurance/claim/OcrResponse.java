package com.example.insurance.claim;

import java.math.BigDecimal;

public class OcrResponse {
    private String text;
    private BigDecimal confidence;

    public OcrResponse() {}

    public String getText() { return text; }
    public void setText(String t) { this.text = t; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal c) { this.confidence = c; }
}
