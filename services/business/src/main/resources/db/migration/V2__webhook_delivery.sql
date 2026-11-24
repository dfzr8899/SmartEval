ALTER TABLE grading_job ADD COLUMN webhook_url VARCHAR(2048) NULL;
ALTER TABLE grading_job ADD COLUMN webhook_secret VARCHAR(43) NULL;

CREATE TABLE webhook_delivery (
    id BINARY(16) PRIMARY KEY,
    job_id BINARY(16) NOT NULL,
    run_no INT NOT NULL,
    job_revision INT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    lease_id BINARY(16) NULL,
    lease_until DATETIME(6) NULL,
    last_http_status INT NULL,
    created_at DATETIME(6) NOT NULL,
    delivered_at DATETIME(6) NULL,
    FOREIGN KEY (job_id) REFERENCES grading_job(id),
    UNIQUE (job_id,run_no,job_revision,event_type),
    INDEX webhook_delivery_queue (status,available_at,lease_until)
);
