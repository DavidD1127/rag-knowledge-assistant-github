package com.yizhaoqi.smartpai.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多轮对话上下文管理：滚动摘要 + 近期原文（业界主流 ConversationSummaryBuffer 模式）。
 * <p>
 * 每个会话维护两部分：
 * <ul>
 * <li>summary：早期多轮对话的滚动摘要（一条 system 消息，增量合并，不从头重写）；</li>
 * <li>recent ：最近若干轮的原始 user/assistant 消息（逐字保留）。</li>
 * </ul>
 * 在一轮对话结束写入(addTurn)时判断：当近期原文条数超过触发阈值，就把最早的若干"完整轮次"
 * (user+assistant 成对)连同旧摘要一起喂给大模型合并成新摘要，recent 只保留最近 keepRecent 条。
 * 这样早期事实以摘要保留、近期细节逐字保留，同时上下文 token 有界。状态经 Redis 持久化，重启可恢复。
 */
@Service
public class ChatMemoryService {

    private static final Logger logger = LoggerFactory.getLogger(ChatMemoryService.class);

    /** 触发压缩的近期消息条数阈值（默认 20 条 ≈ 10 轮） */
    private final int summaryTrigger;
    /** 压缩后保留的近期原始消息条数（默认 14 条 ≈ 7 轮，偶数保证成对） */
    private final int keepRecent;

    private final RedisChatMemoryStore memoryStore;
    private final ChatModel chatModel;

    /** conversationId -> 会话记忆（摘要 + 近期原文） */
    private final Map<String, SessionMemory> sessions = new ConcurrentHashMap<>();

    public ChatMemoryService(RedisChatMemoryStore memoryStore,
            ChatModel chatModel,
            @Value("${memory.summary-trigger:20}") int summaryTrigger,
            @Value("${memory.keep-recent:14}") int keepRecent) {
        this.memoryStore = memoryStore;
        this.chatModel = chatModel;
        this.summaryTrigger = Math.max(summaryTrigger, 2);
        // keepRecent 取偶数，避免只保留半轮
        this.keepRecent = (keepRecent % 2 == 0) ? keepRecent : keepRecent - 1;
    }

    /** 单个会话的记忆状态 */
    private static class SessionMemory {
        String summary; // 滚动摘要（null 表示还没有）
        final List<ChatMessage> recent = new ArrayList<>();
    }

    private SessionMemory session(String conversationId) {
        return sessions.computeIfAbsent(conversationId, id -> {
            SessionMemory sm = new SessionMemory();
            try {
                List<ChatMessage> all = memoryStore.getMessages(id);
                if (all != null) {
                    for (ChatMessage m : all) {
                        if (m instanceof SystemMessage s) {
                            sm.summary = unwrapSummary(s.text());
                        } else {
                            sm.recent.add(m);
                        }
                    }
                }
            } catch (Exception e) {
                logger.warn("从 Redis 载入会话记忆失败，按空记忆处理: {}", e.getMessage());
            }
            return sm;
        });
    }

    /**
     * 返回过往多轮上下文（不含本轮用户问题），供送大模型：
     * 形如 [system:历史摘要] + [最近 N 条原文]。
     */
    public List<Map<String, String>> getMessages(String conversationId) {
        SessionMemory sm = session(conversationId);
        List<Map<String, String>> recent = new ArrayList<>();
        for (ChatMessage m : sm.recent) {
            if (m instanceof UserMessage u) {
                recent.add(entry("user", u.singleText()));
            } else if (m instanceof AiMessage a) {
                recent.add(entry("assistant", a.text()));
            }
        }
        // 近期原文要求以 user 开头（DeepSeek 约束），剔除偶发的开头 assistant
        while (!recent.isEmpty() && "assistant".equals(recent.get(0).get("role"))) {
            recent.remove(0);
        }

        List<Map<String, String>> context = new ArrayList<>();
        if (sm.summary != null && !sm.summary.isBlank()) {
            context.add(entry("system", "历史对话摘要：" + sm.summary));
        }
        context.addAll(recent);
        logger.debug("会话 {} 取出上下文：摘要{}字 + {} 条近期原文",
                conversationId, sm.summary == null ? 0 : sm.summary.length(), recent.size());
        return context;
    }

