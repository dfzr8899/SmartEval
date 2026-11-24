CREATE TABLE grading_job_asset (
    job_id BINARY(16) NOT NULL,
    asset_id BINARY(16) NOT NULL,
    PRIMARY KEY (job_id, asset_id),
    FOREIGN KEY (job_id) REFERENCES grading_job(id),
    FOREIGN KEY (asset_id) REFERENCES grading_asset(id),
    INDEX grading_job_asset_lookup (asset_id)
);

ALTER TABLE grading_asset ADD COLUMN cleanup_retry_at DATETIME(6) NULL;
ALTER TABLE grading_asset ADD COLUMN cleanup_claimed BOOLEAN NOT NULL DEFAULT FALSE;
CREATE INDEX grading_asset_age ON grading_asset(created_at, id);
