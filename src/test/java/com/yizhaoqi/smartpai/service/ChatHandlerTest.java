package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.entity.SearchResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatHandlerTest {
    private final HybridSearchService search = mock(HybridSearchService.class);
    private final DeepSeekClient model = mock(DeepSeekClient.class);
    private final AgenticRetrievalService agent = mock(AgenticRetrievalService.class);
    private final RetrievalDecisionService fallback = mock(RetrievalDecisionService.class);
    private final WebSocketSession session = mock(WebSocketSession.class);
    private ChatHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        RedisTemplate<String, String> redis = mock(RedisTemplate.class, RETURNS_DEEP_STUBS);
        when(redis.opsForValue().get(anyString())).thenReturn("conversation");
        when(session.getId()).thenReturn("session");
        when(session.isOpen()).thenReturn(true);
        handler = new ChatHandler(redis, search, model, fallback, agent, mock(ChatMemoryService.class));
        // 在模型边界模拟401，既验证错误提示，也及时结束异步响应。
        doAnswer(call -> {
            Consumer<Throwable> error = call.getArgument(4);
            error.accept(WebClientResponseException.create(401, "Unauthorized", null, null, null));
            return null;
        }).when(model).streamResponse(anyString(), anyString(), anyList(), any(), any());
    }

    @Test
    void reusesToolResultsAndExplainsInvalidModelKey() throws Exception {
        SearchResult hit = new SearchResult("file", 1, "子片段", 1.0);
        hit.setParentContent("完整父文档");
        when(agent.retrieve("问题", "alice")).thenReturn(new AgenticRetrievalService.RetrievalResult(true, List.of(hit)));
        handler.processMessage("alice", "问题", session);
        Thread.sleep(3200); // 等待完成检测线程，确认不会用二次错误覆盖认证失败。
        verifyNoInteractions(search);
        verify(model).streamResponse(eq("问题"), contains("完整父文档"), anyList(), any(), any());
        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, atLeastOnce()).sendMessage(messages.capture());
        assertTrue(messages.getAllValues().stream().anyMatch(m -> m.getPayload().contains("API 密钥")));
        assertEquals(1, messages.getAllValues().stream().filter(m -> m.getPayload().contains("\"error\"")).count());
    }

    @Test
    void emptySuccessfulToolResultDoesNotTriggerSecondSearch() {
        when(agent.retrieve("问题", "alice")).thenReturn(new AgenticRetrievalService.RetrievalResult(true, List.of()));
        handler.processMessage("alice", "问题", session);
        verifyNoInteractions(search);
    }

    @Test
    void failedAgentFallsBackAndSearchesOnce() {
        when(agent.retrieve("问题", "alice")).thenThrow(new RuntimeException("decision unavailable"));
        when(fallback.needRetrieval("问题")).thenReturn(true);
        when(search.searchWithPermission("问题", "alice", 5)).thenReturn(List.of());
        handler.processMessage("alice", "问题", session);
        verify(fallback).needRetrieval("问题");
        verify(search, times(1)).searchWithPermission("问题", "alice", 5);
    }
}
