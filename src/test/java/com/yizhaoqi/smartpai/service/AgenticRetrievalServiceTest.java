package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgenticRetrievalServiceTest {
    @Test
    void returnsFullToolResultsAndClearsUserBetweenRequests() {
        HybridSearchService search = mock(HybridSearchService.class);
        KnowledgeSearchTool tool = new KnowledgeSearchTool(search);
        ChatModel model = mock(ChatModel.class);
        var hit = new SearchResult("file", 1, "内部流程", 1.0);
        hit.setParentContent("完整父文档上下文");
        when(search.searchWithPermission("流程", "alice", 5)).thenReturn(List.of(hit));
        when(model.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                        .id("tool-1").name("searchKnowledgeBase").arguments("{\"query\":\"流程\"}").build())).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("true")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("false")).build());
        AgenticRetrievalService service = new AgenticRetrievalService(tool, model);
        var result = service.retrieve("内部流程", "alice");
        assertTrue(result.needed());
        assertEquals(List.of(hit), result.results());
        assertEquals("完整父文档上下文", result.results().get(0).getAnswerContent());
        assertNull(tool.getRetrievedResults());
        assertEquals("", tool.searchKnowledgeBase("未绑定用户不能检索"));
        var second = service.retrieve("你好", "bob");
        assertFalse(second.needed());
        assertNull(second.results());
        verify(search, times(1)).searchWithPermission(anyString(), anyString(), anyInt());
    }

    @Test
    void failedDecisionClearsThreadLocalBeforeFallback() {
        KnowledgeSearchTool tool = new KnowledgeSearchTool(mock(HybridSearchService.class));
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("upstream unavailable"));
        var service = new AgenticRetrievalService(tool, model);
        assertThrows(RuntimeException.class, () -> service.retrieve("问题", "alice"));
        assertNull(tool.getRetrievedResults());
        assertEquals("", tool.searchKnowledgeBase("不应带有上次用户"));
    }
}
