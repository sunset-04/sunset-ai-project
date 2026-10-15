CREATE TABLE IF NOT EXISTS ai_chat_session (
    business_type VARCHAR(20) NOT NULL,
    chat_id VARCHAR(100) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (business_type, chat_id)
) /*!40100 DEFAULT CHARACTER SET utf8mb4 */;

CREATE TABLE IF NOT EXISTS ai_user (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(30) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) /*!40100 DEFAULT CHARACTER SET utf8mb4 */;

CREATE TABLE IF NOT EXISTS ai_conversation_owner (
    business_type VARCHAR(20) NOT NULL,
    chat_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (business_type, chat_id),
    INDEX idx_ai_owner_user (user_id, business_type)
) /*!40100 DEFAULT CHARACTER SET utf8mb4 */;

CREATE TABLE IF NOT EXISTS ai_pdf_task (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36) NOT NULL,
    chat_id VARCHAR(100) NOT NULL,
    filename VARCHAR(255) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message VARCHAR(500),
    result_json LONGTEXT,
    attempts INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE (user_id, chat_id),
    INDEX idx_ai_pdf_task_status (status, created_at)
) /*!40100 DEFAULT CHARACTER SET utf8mb4 */;

CREATE TABLE IF NOT EXISTS ai_chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(160) NOT NULL,
    message_type VARCHAR(20) NOT NULL,
    content LONGTEXT,
    payload LONGTEXT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_ai_message_conversation (conversation_id, id)
) /*!40100 DEFAULT CHARACTER SET utf8mb4 */;
