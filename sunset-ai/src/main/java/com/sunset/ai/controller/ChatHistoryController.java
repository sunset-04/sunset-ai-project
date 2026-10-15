package com.sunset.ai.controller;

import com.sunset.ai.entity.vo.MessageVO;
import com.sunset.ai.repository.ChatHistoryRepository;
import com.sunset.ai.repository.JdbcChatStore;
import com.sunset.ai.service.ConversationAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ai/history")
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    private final JdbcChatStore chatStore;
    private final ConversationAccess access;

    @GetMapping("{type}")
    public List<String> getChatIds(@PathVariable String type) {
        return access.chatIds(type);
    }

    @GetMapping("/{type}/{chatId}")
    public List<MessageVO> getChatHistory(@PathVariable String type, @PathVariable String chatId,
                                        @RequestParam(defaultValue = "200") int limit,
                                        @RequestParam(required = false) Long beforeId) {
        access.require(type, chatId);
        return chatStore.history(type, chatId, limit, beforeId);
    }
}
