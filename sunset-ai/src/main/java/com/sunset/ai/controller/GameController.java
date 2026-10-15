package com.sunset.ai.controller;

import com.sunset.ai.repository.ChatHistoryRepository;
import com.sunset.ai.utils.ConversationIds;
import com.sunset.ai.service.ConversationAccess;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ai")
public class GameController {

    private final ChatClient gameChatClient;
    private final ChatHistoryRepository chatHistoryRepository;
    private final ConversationAccess access;

    @RequestMapping(value = "/game", method = org.springframework.web.bind.annotation.RequestMethod.POST, produces = "text/plain;charset=UTF-8")
    public Flux<@NonNull String> chat(@RequestParam("prompt") String prompt, @RequestParam("chatId") String conversationId) {
        String memoryId = ConversationIds.key("game", conversationId);
        ConversationIds.prompt(prompt);
        access.claim("game", conversationId);
        chatHistoryRepository.save("game", conversationId);
        return gameChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(CONVERSATION_ID, memoryId))
                .stream()
                .content();
    }
}
