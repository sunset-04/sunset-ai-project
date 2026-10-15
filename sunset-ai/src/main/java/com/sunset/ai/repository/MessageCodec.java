package com.sunset.ai.repository;

import org.springframework.ai.chat.messages.*;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Store media and tool messages as well as text, so restored context stays usable. */
public final class MessageCodec {
    private final ObjectMapper mapper = new ObjectMapper();

    public String encode(Message message) {
        List<Media> media = message instanceof UserMessage user ? user.getMedia()
                : message instanceof AssistantMessage assistant ? assistant.getMedia() : List.of();
        List<StoredMedia> storedMedia = media.stream().map(item -> new StoredMedia(
                item.getMimeType().toString(), item.getData() instanceof String || item.getData() instanceof URI,
                item.getData() instanceof String || item.getData() instanceof URI
                        ? item.getData().toString() : Base64.getEncoder().encodeToString(item.getDataAsByteArray())))
                .toList();
        return mapper.writeValueAsString(new StoredMessage(message.getMessageType(), message.getText(),
                message.getMetadata(), storedMedia,
                message instanceof AssistantMessage assistant ? assistant.getToolCalls() : List.of(),
                message instanceof ToolResponseMessage tool ? tool.getResponses() : List.of()));
    }

    public Message decode(String json) {
        StoredMessage stored = mapper.readValue(json, StoredMessage.class);
        List<Media> media = stored.media().stream().map(item -> item.uri()
                ? new Media(MimeTypeUtils.parseMimeType(item.mimeType()), URI.create(item.data()))
                : new Media(MimeTypeUtils.parseMimeType(item.mimeType()),
                        new ByteArrayResource(Base64.getDecoder().decode(item.data())))).toList();
        return switch (stored.type()) {
            case USER -> UserMessage.builder().text(stored.text()).metadata(stored.metadata()).media(media).build();
            case ASSISTANT -> AssistantMessage.builder().content(stored.text()).properties(stored.metadata())
                    .media(media).toolCalls(stored.toolCalls()).build();
            case SYSTEM -> SystemMessage.builder().text(stored.text()).metadata(stored.metadata()).build();
            case TOOL -> ToolResponseMessage.builder().responses(stored.toolResponses()).metadata(stored.metadata()).build();
        };
    }

    public record StoredMedia(String mimeType, boolean uri, String data) { }
    public record StoredMessage(MessageType type, String text, Map<String, Object> metadata,
                                List<StoredMedia> media, List<AssistantMessage.ToolCall> toolCalls,
                                List<ToolResponseMessage.ToolResponse> toolResponses) { }
}
