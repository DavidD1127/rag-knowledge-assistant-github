package com.yizhaoqi.smartpai.client;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.reactive.function.client.WebClient;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.function.Consumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.yizhaoqi.smartpai.config.AiProperties;

@Service
public class DeepSeekClient {

    private final WebClient webClient;
    private final String apiKey;
    private final String model;
    private final AiProperties aiProperties;

    // 备用模型（用于主模型超时/异常时降级切换）
    private final String fallbackApiUrl;
    private final String fallbackApiKey;
    private final String fallbackModel;
    private final WebClient fallbackWebClient;

    private static final Logger logger = LoggerFactory.getLogger(DeepSeekClient.class);

    public DeepSeekClient(@Value("${deepseek.api.url}") String apiUrl,
            @Value("${deepseek.api.key}") String apiKey,
            @Value("${deepseek.api.model}") String model,
            @Value("${deepseek.fallback.url:}") String fallbackApiUrl,
            @Value("${deepseek.fallback.key:}") String fallbackApiKey,
            @Value("${deepseek.fallback.model:}") String fallbackModel,
            AiProperties aiProperties) {
        WebClient.Builder builder = WebClient.builder().baseUrl(apiUrl);

        // 只有当 API key 不为空时才添加 Authorization header
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            builder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }

        this.webClient = builder.build();
        this.apiKey = apiKey;
        this.model = model;
        this.aiProperties = aiProperties;
        this.fallbackApiUrl = fallbackApiUrl;
        this.fallbackApiKey = fallbackApiKey;
        this.fallbackModel = fallbackModel;

