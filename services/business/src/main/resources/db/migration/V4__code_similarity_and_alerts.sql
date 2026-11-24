CREATE TABLE grading_similarity_run (
    job_id BINARY(16) NOT NULL,
    run_no INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    available_at DATETIME(6) NOT NULL,
    lease_id BINARY(16) NULL,
    alerts JSON NULL,
    PRIMARY KEY (job_id,run_no),
    FOREIGN KEY (job_id) REFERENCES grading_job(id),
    INDEX grading_similarity_queue (status,available_at)
);

INSERT INTO grading_similarity_run(job_id,run_no,available_at)
SELECT i.job_id,i.run_no,MIN(j.created_at)
FROM grading_item i JOIN grading_job j ON j.id=i.job_id
GROUP BY i.job_id,i.run_no HAVING COUNT(*)>=2;
