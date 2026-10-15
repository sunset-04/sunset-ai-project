package com.sunset.ai.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcChatHistoryRepository implements ChatHistoryRepository {
    private final JdbcChatStore store;

    @Override
    public void save(String type, String chatId) {
        store.touch(type, chatId);
    }

    @Override
    public List<String> getChatIds(String type) {
        return store.chatIds(type);
    }
}
