package com.sunset.ai.utils;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ConversationIdsTest {
    @Test void differentBusinessesDoNotShareTheSameMemoryKey() {
        assertThat(ConversationIds.key("chat", "123")).isNotEqualTo(ConversationIds.key("pdf", "123"));
    }
    @Test void untrustedValuesCannotBeUsedInStoragePathsOrSearchExpressions() {
        for (String id : new String[]{"", "../path", "x' OR '1'", "a".repeat(101)}) {
            assertThatThrownBy(() -> ConversationIds.key("pdf", id)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> ConversationIds.key("unknown", "123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationIds.prompt(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
