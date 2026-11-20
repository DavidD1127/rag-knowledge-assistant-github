package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.entity.SearchResult;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 暴露给大模型（AiServices + @Tool）的检索工具。
 * <p>
 * 通过 ThreadLocal 绑定当前请求的用户ID，使无状态工具在调用时能带上权限过滤；
 * 大模型判断需要检索时会调用本工具拿相关资料，再自主决策是否把它用作上下文。
 */
@Component
public class KnowledgeSearchTool {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    private final ThreadLocal<List<SearchResult>> retrievedResults = new ThreadLocal<>();

    private final HybridSearchService searchService;

    public KnowledgeSearchTool(HybridSearchService searchService) {
        this.searchService = searchService;
    }

    public void bindUser(String userId) {
        CURRENT_USER.set(userId);
        retrievedResults.remove();
    }

    public void clearUser() {
        CURRENT_USER.remove();
        retrievedResults.remove();
    }

    public List<SearchResult> getRetrievedResults() {
        return retrievedResults.get();
    }

    /**
     * 大模型检索工具入口。
     *
     * @param query 检索查询语句
     * @return 命中文档片段（含权限过滤），供回答引用
     */
    @Tool("检索企业知识库，返回与查询相关的文档片段，供回答参考。适合内部文档/产品/业务类问题。")
    public String searchKnowledgeBase(@P("用户检索查询语句") String query) {
        String userId = CURRENT_USER.get();
        if (userId == null) {
            return "";
        }
        try {
            List<SearchResult> results = searchService.searchWithPermission(query, userId, 5);
            // 保留完整片段供正式回答使用；null 表示尚未成功检索，空列表表示已查但无结果。
            List<SearchResult> previous = retrievedResults.get();
            java.util.Map<String, SearchResult> merged = new java.util.LinkedHashMap<>();
            if (previous != null) {
                previous.forEach(r -> merged.put(r.getFileMd5() + "_" + r.getChunkId(), r));
            }
            results.forEach(r -> merged.put(r.getFileMd5() + "_" + r.getChunkId(), r));
            retrievedResults.set(List.copyOf(merged.values()));
            StringBuilder sb = new StringBuilder();
            int idx = 0;
            for (SearchResult r : results) {
                idx++;
                String content = r.getTextContent();
                if (content == null) {
                    continue;
                }
                if (content.length() > 300) {
                    content = content.substring(0, 300);
                }
                sb.append("[").append(idx).append("] ").append(content).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }
}
