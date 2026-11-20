package com.yizhaoqi.smartpai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j 模型装配。
 *
 * DeepSeek 提供 OpenAI 兼容接口，因此这里用 langchain4j 的 OpenAiChatModel
 * 指向 DeepSeek，供"对话记忆管理 / Agentic 工具调用检索"等 langchain4j 能力复用。
 * 主问答的流式链路仍走自研的 DeepSeekClient（WebSocket 流式契约不变）。
 */
@Configuration
public class LlmBeans {

    private static final Logger logger = LoggerFactory.getLogger(LlmBeans.class);

    @Bean
    public ChatModel chatModel(
            @Value("${deepseek.api.url}") String baseUrl,
            @Value("${deepseek.api.key}") String apiKey,
            @Value("${deepseek.api.model}") String model) {
        logger.info("初始化 langchain4j ChatModel -> baseUrl: {}, model: {}", baseUrl, model);
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(model)
                .build();
    }
}