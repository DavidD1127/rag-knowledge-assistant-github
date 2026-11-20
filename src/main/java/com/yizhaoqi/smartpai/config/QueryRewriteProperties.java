package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 查询改写相关配置
 */
@Component
@ConfigurationProperties(prefix = "query-rewrite")
@Data
public class QueryRewriteProperties {

    /** 是否启用查询改写 */
    private boolean enabled = true;

    /** 系统提示词 */
    private String systemPrompt;

    /** 用户提示词模板 */
    private String userPromptTemplate;

    /** 最大输出 token 数 */
    private Integer maxTokens = 100;

    /** 采样温度 */
    private Double temperature = 0.3;
}
