CREATE TABLE grading_collection (
    id BINARY(16) PRIMARY KEY,
    tenant_id BINARY(16) NOT NULL,
    created_by BINARY(16) NOT NULL,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    share_token VARBINARY(43) NOT NULL,
    status VARCHAR(12) NOT NULL,
    preset_config JSON NULL,
    template_id BINARY(16) NULL,
    template_version INT NULL,
    job_id BINARY(16) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE (share_token),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id),
    FOREIGN KEY (created_by) REFERENCES sys_user(id),
    FOREIGN KEY (job_id) REFERENCES grading_job(id),
    FOREIGN KEY (tenant_id,template_id,template_version) REFERENCES grading_template(tenant_id,id,version_no),
    INDEX grading_collection_workspace (tenant_id,created_at)
);

CREATE TABLE grading_collection_entry (
    id BINARY(16) PRIMARY KEY,
    collection_id BINARY(16) NOT NULL,
    submission_id VARCHAR(100) NOT NULL,
    submission_key BINARY(32) NOT NULL,
    answer_text MEDIUMTEXT NULL,
    language VARCHAR(16) NULL,
    asset_ids JSON NOT NULL,
    submitted_at DATETIME(6) NOT NULL,
    UNIQUE (collection_id,submission_key),
    FOREIGN KEY (collection_id) REFERENCES grading_collection(id)
);

CREATE TABLE grading_collection_asset (
    collection_id BINARY(16) NOT NULL,
    asset_id BINARY(16) NOT NULL,
    entry_id BINARY(16) NULL,
    PRIMARY KEY (collection_id,asset_id),
    FOREIGN KEY (collection_id) REFERENCES grading_collection(id),
    FOREIGN KEY (asset_id) REFERENCES grading_asset(id),
    FOREIGN KEY (entry_id) REFERENCES grading_collection_entry(id) ON DELETE CASCADE,
    INDEX grading_collection_asset_lookup (asset_id)
);
