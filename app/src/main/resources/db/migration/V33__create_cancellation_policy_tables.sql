CREATE TABLE cancellation_policies (
    id                  BIGSERIAL PRIMARY KEY,
    org_id              BIGINT,
    scope               VARCHAR(30) NOT NULL,
    created_by_admin_id BIGINT,
    created_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_cancellation_policies_org_id ON cancellation_policies(org_id);

CREATE UNIQUE INDEX uq_cancellation_policies_org_id
    ON cancellation_policies(org_id)
    WHERE org_id IS NOT NULL;

CREATE UNIQUE INDEX uq_cancellation_policies_default
    ON cancellation_policies(scope)
    WHERE scope = 'SYSTEM_DEFAULT';

CREATE TABLE cancellation_policy_tiers (
    id                 BIGSERIAL PRIMARY KEY,
    policy_id          BIGINT NOT NULL REFERENCES cancellation_policies(id) ON DELETE CASCADE,
    hours_before_event INTEGER,
    refund_percent     INTEGER NOT NULL,
    sort_order         INTEGER NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    CONSTRAINT chk_refund_percent_range CHECK (refund_percent >= 0 AND refund_percent <= 100),
    CONSTRAINT uq_policy_tier_sort UNIQUE (policy_id, sort_order)
);

CREATE INDEX idx_policy_tiers_policy_id ON cancellation_policy_tiers(policy_id);
CREATE INDEX idx_policy_tiers_sort_order ON cancellation_policy_tiers(sort_order);

INSERT INTO cancellation_policies (org_id, scope, created_by_admin_id)
VALUES (NULL, 'SYSTEM_DEFAULT', NULL);

INSERT INTO cancellation_policy_tiers (policy_id, hours_before_event, refund_percent, sort_order)
SELECT id, 72, 100, 1 FROM cancellation_policies WHERE scope = 'SYSTEM_DEFAULT';

INSERT INTO cancellation_policy_tiers (policy_id, hours_before_event, refund_percent, sort_order)
SELECT id, 24, 50, 2 FROM cancellation_policies WHERE scope = 'SYSTEM_DEFAULT';

INSERT INTO cancellation_policy_tiers (policy_id, hours_before_event, refund_percent, sort_order)
SELECT id, NULL, 0, 3 FROM cancellation_policies WHERE scope = 'SYSTEM_DEFAULT';