        WebClient.Builder fb = WebClient.builder();
        if (fallbackApiUrl != null && !fallbackApiUrl.isBlank()) {
            fb.baseUrl(fallbackApiUrl);
            if (fallbackApiKey != null && !fallbackApiKey.isBlank()) {
                fb.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + fallbackApiKey);
            }
        }
        this.fallbackWebClient = fb.build();

        if (fallbackModel != null && !fallbackModel.isBlank()) {
            logger.info("已配置备用模型降级: {} (url={})", fallbackModel, fallbackApiUrl);
        }
    }

    /**
     * 流式响应 - 检索模式（带上下文）
     */
    public void streamResponse(String userMessage,
            String context,
            List<Map<String, String>> history,
            Consumer<String> onChunk,
            Consumer<Throwable> onError) {
        streamResponseInternal(userMessage, context, history, true, onChunk, onError);
    }

    /**
     * 流式响应 - 通用模式（无上下文，直接回答）
     */
    public void streamResponseDirect(String userMessage,
            List<Map<String, String>> history,
            Consumer<String> onChunk,
            Consumer<Throwable> onError) {
        streamResponseInternal(userMessage, null, history, false, onChunk, onError);
    }

    /**
     * 内部流式响应方法
     */
    private void streamResponseInternal(String userMessage,
            String context,
            List<Map<String, String>> history,
            boolean useRetrievalPrompt,
            Consumer<String> onChunk,
            Consumer<Throwable> onError) {

        // 记录是否已产出过内容；仅在"主模型尚未输出任何 token 即失败"时才降级到备用模型，
        // 避免流式中途切换导致前端收到不连续响应。
        java.util.concurrent.atomic.AtomicBoolean emitted = new java.util.concurrent.atomic.AtomicBoolean(false);

        Map<String, Object> request = buildRequestInternal(userMessage, context, history, useRetrievalPrompt, model);

        webClient.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToFlux(String.class)
                .subscribe(
                        chunk -> {
                            emitted.set(true);
                            processChunk(chunk, onChunk);
                        },
                        error -> {
                            if (emitted.get() || !hasFallbackModel()) {
                                onError.accept(error);
                                return;
                            }
                            logger.warn("主模型调用失败且尚未产出内容，降级到备用模型: {}", fallbackModel, error);
                            Map<String, Object> fbRequest = buildRequestInternal(
                                    userMessage, context, history, useRetrievalPrompt, fallbackModel);
                            fallbackWebClient.post()
                                    .uri("/chat/completions")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .bodyValue(fbRequest)
                                    .retrieve()
                                    .bodyToFlux(String.class)
                                    .subscribe(
                                            chunk -> processChunk(chunk, onChunk),
                                            onError);
                        });
    }

    private boolean hasFallbackModel() {
        return fallbackModel != null && !fallbackModel.isBlank() && fallbackApiUrl != null && !fallbackApiUrl.isBlank();
    }

    private Map<String, Object> buildRequestInternal(String userMessage,
            String context,
            List<Map<String, String>> history,
            boolean useRetrievalPrompt,
            String model) {
        logger.info("构建请求，用户消息：{}，上下文长度：{}，历史消息数：{}，检索模式：{}",
                userMessage,
                context != null ? context.length() : 0,
                history != null ? history.size() : 0,
                useRetrievalPrompt);

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", buildMessagesInternal(userMessage, context, history, useRetrievalPrompt));
        request.put("stream", true);
        // 生成参数
        AiProperties.Generation gen = aiProperties.getGeneration();
        if (gen.getTemperature() != null) {
            request.put("temperature", gen.getTemperature());
        }
        if (gen.getTopP() != null) {
            request.put("top_p", gen.getTopP());
        }
        if (gen.getMaxTokens() != null) {
            request.put("max_tokens", gen.getMaxTokens());
        }
        return request;
    }

    private List<Map<String, String>> buildMessagesInternal(String userMessage,
            String context,
            List<Map<String, String>> history,
            boolean useRetrievalPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();

        if (useRetrievalPrompt) {
            // 检索模式：使用带 REF 标记的 system prompt
            AiProperties.Prompt promptCfg = aiProperties.getPrompt();

            StringBuilder sysBuilder = new StringBuilder();
            String rules = promptCfg.getRules();
            if (rules != null) {
                sysBuilder.append(rules).append("\n\n");
            }

            String refStart = promptCfg.getRefStart() != null ? promptCfg.getRefStart() : "<<REF>>";
            String refEnd = promptCfg.getRefEnd() != null ? promptCfg.getRefEnd() : "<<END>>";
            sysBuilder.append(refStart).append("\n");

            if (context != null && !context.isEmpty()) {
                sysBuilder.append(context);
            } else {
                String noResult = promptCfg.getNoResultText() != null ? promptCfg.getNoResultText() : "（本轮无检索结果）";
                sysBuilder.append(noResult).append("\n");
            }

            sysBuilder.append(refEnd);

            messages.add(Map.of(
                    "role", "system",
                    "content", sysBuilder.toString()));
        } else {
            // 通用模式：使用简单的通用助手 system prompt
            messages.add(Map.of(
                    "role", "system",
                    "content", "你是一个 helpful 的 AI 助手，请直接回答用户问题。"));
        }

        // 追加历史消息（若有）
        if (history != null && !history.isEmpty()) {
            messages.addAll(history);
        }

        // 当前用户问题
        messages.add(Map.of(
                "role", "user",
                "content", userMessage));

        return messages;
    }

    private void processChunk(String chunk, Consumer<String> onChunk) {
        try {
            // 检查是否是结束标记
            if ("[DONE]".equals(chunk)) {
                logger.debug("对话结束");
                return;
            }

            // 直接解析 JSON
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(chunk);
            String content = node.path("choices")
                    .path(0)
                    .path("delta")
                    .path("content")
                    .asText("");

            if (!content.isEmpty()) {
                onChunk.accept(content);
            }
        } catch (Exception e) {
            logger.error("处理数据块时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 同步调用大模型，用于检索决策等简单任务
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param maxTokens    最大token数
     * @return 模型返回的文本内容
     */
    public String chatSync(String systemPrompt, String userPrompt, int maxTokens) {
        logger.debug("开始同步调用，系统提示长度: {}, 用户提示长度: {}",
                systemPrompt != null ? systemPrompt.length() : 0,
                userPrompt != null ? userPrompt.length() : 0);

        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        Map<String, Object> request = new java.util.HashMap<>();
        request.put("model", model);
        request.put("messages", messages);
        request.put("stream", false);
        request.put("temperature", 0.0);
        request.put("max_tokens", maxTokens);

        try {
            // 主模型：先调用一次；失败则降级到备用模型
            return postSyncOnce(webClient, model, request);
        } catch (Exception e) {
            logger.warn("主模型同步调用失败，尝试降级到备用模型: {}", fallbackModel, e);
            if (hasFallbackModel()) {
                try {
                    request.put("model", fallbackModel);
                    return postSyncOnce(fallbackWebClient, fallbackModel, request);
                } catch (Exception fb) {
                    throw new RuntimeException("主模型与备用模型均调用失败", fb);
                }
            }
            throw new RuntimeException("调用大模型失败", e);
        }
    }

    /**
     * 执行一次同步 POST（带超时），解析返回内容。
     */
    private String postSyncOnce(WebClient client, String modelName, Map<String, Object> request) {
        String response = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(String.class)
                .block(java.time.Duration.ofSeconds(60));
        return extractContent(response);
    }

    /**
     * 从响应中提取内容
     */
    private String extractContent(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(response);
            return node.path("choices")
                    .path(0)
                    .path("message")
                    .path("content")
                    .asText("")
                    .trim();
        } catch (Exception e) {
            logger.error("解析响应失败: {}", response, e);
            return "";
        }
    }
}