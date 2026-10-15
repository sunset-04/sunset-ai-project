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
@RequestMapping("/ai")
@RequiredArgsConstructor
public class CustomerServiceController {

    private final ChatClient serviceChatClient;

    private final ChatHistoryRepository chatHistoryRepository;
    private final ConversationAccess access;

    @RequestMapping(value = "/service", method = org.springframework.web.bind.annotation.RequestMethod.POST, produces = "text/plain;charset=UTF-8")
    public Flux<@NonNull String> service(@RequestParam("prompt") String prompt, @RequestParam("chatId") String conversationId) {
        String memoryId = ConversationIds.key("service", conversationId);
        ConversationIds.prompt(prompt);
        access.claim("service", conversationId);
        // 1.保存会话id
        chatHistoryRepository.save("service", conversationId);
        // 2.请求模型
        return serviceChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(CONVERSATION_ID, memoryId))
                .stream()
                .content();
    }
}
