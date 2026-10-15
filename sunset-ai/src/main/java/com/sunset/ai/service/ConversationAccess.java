package com.sunset.ai.service;

import com.sunset.ai.utils.ConversationIds;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/** Ownership is checked before reading files, reading history or invoking a model. */
@Service
@DependsOn("jdbcChatStore")
@RequiredArgsConstructor
public class ConversationAccess {
    private final JdbcTemplate jdbc;
    private final IFileService files;

    public String userId() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        }
        return auth.getName();
    }

    public void require(String type, String chatId) { require(type, chatId, userId()); }
    public void require(String type, String chatId, String userId) {
        ConversationIds.key(type, chatId);
        if (!owners(type, chatId).contains(userId)) missing();
    }

    public void claim(String type, String chatId) {
        ConversationIds.key(type, chatId);
        String user = userId();
        List<String> owners = owners(type, chatId);
        if (!owners.isEmpty()) { if (!owners.contains(user)) missing(); return; }
        // Pre-login data is never silently assigned to the first person who guesses its ID.
        boolean legacy = jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_session WHERE business_type = ? AND chat_id = ?", Integer.class, type, chatId) > 0
                || jdbc.queryForObject("SELECT COUNT(*) FROM ai_chat_message WHERE conversation_id = ?", Integer.class, ConversationIds.key(type, chatId)) > 0;
        if (!legacy && "pdf".equals(type)) {
            try { files.getFile(chatId); legacy = true; }
            catch (ResponseStatusException e) { if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e; }
        }
        if (legacy) missing();
        try {
            jdbc.update("INSERT INTO ai_conversation_owner (business_type, chat_id, user_id) VALUES (?, ?, ?)", type, chatId, user);
        } catch (DuplicateKeyException ignored) { /* Concurrent requests must still verify the winner. */ }
        require(type, chatId, user);
    }

    public List<String> chatIds(String type) {
        ConversationIds.type(type);
        return jdbc.queryForList("""
                SELECT s.chat_id FROM ai_chat_session s JOIN ai_conversation_owner o
                ON s.business_type = o.business_type AND s.chat_id = o.chat_id
                WHERE o.user_id = ? AND s.business_type = ? ORDER BY s.updated_at DESC, s.chat_id DESC
                """, String.class, userId(), type);
    }
    private List<String> owners(String type, String chatId) {
        return jdbc.queryForList("SELECT user_id FROM ai_conversation_owner WHERE business_type = ? AND chat_id = ?", String.class, type, chatId);
    }
    private void missing() { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在或无权访问"); }
}
