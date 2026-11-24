ALTER TABLE grading_job ADD COLUMN available_at DATETIME(6) NULL;
ALTER TABLE grading_job MODIFY COLUMN webhook_secret VARCHAR(255) NULL;

ALTER TABLE grading_item ADD COLUMN available_at DATETIME(6) NULL;
ALTER TABLE grading_item ADD COLUMN created_at DATETIME(6) NULL;
CREATE INDEX grading_item_queue_fifo ON grading_item(status, available_at, lease_until, created_at, job_id, ordinal_no);
