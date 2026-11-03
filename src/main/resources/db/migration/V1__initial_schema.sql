-- =============================================================================
-- V1 — initial relational schema for the coordination server.
--
-- Design notes:
--   * PDF binaries stay on the filesystem (blob reference = workflow id +
--     revision number directory layout); PostgreSQL owns all domain state:
--     users, workflows, signers, document versions, audit events, invitations.
--   * `username` is the workflow identity used by the existing API (alice,
--     bob, …); `email` is the identity used by invitations. Both are unique.
--   * Actor/user columns that the domain models as free text (audit actor,
--     revision creator, e.g. "system") stay plain text; columns that always
--     reference a real account carry real foreign keys.
-- =============================================================================

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(100) NOT NULL,
    email         VARCHAR(255)     NULL,
    password_hash VARCHAR(100)     NULL,  -- NULL while a user only exists as an invited placeholder
    display_name  VARCHAR(200)     NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ      NULL,
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT ck_users_status   CHECK (status IN ('ACTIVE', 'DISABLED', 'PENDING_INVITE'))
);

CREATE INDEX idx_users_email ON users (email);

CREATE TABLE workflows (
    id                   BIGSERIAL PRIMARY KEY,
    workflow_id          VARCHAR(64)  NOT NULL,
    document_name        VARCHAR(500) NOT NULL,
    policy               VARCHAR(20)  NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    blocked_reason       TEXT             NULL,
    claimed_turn_username VARCHAR(100)    NULL,
    claimed_turn_at      TIMESTAMPTZ      NULL,
    created_by           VARCHAR(100) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_workflows_workflow_id  UNIQUE (workflow_id),
    CONSTRAINT ck_workflows_policy CHECK (policy IN ('SEQUENTIAL', 'FLEXIBLE')),
    CONSTRAINT ck_workflows_status CHECK (status IN ('DRAFT', 'READY_FOR_SIGNING',
        'WAITING_FOR_SIGNER', 'SIGNATURE_SUBMITTED', 'VALIDATING', 'FAILED',
        'CANCELLED', 'COMPLETED')),
    CONSTRAINT fk_workflows_claimed_turn FOREIGN KEY (claimed_turn_username)
        REFERENCES users (username),
    CONSTRAINT fk_workflows_created_by   FOREIGN KEY (created_by)
        REFERENCES users (username)
);

CREATE INDEX idx_workflows_status ON workflows (status);

CREATE TABLE workflow_signers (
    id                  BIGSERIAL PRIMARY KEY,
    workflow_id         BIGINT       NOT NULL,
    signer_order        INT          NOT NULL,
    username            VARCHAR(100) NOT NULL,
    certificate_sha256  VARCHAR(64)      NULL,   -- NULL = UNBOUND slot
    bound_at            TIMESTAMPTZ      NULL,
    signed              BOOLEAN      NOT NULL DEFAULT FALSE,
    signed_at           TIMESTAMPTZ      NULL,
    signing_operation_id VARCHAR(64)     NULL,
    invited_at          TIMESTAMPTZ      NULL,
    -- Reserved for Phase 8 (per-signer placement persistence, PDF coordinates):
    signature_page      INT             NULL,
    signature_x         NUMERIC(12, 3)  NULL,
    signature_y         NUMERIC(12, 3)  NULL,
    signature_width     NUMERIC(12, 3)  NULL,
    signature_height    NUMERIC(12, 3)  NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_signers_workflow_order UNIQUE (workflow_id, signer_order),
    CONSTRAINT uq_signers_workflow_user  UNIQUE (workflow_id, username),
    CONSTRAINT ck_signers_cert_format CHECK (certificate_sha256 IS NULL
        OR certificate_sha256 ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT fk_signers_workflow FOREIGN KEY (workflow_id)
        REFERENCES workflows (id) ON DELETE CASCADE,
    CONSTRAINT fk_signers_user     FOREIGN KEY (username)
        REFERENCES users (username)
);

CREATE INDEX idx_signers_username ON workflow_signers (username);

CREATE TABLE document_versions (
    id              BIGSERIAL PRIMARY KEY,
    workflow_id     BIGINT       NOT NULL,
    revision_number INT          NOT NULL,
    parent_revision INT          NOT NULL,
    sha256          VARCHAR(64)  NOT NULL,
    size_bytes      BIGINT       NOT NULL,
    created_by      VARCHAR(100) NOT NULL,   -- username or "system"
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_versions_workflow_revision UNIQUE (workflow_id, revision_number),
    CONSTRAINT ck_versions_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_versions_workflow FOREIGN KEY (workflow_id)
        REFERENCES workflows (id) ON DELETE CASCADE
);

CREATE TABLE audit_events (
    id          BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT       NOT NULL,
    event_seq   BIGINT       NOT NULL,
    event_type  VARCHAR(50)  NOT NULL,
    actor       VARCHAR(100) NOT NULL,   -- username or "system" (provenance text)
    detail      TEXT             NULL,
    occurred_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_audit_workflow_seq UNIQUE (workflow_id, event_seq),
    CONSTRAINT fk_audit_workflow FOREIGN KEY (workflow_id)
        REFERENCES workflows (id) ON DELETE CASCADE
);

CREATE INDEX idx_audit_workflow ON audit_events (workflow_id, event_seq);

CREATE TABLE invitations (
    id           BIGSERIAL PRIMARY KEY,
    workflow_id  BIGINT       NOT NULL,
    signer_order INT          NOT NULL,
    email        VARCHAR(255) NOT NULL,
    token_hash   VARCHAR(64)  NOT NULL,   -- SHA-256 of the raw token; raw token never stored
    status       VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    invited_by   VARCHAR(100) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ  NOT NULL,
    accepted_at  TIMESTAMPTZ      NULL,
    accepted_by  VARCHAR(100)     NULL,
    CONSTRAINT uq_invitations_token UNIQUE (token_hash),
    CONSTRAINT ck_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED',
        'EXPIRED', 'REVOKED', 'DECLINED')),
    CONSTRAINT fk_invitations_workflow FOREIGN KEY (workflow_id)
        REFERENCES workflows (id) ON DELETE CASCADE
);

CREATE INDEX idx_invitations_email  ON invitations (email);
CREATE INDEX idx_invitations_status ON invitations (status);

-- At most one PENDING invitation per signer slot (brief §40: a resend
-- revokes the previous token first). Superseded ACCEPTED/REVOKED/EXPIRED
-- rows are kept for the audit story.
CREATE UNIQUE INDEX uq_invitations_one_pending_per_slot
    ON invitations (workflow_id, signer_order)
    WHERE status = 'PENDING';
