package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索决策相关配置
 */
@Component
@ConfigurationProperties(prefix = "retrieval-decision")
@Data
public class RetrievalDecisionProperties {

    /** 系统提示词 */
    private String systemPrompt;

    /** 用户提示词模板 */
    private String userPromptTemplate;

    /** 最大输出 token 数 */
    private Integer maxTokens = 10;

    /** 采样温度 */
    private Double temperature = 0.0;
}