    /**
     * 记录一轮完整对话(user + assistant)。写入后判断是否触发滚动摘要压缩。
     */
    public void addTurn(String conversationId, String userMessage, String assistantMessage) {
        SessionMemory sm = session(conversationId);
        sm.recent.add(UserMessage.from(userMessage));
        if (assistantMessage != null && !assistantMessage.isEmpty()) {
            sm.recent.add(AiMessage.from(assistantMessage));
        }

        if (sm.recent.size() > summaryTrigger) {
            compact(sm);
        }
        persist(conversationId, sm);
    }

    /**
     * 滚动压缩：把最早滑出的若干"完整轮次"连同旧摘要增量合并成新摘要，recent 仅保留最近 keepRecent 条。
     * 压缩失败则不裁剪（不丢内容），下一轮再尝试。
     */
    private void compact(SessionMemory sm) {
        int moveOut = sm.recent.size() - keepRecent;
        // 只按成对轮次滑出，避免把 user/assistant 拆散
        if (moveOut % 2 != 0) {
            moveOut -= 1;
        }
        if (moveOut <= 0) {
            return;
        }

        List<ChatMessage> oldTurns = new ArrayList<>(sm.recent.subList(0, moveOut));
        try {
            String newSummary = mergeSummary(sm.summary, oldTurns);
            sm.summary = newSummary;
            sm.recent.subList(0, moveOut).clear();
            logger.info("对话记忆已滚动压缩：合并 {} 条早期消息，近期保留 {} 条", moveOut, sm.recent.size());
        } catch (Exception e) {
            // 摘要失败不裁剪，保留原文，下一轮重试，避免丢历史
            logger.warn("滚动摘要失败，本轮保留原文不压缩: {}", e.getMessage());
        }
    }

    /** 旧摘要 + 新滑出的早期消息 -> 增量合并后的新摘要 */
    private String mergeSummary(String oldSummary, List<ChatMessage> oldTurns) {
        StringBuilder conv = new StringBuilder();
        for (ChatMessage m : oldTurns) {
            if (m instanceof UserMessage u) {
                conv.append("用户：").append(u.singleText()).append("\n");
            } else if (m instanceof AiMessage a) {
                conv.append("助手：").append(a.text()).append("\n");
            }
        }
        String prompt = "请把以下多轮对话逐步浓缩成一段简洁的中文摘要，要求：\n"
                + "1) 保留关键事实、用户的偏好/约束、已得出的结论、尚未解决或待跟进的问题；\n"
                + "2) 去掉寒暄与重复内容，不要分点罗列，用连贯的一段话；\n"
                + "3) 若已有旧摘要，请在其基础上增量合并，而不是丢弃旧信息。\n\n"
                + "【已有摘要】\n" + (oldSummary == null ? "（无）" : oldSummary) + "\n\n"
                + "【新增的早期对话】\n" + conv;
        String result = chatModel.chat(prompt);
        return result == null ? oldSummary : result.trim();
    }

    /** 持久化：摘要放一条 system，其后跟近期原文 */
    private void persist(String conversationId, SessionMemory sm) {
        try {
            List<ChatMessage> all = new ArrayList<>();
            if (sm.summary != null && !sm.summary.isBlank()) {
                all.add(SystemMessage.from(SUMMARY_PREFIX + sm.summary));
            }
            all.addAll(sm.recent);
            memoryStore.updateMessages(conversationId, all);
        } catch (Exception e) {
            logger.warn("会话记忆持久化失败: {}", e.getMessage());
        }
    }

    /** 摘要在 system 消息中的包裹前缀，便于从 Redis 读回时识别 */
    private static final String SUMMARY_PREFIX = "历史对话摘要：";

    private String unwrapSummary(String text) {
        return text != null && text.startsWith(SUMMARY_PREFIX)
                ? text.substring(SUMMARY_PREFIX.length())
                : text;
    }

    private Map<String, String> entry(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}