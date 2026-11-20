package com.yizhaoqi.smartpai.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ParseServiceUnitTest {
    private List<String> split(String text, int size, int overlap) {
        ParseService service = new ParseService();
        ReflectionTestUtils.setField(service, "chunkSize", size);
        ReflectionTestUtils.setField(service, "chunkOverlap", overlap);
        return ReflectionTestUtils.invokeMethod(service, "splitChildren", text);
    }

    @Test
    void shortTextIsPreserved() {
        assertEquals(List.of("短文本测试"), split("短文本测试", 64, 8));
    }

    @Test
    void emptyTextCreatesNoChunks() {
        assertTrue(split("", 64, 8).isEmpty());
    }

    @Test
    void longTextRetainsBeginningAndEnd() {
        var chunks = split("START " + "alpha beta gamma delta ".repeat(100) + " END", 64, 8);
        assertTrue(chunks.size() > 1);
        assertTrue(chunks.get(0).startsWith("START"));
        assertTrue(chunks.get(chunks.size() - 1).endsWith("END"));
        assertTrue(chunks.stream().allMatch(c -> !c.isBlank() && c.length() <= 64));
    }

    @Test
    void fallbackPreservesTextWhenSplitterConfigurationIsInvalid() {
        var chunks = split("起始 " + "abcdefghij".repeat(30) + " 结束", 64, 64);
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.get(0).contains("起始"));
        assertTrue(chunks.get(chunks.size() - 1).contains("结束"));
    }
}
