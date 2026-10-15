package com.sunset.ai.repository;

import com.sunset.ai.utils.ConversationIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class JdbcChatStoreTest {
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private JdbcChatStore store;

    @BeforeEach
    void initialize() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        store = new JdbcChatStore(jdbc, transactions);
        store.initialize();
    }

    @Test
    void completeHistorySurvivesNewInstanceWhileContextRemainsBounded() {
        String key = ConversationIds.key("chat", "session1");
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            messages.add(new UserMessage("question " + i));
            messages.add(new AssistantMessage("answer " + i));
        }
        new PersistentChatMemory(store, 20).add(key, messages);
        JdbcChatStore restarted = new JdbcChatStore(jdbc, transactions);
        restarted.initialize();
        assertThat(restarted.history("chat", "session1", 100, null)).hasSize(60);
        List<Message> context = new PersistentChatMemory(restarted, 20).get(key);
        assertThat(context).hasSize(20);
        assertThat(context.get(0).getText()).isEqualTo("question 20");
        assertThat(context.get(19).getText()).isEqualTo("answer 29");
    }

    @Test
    void businessTypesNeverShareMemoryEvenWithSameClientId() {
        store.touch("chat", "same");
        store.touch("chat", "same");
        store.touch("pdf", "same");
        store.append(ConversationIds.key("chat", "same"), List.of(new UserMessage("private chat")));
        store.append(ConversationIds.key("pdf", "same"), List.of(new UserMessage("PDF question")));
        assertThat(store.chatIds("chat")).containsExactly("same");
        assertThat(store.history("pdf", "same", 20, null)).singleElement()
                .satisfies(message -> assertThat(message.getContent()).isEqualTo("PDF question"));
    }

    @Test
    void historySupportsStableCursorPaginationAndRealTimestamps() {
        store.append("chat:page", List.of(new UserMessage("one"), new AssistantMessage("two"),
                new UserMessage("three"), new AssistantMessage("four")));
        var recent = store.history("chat", "page", 2, null);
        var older = store.history("chat", "page", 2, recent.get(0).getId());
        assertThat(recent).extracting("content").containsExactly("three", "four");
        assertThat(older).extracting("content").containsExactly("one", "two");
        assertThat(recent.get(0).getTimestamp()).isNotBlank();
        assertThatThrownBy(() -> store.history("chat", "page", 501, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clearOnlyRemovesSelectedConversation() {
        store.append("chat:first", List.of(new UserMessage("first")));
        store.append("chat:second", List.of(new UserMessage("second")));
        store.clear("chat:first");
        assertThat(store.history("chat", "first", 20, null)).isEmpty();
        assertThat(store.history("chat", "second", 20, null)).hasSize(1);
    }
}
