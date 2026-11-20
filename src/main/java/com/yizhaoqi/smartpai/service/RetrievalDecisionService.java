package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.RetrievalDecisionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 检索决策服务
 * 使用大模型判断用户问题是否需要检索文档知识库
 */
@Service
public class RetrievalDecisionService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalDecisionService.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private RetrievalDecisionProperties properties;

    // 默认提示词（当配置文件未设置时使用）
    private static final String DEFAULT_SYSTEM_PROMPT = """
        你是一个检索决策助手。请判断用户问题是否需要检索企业文档知识库。

        判断标准：
        - 通用知识（学校、名人、历史、科学常识、编程语言等）→ 回答 NO_RETRIEVAL
        - 企业内部信息（产品文档、技术规范、业务流程、操作手册等）→ 回答 NEED_RETRIEVAL

        只回答 "NEED_RETRIEVAL" 或 "NO_RETRIEVAL"。
        """;

    private static final String DEFAULT_USER_PROMPT_TEMPLATE = """
        用户问题：%s

        这个问题需要检索企业文档知识库吗？只回答 NEED_RETRIEVAL 或 NO_RETRIEVAL。
        """;

    /**
     * 判断是否需要检索文档
     *
     * @param userQuery 用户查询
     * @return true 需要检索, false 不需要检索
     */
    public boolean needRetrieval(String userQuery) {
        long startTime = System.currentTimeMillis();
        logger.info("开始检索决策判断，查询: {}", userQuery);

        try {
            // 从配置获取提示词，未配置则使用默认值
            String systemPrompt = properties.getSystemPrompt() != null
                ? properties.getSystemPrompt()
                : DEFAULT_SYSTEM_PROMPT;

            String userPromptTemplate = properties.getUserPromptTemplate() != null
                ? properties.getUserPromptTemplate()
                : DEFAULT_USER_PROMPT_TEMPLATE;

            String userPrompt = String.format(userPromptTemplate, userQuery);

            // 从配置获取参数，未配置则使用默认值
            int maxTokens = properties.getMaxTokens() != null ? properties.getMaxTokens() : 10;

            String response = deepSeekClient.chatSync(systemPrompt, userPrompt, maxTokens);

            boolean needRetrieval = parseDecision(response);
            long cost = System.currentTimeMillis() - startTime;
            logger.info("检索决策完成，结果: {}, 耗时: {}ms",
                needRetrieval ? "需要检索" : "不需要检索", cost);

            return needRetrieval;

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - startTime;
            logger.error("检索决策失败，耗时: {}ms，默认需要检索", cost, e);
            // 决策失败时，默认需要检索（保守策略）
            return true;
        }
    }

    /**
     * 异步判断是否需要检索
     */
    public CompletableFuture<Boolean> needRetrievalAsync(String userQuery) {
        return CompletableFuture.supplyAsync(() -> needRetrieval(userQuery));
    }

    /**
     * 解析大模型的决策响应
     */
    private boolean parseDecision(String response) {
        if (response == null || response.isEmpty()) {
            logger.warn("决策响应为空，默认需要检索");
            return true;
        }

        String content = response.trim().toUpperCase();
        logger.debug("决策响应内容: {}", content);

        // 如果包含 NO_RETRIEVAL，则不需要检索
        if (content.contains("NO_RETRIEVAL")) {
            return false;
        }

        // 默认需要检索（保守策略）
        return true;
    }
}
