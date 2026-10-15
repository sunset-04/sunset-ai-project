package com.sunset.ai.repository;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

public class PersistentChatMemory implements ChatMemory {
    private final JdbcChatStore store;
    private final int contextWindow;

    public PersistentChatMemory(JdbcChatStore store, int contextWindow) {
        this.store = store;
        this.contextWindow = contextWindow;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        store.append(conversationId, messages);
    }

    @Override
    public List<Message> get(String conversationId) {
        return store.context(conversationId, contextWindow);
    }

    @Override
    public void clear(String conversationId) {
        store.clear(conversationId);
    }
}
