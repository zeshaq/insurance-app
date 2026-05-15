-- Initial schema for the insurance-app demo.
-- Owned by Flyway; JPA's schema-generation is OFF (see persistence.xml).
CREATE TABLE quote (
    id              BIGSERIAL PRIMARY KEY,
    vehicle_vin     VARCHAR(17) NOT NULL,
    driver_age      INT NOT NULL,
    coverage_type   VARCHAR(20) NOT NULL,
    premium         NUMERIC(12,2) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'CALCULATED',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    valid_until     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_quote_vehicle_vin ON quote (vehicle_vin);
CREATE INDEX idx_quote_status      ON quote (status);
