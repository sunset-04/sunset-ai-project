package com.sunset.ai.utils;

import java.util.Set;

/** Keep business conversations separate even when clients reuse the same ID. */
public final class ConversationIds {
    private static final Set<String> TYPES = Set.of("chat", "service", "pdf", "game");

    private ConversationIds() { }

    public static String type(String type) {
        if (type == null || !TYPES.contains(type)) {
            throw new IllegalArgumentException("不支持的会话类型");
        }
        return type;
    }

    public static String validate(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("会话 ID 只能包含字母、数字、下划线或短横线，长度为 1-100");
        }
        return id;
    }

    public static String key(String type, String id) {
        return type(type) + ":" + validate(id);
    }

    public static String prompt(String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 20000) {
            throw new IllegalArgumentException("问题不能为空，且不能超过 20000 字符");
        }
        return prompt;
    }
}
