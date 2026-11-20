package com.yizhaoqi.smartpai.entity;

import lombok.Data;

@Data
public class SearchResult {
    public static final String SOURCE_KNN = "knn";
    public static final String SOURCE_TEXT = "text";
    public static final String SOURCE_PHRASE = "phrase";

    private String fileMd5;    // 文件指纹
    private Integer chunkId;   // 文本分块序号
    private String textContent; // 文本内容（子块）
    private String parentContent; // 所属父块全文（命中子块后回填上下文）
    private Double score;      // 搜索得分（最终融合分）
    private Double originalScore; // 原始召回分数
    private String source;     // 召回来源
    private String fileName;   // 原始文件名
    private String userId;     // 上传用户ID
    private String orgTag;     // 组织标签
    private Boolean isPublic;  // 是否公开

    /**
     * 供大模型作上下文的正文：优先父块全文（父子分块回填），否则回退到子块。
     */
    public String getAnswerContent() {
        if (parentContent != null && !parentContent.isBlank()) {
            return parentContent;
        }
        return textContent;
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score) {
        this(fileMd5, chunkId, textContent, score, score, null, null, null, false, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String fileName) {
        this(fileMd5, chunkId, textContent, score, score, null, null, null, false, fileName);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic) {
        this(fileMd5, chunkId, textContent, score, score, null, userId, orgTag, isPublic, null);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, String userId, String orgTag, boolean isPublic, String fileName) {
        this(fileMd5, chunkId, textContent, score, score, null, userId, orgTag, isPublic, fileName);
    }

    public SearchResult(String fileMd5, Integer chunkId, String textContent, Double score, Double originalScore, String source, String userId, String orgTag, boolean isPublic, String fileName) {
        this.fileMd5 = fileMd5;
        this.chunkId = chunkId;
        this.textContent = textContent;
        this.score = score;
        this.originalScore = originalScore;
        this.source = source;
        this.userId = userId;
        this.orgTag = orgTag;
        this.isPublic = isPublic;
        this.fileName = fileName;
    }
}
