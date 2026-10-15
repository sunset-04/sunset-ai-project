-- 先注册目标账号，将下方用户名替换为实际值。
-- 仅在确认这些匿名历史都属于该账号时手动执行；已有归属的会话不会被覆盖。
-- 本脚本只新增归属，不修改聊天内容、原始 PDF 或向量数据。
SET @legacy_target_username = 'replace_with_your_username';

-- 应当只返回一个已注册账号；没有匹配账号时后续 INSERT 不会写入任何数据。
SELECT id, username, display_name FROM ai_user WHERE username = @legacy_target_username;
SELECT s.business_type, s.chat_id FROM ai_chat_session s
WHERE NOT EXISTS (SELECT 1 FROM ai_conversation_owner o WHERE o.business_type = s.business_type AND o.chat_id = s.chat_id);

START TRANSACTION;
INSERT INTO ai_conversation_owner (business_type, chat_id, user_id)
SELECT s.business_type, s.chat_id, u.id FROM ai_chat_session s
JOIN ai_user u ON u.username = @legacy_target_username
WHERE NOT EXISTS (SELECT 1 FROM ai_conversation_owner o WHERE o.business_type = s.business_type AND o.chat_id = s.chat_id);
COMMIT;
