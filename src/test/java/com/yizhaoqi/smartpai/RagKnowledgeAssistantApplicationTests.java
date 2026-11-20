package com.yizhaoqi.smartpai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;
import com.yizhaoqi.smartpai.config.EsIndexInitializer;

@SpringBootTest
@ActiveProfiles("test")
class RagKnowledgeAssistantApplicationTests {
    @MockBean
    private EsIndexInitializer esIndexInitializer;

    @Test
    void contextLoads() {
    }

}
