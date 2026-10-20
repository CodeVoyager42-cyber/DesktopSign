-- V8 — one-time launch codes (Phase B: website → desktop handover).
--
-- New table only. A signed-in user asks for a code for ONE signing task;
-- the website opens signflow://launch?v=1&c=<code>; the desktop exchanges
-- the code once for a short-lived token limited to that task.
--
-- Only the SHA-256 of a code is stored. A code is bound to its user, its
-- workflow and signer slot, its purpose and the minimum desktop version at
-- issue time; it expires after two minutes and can be consumed exactly once
-- (the consuming UPDATE checks consumed_at IS NULL, so two concurrent
-- exchanges cannot both win). revoked_at also ends the desktop session that
-- was opened with the code ("this wasn't me").
CREATE TABLE launch_codes (
    id                    BIGSERIAL    PRIMARY KEY,
    code_hash             CHAR(64)     NOT NULL,
    username              VARCHAR(100) NOT NULL REFERENCES users (username)
                                       ON DELETE CASCADE ON UPDATE CASCADE,
    workflow_id           BIGINT       NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    signer_order          INTEGER      NOT NULL,
    purpose               VARCHAR(20)  NOT NULL,
    min_app_version       VARCHAR(20)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    expires_at            TIMESTAMPTZ  NOT NULL,
    consumed_at           TIMESTAMPTZ      NULL,
    consumed_app_version  VARCHAR(20)      NULL,
    consumed_device       VARCHAR(64)      NULL,
    revoked_at            TIMESTAMPTZ      NULL,
    CONSTRAINT uq_launch_codes_hash UNIQUE (code_hash),
    CONSTRAINT ck_launch_codes_purpose CHECK (purpose IN ('SIGN')),
    CONSTRAINT ck_launch_codes_order CHECK (signer_order >= 1),
    CONSTRAINT ck_launch_codes_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_launch_codes_user_created ON launch_codes (username, created_at);
CREATE INDEX idx_launch_codes_expires ON launch_codes (expires_at);
