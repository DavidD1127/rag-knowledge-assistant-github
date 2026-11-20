package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SearchControllerTest {
    @Test
    void authenticatedSearchUsesPrincipalNameInsteadOfNumericDatabaseId() {
        HybridSearchService search = mock(HybridSearchService.class);
        SearchController controller = new SearchController();
        ReflectionTestUtils.setField(controller, "hybridSearchService", search);
        when(search.searchWithPermission("流程", "alice", 5)).thenReturn(List.of());
        assertEquals(200, controller.hybridSearch("流程", 5, () -> "alice").get("code"));
        verify(search).searchWithPermission("流程", "alice", 5);
        verify(search, never()).search(anyString(), anyInt());
    }
}
