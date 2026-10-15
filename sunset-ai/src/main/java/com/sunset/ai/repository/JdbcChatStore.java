package com.sunset.ai.repository;

import com.sunset.ai.entity.vo.MessageVO;
import com.sunset.ai.utils.ConversationIds;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Full history is archived independently of the model's sliding context window. */
@Repository
public class JdbcChatStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MessageCodec codec = new MessageCodec();

    @Value("${app.chat.initialize-schema:true}")
    private boolean initializeSchema = true;

    public JdbcChatStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    public void initialize() {
        if (initializeSchema) {
            new ResourceDatabasePopulator(new ClassPathResource("db/chat-schema.sql"))
                    .execute(Objects.requireNonNull(jdbc.getDataSource()));
        }
    }

    public void touch(String type, String chatId) {
        ConversationIds.key(type, chatId);
        if (jdbc.update("UPDATE ai_chat_session SET updated_at = CURRENT_TIMESTAMP(6) WHERE business_type = ? AND chat_id = ?",
                type, chatId) == 0) {
            try {
                jdbc.update("INSERT INTO ai_chat_session (business_type, chat_id) VALUES (?, ?)", type, chatId);
            } catch (DuplicateKeyException ignored) {
                // Another request has already created this conversation.
            }
        }
    }

    public List<String> chatIds(String type) {
        ConversationIds.type(type);
        return jdbc.queryForList("SELECT chat_id FROM ai_chat_session WHERE business_type = ? ORDER BY updated_at DESC, chat_id DESC",
                String.class, type);
    }

    public void append(String conversationId, List<Message> messages) {
        List<String> payloads = messages.stream().map(codec::encode).toList();
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < messages.size(); i++) {
                Message message = messages.get(i);
                jdbc.update("INSERT INTO ai_chat_message (conversation_id, message_type, content, payload) VALUES (?, ?, ?, ?)",
                        conversationId, message.getMessageType().getValue(), message.getText(), payloads.get(i));
            }
        });
    }

    public List<Message> context(String conversationId, int limit) {
        List<Message> result = new ArrayList<>(jdbc.query(
                "SELECT payload FROM ai_chat_message WHERE conversation_id = ? ORDER BY id DESC LIMIT ?",
                (rs, row) -> codec.decode(rs.getString("payload")), conversationId, limit));
        Collections.reverse(result);
        // Never start the prompt with a partial assistant/tool turn.
        while (!result.isEmpty() && result.get(0).getMessageType() != org.springframework.ai.chat.messages.MessageType.USER
                && result.get(0).getMessageType() != org.springframework.ai.chat.messages.MessageType.SYSTEM) {
            result.remove(0);
        }
        return result;
    }

    public List<MessageVO> history(String type, String chatId, int limit, Long beforeId) {
        String key = ConversationIds.key(type, chatId);
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("每页消息数量必须为 1-500");
        }
        String sql = "SELECT id, message_type, content, created_at FROM ai_chat_message WHERE conversation_id = ?";
        List<Object> args = new ArrayList<>(List.of(key));
        if (beforeId != null) {
            sql += " AND id < ?";
            args.add(beforeId);
        }
        sql += " ORDER BY id DESC LIMIT ?";
        args.add(limit);
        List<MessageVO> result = new ArrayList<>(jdbc.query(sql, (rs, row) -> {
            MessageVO message = new MessageVO();
            message.setId(rs.getLong("id"));
            message.setRole(rs.getString("message_type"));
            message.setContent(rs.getString("content"));
            message.setTimestamp(rs.getTimestamp("created_at").toInstant().toString());
            return message;
        }, args.toArray()));
        Collections.reverse(result);
        return result;
    }

    public void clear(String conversationId) {
        jdbc.update("DELETE FROM ai_chat_message WHERE conversation_id = ?", conversationId);
    }
}
