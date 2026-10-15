package com.sunset.ai.controller;

import com.sunset.ai.repository.ChatHistoryRepository;
import com.sunset.ai.utils.ConversationIds;
import com.sunset.ai.service.ConversationAccess;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatClient chatClient;

    private final ChatHistoryRepository chatHistoryRepository;
    private final ConversationAccess access;

    @RequestMapping(value = "/chat", method = org.springframework.web.bind.annotation.RequestMethod.POST, produces = "text/plain;charset=UTF-8")
    public Flux<@NonNull String> chat(
            @RequestParam(value = "prompt", defaultValue = "") String prompt,
            @RequestParam("chatId") String conversationId,
            @RequestParam(value = "files", required = false) List<MultipartFile> files
    ) {
        String memoryId = ConversationIds.key("chat", conversationId);
        if (files != null && !files.isEmpty()) {
            if (files.size() > 5 || files.stream().mapToLong(MultipartFile::getSize).sum() > 20L * 1024 * 1024) {
                throw new IllegalArgumentException("附件最多 5 个，总大小不能超过 20 MB");
            }
            for (MultipartFile file : files) {
                String mime = file.getContentType();
                if (file.isEmpty() || mime == null || !(mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/"))) {
                    throw new IllegalArgumentException("请上传非空的图片、音频或视频附件");
                }
            }
            if (prompt.isBlank()) prompt = "请描述附件内容。";
        }
        ConversationIds.prompt(prompt);
        access.claim("chat", conversationId);
        // 1.保存会话id
        chatHistoryRepository.save("chat", conversationId);
        // 2.请求模型
        if (files == null || files.isEmpty()) {
            // 没有附件,纯文本聊天
            return textChat(prompt, memoryId);
        } else {
            // 有附件,多模态聊天
            return multiModalChat(prompt, memoryId, files);
        }
    }

    private Flux<@NonNull String> multiModalChat(String prompt, String conversationId, List<MultipartFile> files) {
        // 1.解析多媒体
        List<Media> media = files.stream().map(file -> new Media(
                        MimeType.valueOf(file.getContentType()),
                        file.getResource()))
                .toList();
        return chatClient.prompt()
                .user(p -> p.text(prompt).media(media.toArray(Media[]::new)))
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    private Flux<@NonNull String> textChat(String prompt, String conversationId) {
        return chatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }
}
