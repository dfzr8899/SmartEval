CREATE TABLE tenant (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    name VARCHAR(160) NOT NULL,
    slug VARCHAR(64) NOT NULL,
    status VARCHAR(30) NOT NULL,
    CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    row_version INT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_tenant_slug (slug)
);

CREATE TABLE sys_user (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    username_normalized VARCHAR(120) NOT NULL,
    platform_admin BOOLEAN NOT NULL DEFAULT FALSE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    recovery_address VARCHAR(254) NULL,
    recovery_verified_at DATETIME(6) NULL,
    status VARCHAR(30) NOT NULL,
    CHECK (status IN ('ACTIVE', 'DISABLED')),
    row_version INT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_username (username_normalized),
    INDEX ix_platform_admin (platform_admin,status,created_at,id)
);

CREATE TABLE tenant_member (
    tenant_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    user_id BINARY(16) NOT NULL,
    status VARCHAR(30) NOT NULL,
    CHECK (status IN ('ACTIVE', 'DISABLED')),
    row_version INT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_tenant_user (tenant_id, user_id),
    INDEX ix_member_user (user_id,status,tenant_id),
    FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE TABLE member_role (
    tenant_id BINARY(16) NOT NULL,
    member_id BINARY(16) NOT NULL,
    role_key VARCHAR(30) NOT NULL,
    CHECK (role_key IN ('SCHOOL_ADMIN', 'TEACHER', 'STUDENT', 'RESEARCHER')),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, member_id, role_key),
    FOREIGN KEY (tenant_id, member_id) REFERENCES tenant_member (tenant_id, id)
);

CREATE TABLE auth_session (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    user_id BINARY(16) NOT NULL,
    active_tenant_id BINARY(16) NULL,
    refresh_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    row_version INT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_refresh_hash (refresh_hash),
    FOREIGN KEY (user_id) REFERENCES sys_user (id),
    FOREIGN KEY (active_tenant_id, user_id) REFERENCES tenant_member (tenant_id, user_id)
);

CREATE TABLE password_reset (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    user_id BINARY(16) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    UNIQUE KEY uq_reset_hash (token_hash),
    INDEX ix_password_reset_rate (user_id,created_at),
    FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE TABLE integration_client (
    tenant_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    name VARCHAR(140) NOT NULL,
    scopes JSON NOT NULL,
    status VARCHAR(30) NOT NULL,
    CHECK (status IN ('ACTIVE', 'DISABLED')),
    row_version INT NOT NULL DEFAULT 1,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE api_credential (
    tenant_id BINARY(16) NOT NULL,
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id, id),
    FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    client_id BINARY(16) NOT NULL,
    secret_hash BINARY(32) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    FOREIGN KEY (tenant_id, client_id) REFERENCES integration_client (tenant_id, id),
    UNIQUE KEY uq_api_key_hash (secret_hash)
);

CREATE TABLE audit_log (
    id BINARY(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    tenant_id BINARY(16) NULL,
    actor_type VARCHAR(30) NOT NULL,
    actor_id BINARY(16) NOT NULL,
    action VARCHAR(100) NOT NULL,
    target_type VARCHAR(60) NOT NULL,
    target_id BINARY(16) NULL,
    trace_id BINARY(16) NOT NULL,
    details JSON NOT NULL,
    FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    KEY ix_audit_lookup (tenant_id, created_at, id)
);

CREATE TABLE recovery_address_verification (
    id BINARY(16) NOT NULL,
    user_id BINARY(16) NOT NULL,
    address VARCHAR(254) NOT NULL,
    token_hash BINARY(32) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_recovery_verification_hash (token_hash),
    KEY idx_recovery_verification_rate (user_id, created_at),
    FOREIGN KEY (user_id) REFERENCES sys_user (id)
);

CREATE TABLE grading_asset (
    id BINARY(16) PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    client_id BINARY(16) NULL,
    name VARCHAR(255) NOT NULL,
    format VARCHAR(10) NOT NULL,
    sha256 BINARY(32) NOT NULL,
    size_bytes INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    FOREIGN KEY (tenant_id, client_id) REFERENCES integration_client(tenant_id,id)
);

CREATE TABLE grading_template (
    id BINARY(16) NOT NULL,
    tenant_id BINARY(16) NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(160) NOT NULL,
    questions JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (tenant_id,id,version_no),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id)
);

CREATE TABLE grading_job (
    id BINARY(16) PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    client_id BINARY(16) NULL,
    owner_id BINARY(16) NOT NULL,
    request_key VARBINARY(100) NOT NULL,
    request_hash BINARY(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    status VARCHAR(30) NOT NULL,
    revision INT NOT NULL DEFAULT 1,
    submission_input JSON NOT NULL,
    draft JSON NULL,
    sources JSON NULL,
    pinned_config JSON NULL,
    confirmed_by BINARY(16) NULL,
    confirmed_at DATETIME(6) NULL,
    run_no INT NOT NULL DEFAULT 0,
    lease_id BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    attempts INT NOT NULL DEFAULT 0,
    error_code VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE (tenant_id,owner_id,request_key),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    FOREIGN KEY (tenant_id, client_id) REFERENCES integration_client(tenant_id,id),
    INDEX grading_job_queue (status, lease_until, created_at),
    INDEX grading_job_workspace (tenant_id, created_at)
);

CREATE TABLE grading_item (
    job_id BINARY(16) NOT NULL,
    run_no INT NOT NULL,
    ordinal_no INT NOT NULL,
    item_input JSON NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED',
    attempts INT NOT NULL DEFAULT 0,
    lease_id BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    result JSON NULL,
    error_code VARCHAR(80) NULL,
    revision INT NOT NULL DEFAULT 1,
    PRIMARY KEY (job_id,run_no,ordinal_no),
    FOREIGN KEY (job_id) REFERENCES grading_job(id),
    INDEX grading_item_queue (status, lease_until)
);

CREATE TABLE grading_review (
    job_id BINARY(16) NOT NULL,
    run_no INT NOT NULL,
    ordinal_no INT NOT NULL,
    revision INT NOT NULL,
    reviewer_id BINARY(16) NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    previous_result JSON NULL,
    revised_result JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (job_id,run_no,ordinal_no,revision),
    FOREIGN KEY (job_id,run_no,ordinal_no) REFERENCES grading_item(job_id,run_no,ordinal_no)
);
