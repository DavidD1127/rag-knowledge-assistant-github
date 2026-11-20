package com.yizhaoqi.smartpai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文档图片 OCR 配置（VLM -> 文本入库）。
 * 默认关闭，需配置 Qwen-VL 之类视觉模型的 OpenAI 兼容接口。
 */
@Component
@ConfigurationProperties(prefix = "file.parsing.ocr")
@Data
public class OcrProperties {

    /** 是否启用文档内嵌图片 OCR */
    private boolean enabled = false;

    /** 单个文档最多处理的图片数 */
    private int maxImages = 10;

    /** VLM OpenAI 兼容接口 baseUrl */
    private String url;

    /** VLM API key */
    private String key;

    /** VLM 模型名（如 qwen-vl-plus） */
    private String model = "qwen-vl-plus";

    /** 单文件体积上限(字节)，超过不抽取图片，避免大文件整读耗尽内存 */
    private long maxFileBytes = 50 * 1024 * 1024;
}