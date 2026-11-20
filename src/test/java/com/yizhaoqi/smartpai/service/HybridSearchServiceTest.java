package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.util.ObjectBuilder;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.concurrent.ExecutorService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridSearchServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void vectorFailureStillRunsPermissionFilteredKeywordSearch() throws Exception {
        HybridSearchService service = new HybridSearchService();
        ElasticsearchClient es = mock(ElasticsearchClient.class);
        EmbeddingClient embedding = mock(EmbeddingClient.class);
        UserRepository users = mock(UserRepository.class);
        OrgTagCacheService tags = mock(OrgTagCacheService.class);
        QueryRewriteService rewrite = mock(QueryRewriteService.class);
        ReflectionTestUtils.setField(service, "esClient", es);
        ReflectionTestUtils.setField(service, "embeddingClient", embedding);
        ReflectionTestUtils.setField(service, "userRepository", users);
        ReflectionTestUtils.setField(service, "orgTagCacheService", tags);
        ReflectionTestUtils.setField(service, "queryRewriteService", rewrite);
        User user = new User();
        user.setId(42L);
        when(users.findByUsername("alice")).thenReturn(Optional.of(user));
        when(tags.getUserEffectiveOrgTags("alice")).thenReturn(List.of("team"));
        when(rewrite.rewriteVariants("流程", 3)).thenReturn(List.of("流程"));
        var keywordStarted = new java.util.concurrent.CountDownLatch(1);
        var keywordRanBeforeVectorFinished = new java.util.concurrent.atomic.AtomicBoolean();
        when(embedding.embed(anyList())).thenAnswer(call -> {
            keywordRanBeforeVectorFinished.set(keywordStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            throw new RuntimeException("embedding unavailable");
        });
        EsDocument doc = new EsDocument();
        doc.setFileMd5("file");
        doc.setChunkId(1);
        doc.setTextContent("关键词仍能召回");
        when(es.search(any(Function.class), eq(EsDocument.class))).thenAnswer(call -> {
            keywordStarted.countDown();
            Function<SearchRequest.Builder, ObjectBuilder<SearchRequest>> build = call.getArgument(0);
            SearchRequest request = build.apply(new SearchRequest.Builder()).build();
            assertTrue(request.knn().isEmpty());
            String query = request.query().toString();
            assertTrue(query.contains("userId") && query.contains("42"));
            assertTrue(query.contains("orgTag") && query.contains("team"));
            return SearchResponse.of(s -> s.took(1).timedOut(false)
                    .shards(sh -> sh.total(1).successful(1).failed(0))
                    .hits(h -> h.hits(hit -> hit.index("knowledge_base").id("1").score(1.0).source(doc))));
        });
        try {
            var results = service.multiPathSearchWithPermission("流程", "alice", 5);
            assertEquals(1, results.size());
            assertTrue(keywordRanBeforeVectorFinished.get(), "关键词召回不能等待向量接口返回");
            assertEquals("关键词仍能召回", results.get(0).getTextContent());
            verify(es, times(1)).search(any(Function.class), eq(EsDocument.class));
        } finally {
            ((ExecutorService) ReflectionTestUtils.getField(service, "executorService")).shutdownNow();
        }
    }
}
