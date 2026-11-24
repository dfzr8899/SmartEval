CREATE TABLE user_model_config (
    user_id BINARY(16) NOT NULL PRIMARY KEY,
    provider VARCHAR(40) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    model_name VARCHAR(120) NOT NULL,
    api_key_encrypted TEXT NOT NULL,
    response_format VARCHAR(30) NOT NULL DEFAULT 'json_object',
    updated_at DATETIME(6) NOT NULL,
    FOREIGN KEY (user_id) REFERENCES sys_user (id) ON DELETE CASCADE
);
