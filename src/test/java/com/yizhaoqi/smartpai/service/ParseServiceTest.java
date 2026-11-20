package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ParseServiceTest {
    private final DocumentVectorRepository repository = mock(DocumentVectorRepository.class);
    private final ParseService service = new ParseService();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "documentVectorRepository", repository);
        ReflectionTestUtils.setField(service, "chunkSize", 64);
        ReflectionTestUtils.setField(service, "chunkOverlap", 8);
        ReflectionTestUtils.setField(service, "parentTokenSize", 256);
        ReflectionTestUtils.setField(service, "parentTokenOverlap", 24);
        ReflectionTestUtils.setField(service, "parentChunkSize", 512);
        ReflectionTestUtils.setField(service, "bufferSize", 8192);
        ReflectionTestUtils.setField(service, "maxMemoryThreshold", 1.0);
    }

    @Test
    void parsesTextAndPreservesOwnerAndParentContext() throws Exception {
        String text = "开始标记。知识库验收文档。\n\n" + "这是一段需要被分块保存的中文资料。".repeat(100) + "结束标记。";
        service.parseAndSave("md5", new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "42", "team", false);
        ArgumentCaptor<DocumentVector> captor = ArgumentCaptor.forClass(DocumentVector.class);
        verify(repository, atLeast(2)).save(captor.capture());
        var chunks = captor.getAllValues();
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            assertEquals(i + 1, chunk.getChunkId());
            assertEquals("42", chunk.getUserId());
            assertEquals("team", chunk.getOrgTag());
            assertFalse(chunk.isPublic());
            assertEquals("md5", chunk.getFileMd5());
            assertFalse(chunk.getTextContent().isBlank());
            assertTrue(chunk.getParentContent().contains(chunk.getTextContent()));
        }
        assertTrue(chunks.stream().anyMatch(c -> c.getTextContent().contains("开始标记")));
        assertTrue(chunks.stream().anyMatch(c -> c.getTextContent().contains("结束标记")));
    }

    @Test
    void parsesPublicTextWithoutChangingPermissions() throws Exception {
        service.parseAndSave("public-md5", new ByteArrayInputStream("公开测试资料".getBytes(StandardCharsets.UTF_8)), "7", "org", true);
        ArgumentCaptor<DocumentVector> captor = ArgumentCaptor.forClass(DocumentVector.class);
        verify(repository).save(captor.capture());
        assertTrue(captor.getValue().isPublic());
        assertEquals("公开测试资料", captor.getValue().getTextContent());
    }
}
