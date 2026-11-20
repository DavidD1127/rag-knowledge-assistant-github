package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.client.DeepSeekClient;
import com.yizhaoqi.smartpai.config.QueryRewriteProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询改写服务
 * 将用户的口语化查询转换为标准形式，提升检索命中率
 */
@Service
public class QueryRewriteService {

    private static final Logger logger = LoggerFactory.getLogger(QueryRewriteService.class);

    @Autowired
    private DeepSeekClient deepSeekClient;

    @Autowired
    private QueryRewriteProperties properties;

    // 默认系统提示词
    private static final String DEFAULT_SYSTEM_PROMPT = """
        你是一个查询改写专家。请将用户的口语化查询改写为适合文档检索的标准查询。

        改写规则：
        1. 去除口语化词汇（那个啥、来着、请问一下等）
        2. 提取核心关键词
        3. 使用书面化、规范化的表达
        4. 保持原意不变
        5. 适当扩展同义词（如需要）

        只输出改写后的查询，不要解释。
        """;

    // 默认用户提示词模板
    private static final String DEFAULT_USER_PROMPT_TEMPLATE = """
        原始查询：%s

        请将其改写为标准检索查询：
        """;

    /**
     * 改写用户查询
     *
     * @param originalQuery 原始查询
     * @return 改写后的查询
     */
    public String rewrite(String originalQuery) {
        if (!properties.isEnabled()) {
            logger.debug("查询改写已禁用，返回原始查询");
            return originalQuery;
        }

        long startTime = System.currentTimeMillis();
        logger.info("开始查询改写，原始查询: {}", originalQuery);

        try {
            // 从配置获取提示词
            String systemPrompt = properties.getSystemPrompt() != null
                ? properties.getSystemPrompt()
                : DEFAULT_SYSTEM_PROMPT;

            String userPromptTemplate = properties.getUserPromptTemplate() != null
                ? properties.getUserPromptTemplate()
                : DEFAULT_USER_PROMPT_TEMPLATE;

            String userPrompt = String.format(userPromptTemplate, originalQuery);

            // 从配置获取参数
            int maxTokens = properties.getMaxTokens() != null ? properties.getMaxTokens() : 100;

            String rewrittenQuery = deepSeekClient.chatSync(systemPrompt, userPrompt, maxTokens);

            // 清理改写结果
            rewrittenQuery = cleanRewrittenQuery(rewrittenQuery);

            long cost = System.currentTimeMillis() - startTime;
            logger.info("查询改写完成，耗时: {}ms, 原始: '{}', 改写: '{}'", 
                cost, originalQuery, rewrittenQuery);

            // 如果改写结果为空，返回原始查询
            if (rewrittenQuery == null || rewrittenQuery.isEmpty()) {
                logger.warn("查询改写结果为空，使用原始查询");
                return originalQuery;
            }

            return rewrittenQuery;

        } catch (Exception e) {
            long cost = System.currentTimeMillis() - startTime;
            logger.error("查询改写失败，耗时: {}ms，使用原始查询", cost, e);
            return originalQuery;
        }
    }

    /**
     * 多路改写：生成 N 个不同的检索表达变体（含原始查询），用于并行检索提升查全率。
     *
     * @param originalQuery 原始查询
     * @param variants      期望的变体数量（不含原始查询）
     * @return 原始查询 + 改写变体（去重），最少 1 个（原始查询）
     */
    public List<String> rewriteVariants(String originalQuery, int variants) {
        if (originalQuery == null || originalQuery.isBlank() || !properties.isEnabled() || variants <= 0) {
            return List.of(originalQuery);
        }
        try {
            String systemPrompt = properties.getSystemPrompt() != null
                    ? properties.getSystemPrompt()
                    : DEFAULT_SYSTEM_PROMPT;
            int maxTokens = properties.getMaxTokens() != null ? properties.getMaxTokens() : 100;
            String userPrompt = String.format(
                    "你是查询改写专家。请将用户查询改写为 %d 个不同的检索表达变体，" +
                            "可从同义替换、更具体、更口语化/更书面化等角度改写，每行一个，只输出变体本身，不要解释。\n\n原始查询：%s",
                    variants, originalQuery);
            String raw = deepSeekClient.chatSync(systemPrompt, userPrompt, maxTokens * 3);

            List<String> result = new ArrayList<>();
            result.add(originalQuery);
            if (raw != null) {
                for (String line : raw.split("\\n")) {
                    String t = cleanRewrittenQuery(line);
                    if (!t.isEmpty() && !result.contains(t)) {
                        result.add(t);
                    }
                }
            }
            if (result.size() > variants + 1) {
                result = new ArrayList<>(result.subList(0, variants + 1));
            }
            logger.info("多路改写完成: '{}' -> 变体数: {}", originalQuery, result.size());
            return result;
        } catch (Exception e) {
            logger.warn("多路改写失败，退回单一路径: {}", e.getMessage());
            return List.of(originalQuery);
        }
    }

    /**
     * 清理改写结果
     */
    private String cleanRewrittenQuery(String query) {
        if (query == null) {
            return "";
        }

        return query.trim()
            .replaceAll("^(改写后的查询[：:]?\\s*)", "")  // 去除前缀
            .replaceAll("^(标准查询[：:]?\\s*)", "")
            .replaceAll("^(检索查询[：:]?\\s*)", "")
            .replaceAll("^[" + '"' + "'" + "](.*)[" + '"' + "'" + "]$", "$1")  // 去除引号
            .trim();
    }
}
