package com.yizhaoqi.smartpai.service;

import dev.langchain4j.model.chat.ChatModel;
import com.yizhaoqi.smartpai.entity.SearchResult;
import java.util.List;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agentic 检索决策服务。
 * <p>
 * 用 langchain4j AiServices + @Tool 让大模型自主决定是否检索：
 * 模型先自行判断问题是否依赖企业知识，需要时调用 {@link KnowledgeSearchTool#searchKnowledgeBase}，
 * 再返回是否需要使用检索上下文。相比"让模型事先返回 NEED/NO"的二值门禁更智能、更可扩展。
 */
@Service
public class AgenticRetrievalService {

    private static final Logger logger = LoggerFactory.getLogger(AgenticRetrievalService.class);

    private final KnowledgeSearchTool tool;
    private final RetrievalAgent agent;

    public AgenticRetrievalService(KnowledgeSearchTool tool, ChatModel chatModel) {
        this.tool = tool;
        this.agent = AiServices.builder(RetrievalAgent.class)
                .chatModel(chatModel)
                .tools(tool)
                .build();
    }

    public record RetrievalResult(boolean needed, List<SearchResult> results) {}

    /**
     * 返回检索决策及本次工具调用的完整结果，供回答阶段复用。
     *
     * @param query  用户问题
     * @param userId 当前用户ID（用于工具内的权限过滤）
     * @return results 为 null 表示未成功执行检索，空列表表示已检索但无命中
     */
    public RetrievalResult retrieve(String query, String userId) {
        try {
            tool.bindUser(userId);
            boolean decision = agent.needRetrieval(query);
            logger.info("Agentic 检索决策完成: {} -> {}", query, decision);
            return new RetrievalResult(decision, tool.getRetrievedResults());
        } catch (Exception e) {
            logger.warn("Agentic 检索决策失败，交由调用方降级处理");
            throw e;
        } finally {
            tool.clearUser();
        }
    }

    /**
     * 供 AiServices 生成的 Agent 接口：方法签名 + @Tool 由大模型驱动。
     */
    public interface RetrievalAgent {
        @SystemMessage("你是企业知识库检索决策助手。判断用户问题是否需要检索企业文档；"
                + "若问题可能依赖内部文档/产品/业务知识，先调用 searchKnowledgeBase 工具确认能否检索到相关资料，"
                + "再回答该问题是否需要检索上下文。只能回答 true 或 false。")
        boolean needRetrieval(@UserMessage String query);
    }
}
