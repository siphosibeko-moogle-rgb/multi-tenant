-- V15: Supplier Directory (the only cross-business data in the system)
-- Do not edit V1..V14. Never join these tables to products, stock, sales or users.

CREATE TABLE supplier_listing (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL UNIQUE,          -- owning business: one listing per business
    business_name  VARCHAR(150) NOT NULL,
    business_type  VARCHAR(30)  NOT NULL
        CHECK (business_type IN ('WHOLESALER','MANUFACTURER','DISTRIBUTOR','RETAILER','OTHER')),
    contact_name   VARCHAR(100) NOT NULL,
    phone          VARCHAR(30)  NOT NULL,         -- never returned by search/detail
    email          VARCHAR(254) NOT NULL,         -- never returned by search/detail
    description    VARCHAR(500) NOT NULL,         -- what they supply
    category       VARCHAR(60)  NOT NULL,
    is_published   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_supplier_listing_published ON supplier_listing (is_published, created_at DESC);
CREATE INDEX idx_supplier_listing_category  ON supplier_listing (category);
CREATE INDEX idx_supplier_listing_type      ON supplier_listing (business_type);
-- ILIKE on business_name and description is enough for now (no extra index needed at school-project scale)

CREATE TABLE supplier_contact_request (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id           UUID NOT NULL REFERENCES supplier_listing (id) ON DELETE CASCADE,
    requester_tenant_id  UUID NOT NULL,
    requester_user_id    UUID NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'REVEALED'
        CHECK (status IN ('REVEALED','PENDING','APPROVED','DECLINED')),  -- last three are Phase 2
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_contact_request_listing   ON supplier_contact_request (listing_id, created_at DESC);
CREATE INDEX idx_contact_request_requester ON supplier_contact_request (requester_tenant_id, created_at DESC);

-- ---------------------------------------------------------------
-- Row-level security
-- ASSUMPTION: your existing policies read the tenant from a session
-- setting called app.tenant_id. If yours is named differently, change
-- current_setting('app.tenant_id', true) below to match V1..V14.
-- ---------------------------------------------------------------
ALTER TABLE supplier_listing ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_listing FORCE ROW LEVEL SECURITY;

-- Any authenticated session reads published rows; the owner also sees its own unpublished row.
CREATE POLICY supplier_listing_select ON supplier_listing
    FOR SELECT
    USING (is_published OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY supplier_listing_insert ON supplier_listing
    FOR INSERT
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY supplier_listing_update ON supplier_listing
    FOR UPDATE
    USING      (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

CREATE POLICY supplier_listing_delete ON supplier_listing
    FOR DELETE
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE supplier_contact_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE supplier_contact_request FORCE ROW LEVEL SECURITY;

-- Requester sees its own requests; a listing owner sees requests for its own listing.
CREATE POLICY contact_request_select ON supplier_contact_request
    FOR SELECT
    USING (
        requester_tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        OR listing_id IN (
            SELECT id FROM supplier_listing
            WHERE tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
        )
    );

-- A business can only record requests as itself.
CREATE POLICY contact_request_insert ON supplier_contact_request
    FOR INSERT
    WITH CHECK (requester_tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- No update/delete policies: requests are an append-only audit trail in Phase 1.
-- (Phase 2 will add an UPDATE policy so the listing owner can approve/decline.)
