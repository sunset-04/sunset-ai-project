package com.sunset.ai.controller;

import com.sunset.ai.repository.JdbcChatStore;
import com.sunset.ai.service.PdfTaskService;
import com.sunset.ai.support.PdfTestDocuments;
import org.springframework.ai.document.Document;
import org.springframework.mock.web.MockMultipartFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:application-flow;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.ai.openai.api-key=test-only",
        "app.storage.directory=target/test-data/application-flow",
        "app.storage.legacy-directory=target/test-data/empty-legacy"
})
class ApplicationFlowTest {
    @MockitoBean OpenAiChatModel model;
    @MockitoBean OpenAiEmbeddingModel embedding;
    @Autowired WebApplicationContext context;
    @Autowired JdbcChatStore store;
    @Autowired PdfTaskService tasks;
    MockMvc mvc;

    @BeforeEach void prepare() {
        mvc = MockMvcBuilders.webAppContextSetup(context).defaultRequest(get("/").with(user("integration-user"))).apply(springSecurity()).build();
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(
                List.of(new Generation(new AssistantMessage("测试回复"))))));
    }

    @Test void streamedConversationIsArchivedAndCanBeReadThroughHistoryApi() throws Exception {
        var result = mvc.perform(post("/ai/chat").with(csrf()).param("prompt", "你好").param("chatId", "flow_chat"))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(10000);
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string("测试回复"));
        } else {
            assertThat(result.getResponse().getContentAsString()).isEqualTo("测试回复");
        }
        mvc.perform(get("/ai/history/chat/flow_chat")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("你好"))
                .andExpect(jsonPath("$[1].content").value("测试回复"))
                .andExpect(jsonPath("$[0].timestamp").isNotEmpty());
        assertThat(store.history("pdf", "flow_chat", 20, null)).isEmpty();
        verify(model).stream(any(Prompt.class));
    }

    @Test void invalidInputIsRejectedBeforeModelInvocation() throws Exception {
        mvc.perform(post("/ai/chat").with(csrf()).param("prompt", "hello").param("chatId", "../bad"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.ok").value(0));
        mvc.perform(get("/ai/pdf/file/missing")).andExpect(status().isNotFound());
        mvc.perform(get("/ai/history/invalid/session").param("limit", "1000"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(model);
    }

    @Test void uploadedShortPdfIsIncludedInTheModelPromptForAnOverviewQuestion() throws Exception {
        when(embedding.embed(any(Document.class))).thenReturn(new float[]{1, 0, 0});
        when(embedding.dimensions()).thenReturn(3);
        String chatId = "pdf_flow_" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "resume.pdf", "application/pdf",
                PdfTestDocuments.pdf("Personal resume. Applying for a Java backend developer internship."));
        var upload = mvc.perform(multipart("/ai/pdf/upload/" + chatId).file(file).with(csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").isNotEmpty()).andReturn();
        String taskId = new tools.jackson.databind.ObjectMapper().readTree(upload.getResponse().getContentAsString()).get("id").asText();
        tasks.dispatch();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!List.of("SUCCEEDED", "FAILED").contains(tasks.get(taskId, "integration-user").status()) && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(tasks.get(taskId, "integration-user").status()).isEqualTo("SUCCEEDED");
        var result = mvc.perform(post("/ai/pdf/chat").with(csrf()).param("prompt", "看看这个是做什么的").param("chatId", chatId)).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult(10000);
            mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        }
        var captured = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(captured.capture());
        assertThat(captured.getValue().getSystemMessage().getText()).contains("Personal resume", "Java backend", "完整文档");
        assertThat(store.history("pdf", chatId, 20, null)).extracting("content")
                .containsExactly("看看这个是做什么的", "测试回复");
    }
    @Test void loginPersistsSessionAndLogoutRevokesAccess() throws Exception {
        MockMvc anonymousMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        anonymousMvc.perform(get("/ai/history/chat")).andExpect(status().isUnauthorized());
        anonymousMvc.perform(post("/auth/register").contentType("application/json")
                .content("{\"username\":\"blocked\",\"password\":\"password123\"}"))
                .andExpect(status().isForbidden());
        String username = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 15);
        String body = "{\"username\":\"" + username + "\",\"password\":\"password123\",\"displayName\":\"Test User\"}";
        var registration = anonymousMvc.perform(post("/auth/register").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.displayName").value("Test User")).andReturn();
        var session = (org.springframework.mock.web.MockHttpSession) registration.getRequest().getSession(false);
        anonymousMvc.perform(get("/auth/me").session(session)).andExpect(jsonPath("$.user.username").value(username));
        anonymousMvc.perform(get("/ai/history/chat").session(session)).andExpect(status().isOk());
        anonymousMvc.perform(post("/auth/register").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isConflict());
        anonymousMvc.perform(post("/auth/login").with(csrf()).contentType("application/json")
                .content(body.replace("password123", "wrongpassword"))).andExpect(status().isUnauthorized());
        var login = anonymousMvc.perform(post("/auth/login").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn();
        var loggedIn = (org.springframework.mock.web.MockHttpSession) login.getRequest().getSession(false);
        anonymousMvc.perform(get("/ai/history/chat").session(loggedIn)).andExpect(status().isOk());
        anonymousMvc.perform(post("/auth/logout").session(loggedIn).with(csrf())).andExpect(status().isNoContent());
        assertThat(loggedIn.isInvalid()).isTrue();
        anonymousMvc.perform(get("/ai/history/chat")).andExpect(status().isUnauthorized());
    }

    @Test void anotherUserCannotReadOrContinueOwnedConversationOrClaimLegacyHistory() throws Exception {
        String id = "private_" + UUID.randomUUID();
        var response = mvc.perform(post("/ai/chat").with(csrf()).param("prompt", "private question").param("chatId", id)).andReturn();
        if (response.getRequest().isAsyncStarted()) { response.getAsyncResult(10000); mvc.perform(asyncDispatch(response)).andExpect(status().isOk()); }
        mvc.perform(get("/ai/history/chat/" + id).with(user("other-user"))).andExpect(status().isNotFound());
        mvc.perform(post("/ai/chat").with(csrf()).with(user("other-user")).param("prompt", "steal").param("chatId", id)).andExpect(status().isNotFound());
        mvc.perform(get("/ai/history/chat").with(user("other-user"))).andExpect(content().json("[]"));
        String legacy = "legacy_" + UUID.randomUUID();
        store.touch("chat", legacy);
        mvc.perform(post("/ai/chat").with(csrf()).param("prompt", "claim").param("chatId", legacy)).andExpect(status().isNotFound());
        verify(model, times(1)).stream(any(Prompt.class));
    }

    @Test void pdfTaskAndDownloadAreRestrictedToTheirOwner() throws Exception {
        when(embedding.embed(any(Document.class))).thenReturn(new float[]{1, 0, 0});
        when(embedding.dimensions()).thenReturn(3);
        String chatId = "pdf_private_" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "private.pdf", "application/pdf", PdfTestDocuments.pdf("Private PDF for its owner only."));
        var upload = mvc.perform(multipart("/ai/pdf/upload/" + chatId).file(file).with(csrf())).andExpect(status().isAccepted()).andReturn();
        String taskId = new tools.jackson.databind.ObjectMapper().readTree(upload.getResponse().getContentAsString()).get("id").asText();
        mvc.perform(get("/ai/pdf/tasks/" + taskId).with(user("other-user"))).andExpect(status().isNotFound());
        mvc.perform(get("/ai/pdf/tasks").param("chatId", chatId).with(user("other-user"))).andExpect(status().isNotFound());
        mvc.perform(post("/ai/pdf/tasks/" + taskId + "/retry").with(csrf()).with(user("other-user"))).andExpect(status().isNotFound());
        mvc.perform(get("/ai/pdf/file/" + chatId).with(user("other-user"))).andExpect(status().isNotFound());
        mvc.perform(post("/ai/pdf/chat").param("chatId", chatId).param("prompt", "steal").with(csrf()).with(user("other-user"))).andExpect(status().isNotFound());
        mvc.perform(multipart("/ai/pdf/upload/" + chatId).file(file).with(csrf()).with(user("other-user"))).andExpect(status().isNotFound());
        tasks.dispatch();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!List.of("SUCCEEDED", "FAILED").contains(tasks.get(taskId, "integration-user").status()) && System.nanoTime() < deadline) Thread.sleep(25);
        assertThat(tasks.get(taskId, "integration-user").status()).isEqualTo("SUCCEEDED");
        mvc.perform(get("/ai/pdf/file/" + chatId)).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"));
        verifyNoInteractions(model);
    }

}
