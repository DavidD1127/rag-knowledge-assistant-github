package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 基于 Redis 的 langchain4j 对话记忆落库实现。
 * <p>
 * 每个会话(memoryId)一条 JSON 数组，键：conversation:{conversationId}:lc4j-memory，带 7 天 TTL。
 * langchain4j 的 MessageWindowChatMemory 在 add 时回调该 store 完成持久化，重启后可恢复。
 */
@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemoryStore(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    private String key(Object memoryId) {
        return "conversation:" + memoryId + ":lc4j-memory";
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(key(memoryId));
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Map<String, String>> entries =
                    objectMapper.readValue(json, new TypeReference<List<Map<String, String>>>() {});
            List<ChatMessage> messages = new ArrayList<>();
            for (Map<String, String> e : entries) {
                String role = e.get("role");
                String content = e.get("content");
                if (content == null) {
                    continue;
                }
                switch (Objects.toString(role, "")) {
                    case "user" -> messages.add(UserMessage.from(content));
                    case "assistant" -> messages.add(AiMessage.from(content));
                    case "system" -> messages.add(SystemMessage.from(content));
                    default -> {}
                }
            }
            return messages;
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        List<Map<String, String>> entries = new ArrayList<>();
        for (ChatMessage m : messages) {
            if (m instanceof UserMessage u) {
                entries.add(entry("user", u.singleText()));
            } else if (m instanceof AiMessage a) {
                entries.add(entry("assistant", a.text()));
            } else if (m instanceof SystemMessage s) {
                entries.add(entry("system", s.text()));
            }
        }
        try {
            redisTemplate.opsForValue().set(key(memoryId),
                    objectMapper.writeValueAsString(entries), TTL);
        } catch (Exception ignored) {
            // 记忆持久化失败不应阻断对话主流程
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
    }

    private Map<String, String> entry(String role, String content) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }
}