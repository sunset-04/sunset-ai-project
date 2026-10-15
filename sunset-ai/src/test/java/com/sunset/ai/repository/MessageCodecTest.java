package com.sunset.ai.repository;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.content.Media;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class MessageCodecTest {
    private final MessageCodec codec = new MessageCodec();

    @Test
    void textAndImageRemainAvailableAfterSerialization() {
        UserMessage message = UserMessage.builder().text("图片里有什么？")
                .metadata(Map.of("source", "upload"))
                .media(new Media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(new byte[]{1, 2, 3}))).build();
        UserMessage restored = (UserMessage) codec.decode(codec.encode(message));
        assertThat(restored.getText()).isEqualTo(message.getText());
        assertThat(restored.getMetadata()).containsEntry("source", "upload");
        assertThat(restored.getMedia().get(0).getDataAsByteArray()).containsExactly(1, 2, 3);
    }

    @Test
    void toolCallsAndResponsesRoundTripWithoutLosingIdentifiers() {
        AssistantMessage assistant = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call1", "function", "querySchool", "{}"))).build();
        AssistantMessage restored = (AssistantMessage) codec.decode(codec.encode(assistant));
        assertThat(restored.getToolCalls()).isEqualTo(assistant.getToolCalls());
        ToolResponseMessage response = ToolResponseMessage.builder().responses(
                List.of(new ToolResponseMessage.ToolResponse("call1", "querySchool", "[]"))).build();
        assertThat(((ToolResponseMessage) codec.decode(codec.encode(response))).getResponses()).isEqualTo(response.getResponses());
    }
}
