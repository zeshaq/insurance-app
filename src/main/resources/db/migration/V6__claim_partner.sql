-- Slice 10 (feature 5 part 2): cross-carrier (partner) lookup fields.
-- A claim involving another driver records whether the OTHER party's
-- vehicle is covered by a partner carrier — the lookup happens via
-- mTLS to a partner API (mocked by partner-mock in compose).
ALTER TABLE claim
    ADD COLUMN other_party_vin     VARCHAR(17),
    ADD COLUMN other_party_policy  VARCHAR(50),
    ADD COLUMN other_party_carrier VARCHAR(50);

CREATE INDEX idx_claim_other_party_vin ON claim (other_party_vin);
