-- Slice 9 (feature 5): Claim filing with multipart upload + OCR.
-- photo_key is the MinIO object key inside the `claims` bucket; binary content
-- never lives in Postgres. ocr_text / ocr_confidence are populated from MI's
-- OCR mediator (synthetic data via the WireMock vision stub for the demo).
CREATE TABLE claim (
    id                  BIGSERIAL PRIMARY KEY,
    policy_number       VARCHAR(20)  NOT NULL REFERENCES policy(policy_number),
    description         TEXT,
    photo_key           VARCHAR(200),
    photo_content_type  VARCHAR(100),
    ocr_text            TEXT,
    ocr_confidence      NUMERIC(4,3),
    status              VARCHAR(20)  NOT NULL DEFAULT 'FILED',  -- FILED / UNDER_REVIEW / APPROVED / DENIED
    filed_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_claim_policy_number ON claim (policy_number);
CREATE INDEX idx_claim_status        ON claim (status);
