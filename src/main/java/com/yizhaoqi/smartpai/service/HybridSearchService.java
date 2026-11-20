package com.yizhaoqi.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.model.User;
import com.yizhaoqi.smartpai.exception.CustomException;
import com.yizhaoqi.smartpai.repository.UserRepository;
import com.yizhaoqi.smartpai.repository.FileUploadRepository;
import com.yizhaoqi.smartpai.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import co.elastic.clients.elasticsearch._types.query_dsl.Operator;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 混合搜索服务，结合文本匹配和向量相似度搜索
 * 支持权限过滤，确保用户只能搜索其有权限访问的文档
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private QueryRewriteService queryRewriteService;

    // 线程池配置，用于并行执行多路召回
    private final ExecutorService executorService = new ThreadPoolExecutor(
            32, // 核心线程数
            64, // 最大线程数
            60L, TimeUnit.SECONDS, // 线程空闲时间
            new LinkedBlockingQueue<>(100), // 任务队列
            new ThreadPoolExecutor.CallerRunsPolicy() // 拒绝策略
    );

    /**
     * 企业级多路召回搜索，支持权限过滤
     * 并行执行多个召回路径，然后合并和重排序结果
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> multiPathSearchWithPermission(String query, String userId, int topK) {
        long startTime = System.currentTimeMillis();
        logger.info("开始企业级多路召回搜索，查询: {}, 用户ID: {}, topK: {}", query, userId, topK);

        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 多路改写：将 query 扩展为多个检索变体，为每条变体并行召回，提升查全率
            List<String> queryVariants = queryRewriteService.rewriteVariants(query, 3);
            logger.info("多路改写变体: {}", queryVariants);

            // 为每个变体预生成查询向量（并行），再对每条变体执行 KNN + BM25 召回
            Map<String, CompletableFuture<List<Float>>> vectorFutures = new HashMap<>();
            for (String variant : queryVariants) {
                vectorFutures.put(variant, CompletableFuture.supplyAsync(
                        () -> embedToVectorList(variant), executorService)
                        .exceptionally(error -> {
                            logger.warn("向量生成失败，保留关键词检索: {}", variant);
                            return null;
                        }));
            }

            // 并行执行多路召回
            List<CompletableFuture<List<SearchResult>>> futures = new ArrayList<>();
            for (String variant : queryVariants) {
                final String v = variant;
                // 1. 向量召回（KNN搜索）
                futures.add(vectorFutures.get(variant).thenApplyAsync(vv -> {
                    if (vv == null || vv.isEmpty()) {
                        return Collections.<SearchResult>emptyList();
                    }
                    try {
                        return knnSearchWithPermission(v, vv, userDbId, userEffectiveTags, topK * 5);
                    } catch (Exception e) {
                        logger.error("向量召回失败", e);
                        return Collections.emptyList();
                    }
                }, executorService));

                // 2. 文本召回（BM25搜索）
                futures.add(CompletableFuture.supplyAsync(() -> {
                    try {
                        return textSearchWithPermission(v, userDbId, userEffectiveTags, topK * 5);
                    } catch (Exception e) {
                        logger.error("文本召回失败", e);
                        return Collections.emptyList();
                    }
                }, executorService));
            }

            // 注：已移除精确匹配召回（Phrase Match），因为 BM25 已能覆盖关键词匹配场景
            // 如需增加其他召回路径（如最近访问），可在此添加

            // 等待所有召回完成，保持每个召回路径各自的有序序列（用于 RRF 按位次融合）
            CompletableFuture<Void> allOf = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));

            List<List<SearchResult>> pathResults = allOf
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).toList()).join();

            logger.debug("多路召回完成，召回路径数: {}", pathResults.size());

            // 重排：RRF 多路融合（Reciprocal Rank Fusion）
            List<SearchResult> fusedResults = rrfFuse(pathResults);
            logger.debug("RRF融合后结果数: {}", fusedResults.size());

            // 截取topK结果
            List<SearchResult> finalResults = fusedResults.stream()
                    .limit(topK)
                    .collect(Collectors.toList());

            logger.debug("返回最终结果数: {}", finalResults.size());
            attachFileNames(finalResults);

            long endTime = System.currentTimeMillis();
            logger.info("企业级多路召回搜索完成，耗时: {}ms, 返回结果数: {}", endTime - startTime, finalResults.size());
            return finalResults;
        } catch (Exception e) {
            long endTime = System.currentTimeMillis();
            logger.error("多路召回搜索失败，耗时: {}ms", endTime - startTime, e);
            // 发生异常时尝试使用纯文本搜索作为后备方案
            try {
                logger.info("尝试使用纯文本搜索作为后备方案");
                return textSearchWithPermission(query, getUserDbId(userId), getUserEffectiveOrgTags(userId), topK);
            } catch (Exception fallbackError) {
                logger.error("后备搜索也失败", fallbackError);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 普通搜索（仅搜索公开文档）
     * 用于未登录用户或公开搜索场景
     *
     * @param query 查询字符串
     * @param topK  返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> search(String query, int topK) {
        logger.debug("开始公开搜索，查询: {}", query);

        // Query 改写
        String rewrittenQuery = queryRewriteService.rewrite(query);
        if (!rewrittenQuery.equals(query)) {
            logger.info("查询已改写: '{}' -> '{}'", query, rewrittenQuery);
        }

        try {
            // 生成查询向量
            final List<Float> queryVector = embedToVectorList(rewrittenQuery);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return searchPublicOnly(rewrittenQuery, topK);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base_qwen_v1");
                int recallK = topK * 30;
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                        .filter(f -> f.term(t -> t.field("public").value(true))));
                s.query(q -> q.bool(b -> b
                        .must(m -> m.match(mt -> mt.field("textContent").query(rewrittenQuery)))
                        .filter(f -> f.term(t -> t.field("public").value(true)))));
                s.rescore(r -> r
                        .windowSize(recallK)
                        .query(rq -> rq
                                .queryWeight(0.5d)
                                .rescoreQueryWeight(0.5d)
                                .query(rqq -> rqq.match(m -> m
                                        .field("textContent")
                                        .query(rewrittenQuery)
                                        .operator(Operator.And)))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        SearchResult sr = new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score() != null ? hit.score() : 0.0,
                                hit.score() != null ? hit.score() : 0.0,
                                SearchResult.SOURCE_KNN,
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null);
                        sr.setParentContent(hit.source().getParentContent());
                        return sr;
                    })
                    .collect(Collectors.toList());

            attachFileNames(results);
            logger.info("公开搜索完成，返回 {} 条结果", results.size());
            return results;

        } catch (Exception e) {
            logger.error("公开搜索失败", e);
            throw new CustomException("搜索失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 仅搜索公开文档（文本匹配）
     */
    private List<SearchResult> searchPublicOnly(String query, int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base_qwen_v1");
                s.query(q -> q.bool(b -> b
                        .must(m -> m.match(mt -> mt.field("textContent").query(query)))
                        .filter(f -> f.term(t -> t.field("public").value(true)))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        SearchResult sr = new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score() != null ? hit.score() : 0.0,
                                hit.score() != null ? hit.score() : 0.0,
                                SearchResult.SOURCE_TEXT,
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null);
                        sr.setParentContent(hit.source().getParentContent());
                        return sr;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("公开文本搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 使用文本匹配和向量相似度进行混合搜索，支持权限过滤
     * 该方法确保用户只能搜索其有权限访问的文档（自己的文档、公开文档、所属组织的文档）
     *
     * @param query  查询字符串
     * @param userId 用户ID
     * @param topK   返回结果数量
     * @return 搜索结果列表
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        logger.debug("开始带权限搜索，原始查询: {}, 用户ID: {}", query, userId);

        // Query 改写：将口语化查询转换为标准查询
        String rewrittenQuery = queryRewriteService.rewrite(query);
        if (!rewrittenQuery.equals(query)) {
            logger.info("查询已改写: '{}' -> '{}'", query, rewrittenQuery);
        }

        try {
            // 获取用户有效的组织标签（包含层级关系）
            List<String> userEffectiveTags = getUserEffectiveOrgTags(userId);
            logger.debug("用户 {} 的有效组织标签: {}", userId, userEffectiveTags);

            // 获取用户的数据库ID用于权限过滤
            String userDbId = getUserDbId(userId);
            logger.debug("用户 {} 的数据库ID: {}", userId, userDbId);

            // 生成查询向量（使用改写后的查询）
            final List<Float> queryVector = embedToVectorList(rewrittenQuery);

            // 如果向量生成失败，仅使用文本匹配
            if (queryVector == null) {
                logger.warn("向量生成失败，仅使用文本匹配进行搜索");
                return textOnlySearchWithPermission(rewrittenQuery, userDbId, userEffectiveTags, topK);
            }

            logger.debug("向量生成成功，开始执行混合搜索 KNN");

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base_qwen_v1");
                // KNN 召回
                int recallK = topK * 30; // KNN 召回窗口
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(recallK)
                        .numCandidates(recallK)
                        // KNN召回阶段就过滤权限，确保召回的都是用户有权限的文档
                        .filter(knFilter -> knFilter.bool(bf -> bf
                                // 条件1: 用户可访问自己的文档
                                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                // 条件2: 公开文档
                                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                // 条件3: 组织标签
                                .should(s3 -> {
                                    if (userEffectiveTags.isEmpty()) {
                                        return s3.matchNone(mn -> mn);
                                    } else if (userEffectiveTags.size() == 1) {
                                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                    } else {
                                        return s3.bool(inner -> {
                                            userEffectiveTags.forEach(tag -> inner
                                                    .should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        });
                                    }
                                }))));
                // 必须命中关键词 + 权限过滤（使用改写后的查询）
                s.query(q -> q.bool(b -> b
                        .must(mst -> mst.match(m -> m.field("textContent").query(rewrittenQuery)))
                        .filter(f -> f.bool(bf -> bf
                                // 条件1: 用户可访问自己的文档
                                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                // 条件2: 公开文档
                                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                // 条件3: 组织标签
                                .should(s3 -> {
                                    if (userEffectiveTags.isEmpty()) {
                                        return s3.matchNone(mn -> mn);
                                    } else if (userEffectiveTags.size() == 1) {
                                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                    } else {
                                        return s3.bool(inner -> {
                                            userEffectiveTags.forEach(tag -> inner
                                                    .should(sh2 -> sh2.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        });
                                    }
                                })))));

                // 第二阶段 混合 rescore，主流企业做法：KNN和BM25权重均衡（使用改写后的查询）
                s.rescore(r -> r
                        .windowSize(recallK)
                        .query(rq -> rq
                                .queryWeight(0.5d) // KNN权重 0.5
                                .rescoreQueryWeight(0.5d) // BM25权重 0.5
                                .query(rqq -> rqq.match(m -> m
                                        .field("textContent")
                                        .query(rewrittenQuery)
                                        .operator(Operator.And)))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            logger.debug("Elasticsearch查询执行完成，命中数量: {}, 最大分数: {}",
                    response.hits().total().value(), response.hits().maxScore());

            // 收集原始结果并计算最大最小分数用于归一化
            List<SearchResult> rawResults = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        double rawScore = hit.score() != null ? hit.score() : 0.0;
                        logger.debug("搜索结果 - 文件: {}, 块: {}, 分数: {}, 内容: {}",
                                hit.source().getFileMd5(), hit.source().getChunkId(), rawScore,
                                hit.source().getTextContent().substring(0,
                                        Math.min(50, hit.source().getTextContent().length())));
                        SearchResult sr = new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                rawScore,
                                rawScore,
                                SearchResult.SOURCE_KNN,
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null);
                        sr.setParentContent(hit.source().getParentContent());
                        return sr;
                    })
                    .collect(Collectors.toList());

            // 归一化分数
            List<SearchResult> normalizedResults = normalizeScores(rawResults);

            // 附加文件名信息
            attachFileNames(normalizedResults);

            logger.info("带权限搜索完成，返回 {} 条结果", normalizedResults.size());
            return normalizedResults;

        } catch (Exception e) {
            logger.error("带权限搜索失败", e);
            throw new CustomException("搜索失败: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 仅使用文本匹配进行搜索（当向量生成失败时的后备方案）
     */
    private List<SearchResult> textOnlySearchWithPermission(String query, String userDbId,
            List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("执行纯文本搜索，查询: {}", query);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base_qwen_v1");
                s.query(q -> q.bool(b -> b
                        .must(m -> m.match(mt -> mt
                                .field("textContent")
                                .query(query)
                                .operator(Operator.And)))
                        .filter(f -> f.bool(bf -> bf
                                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                .should(s3 -> {
                                    if (userEffectiveTags.isEmpty()) {
                                        return s3.matchNone(mn -> mn);
                                    } else if (userEffectiveTags.size() == 1) {
                                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                    } else {
                                        return s3.bool(inner -> {
                                            userEffectiveTags.forEach(tag -> inner
                                                    .should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        });
                                    }
                                })))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            List<SearchResult> results = response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        SearchResult sr = new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score() != null ? hit.score() : 0.0,
                                hit.score() != null ? hit.score() : 0.0,
                                SearchResult.SOURCE_TEXT,
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null);
                        sr.setParentContent(hit.source().getParentContent());
                        return sr;
                    })
                    .collect(Collectors.toList());

            attachFileNames(results);
            logger.info("纯文本搜索完成，返回 {} 条结果", results.size());
            return results;

        } catch (Exception e) {
            logger.error("纯文本搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * KNN向量搜索，支持权限过滤
     */
    private List<SearchResult> knnSearchWithPermission(String query, List<Float> queryVector, String userDbId,
            List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("执行KNN向量搜索，topK: {}", topK);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base_qwen_v1");
                s.knn(kn -> kn
                        .field("vector")
                        .queryVector(queryVector)
                        .k(topK)
                        .numCandidates(topK * 2)
                        .filter(f -> f.bool(bf -> bf
                                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                .should(s3 -> {
                                    if (userEffectiveTags.isEmpty()) {
                                        return s3.matchNone(mn -> mn);
                                    } else if (userEffectiveTags.size() == 1) {
                                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                    } else {
                                        return s3.bool(inner -> {
                                            userEffectiveTags.forEach(tag -> inner
                                                    .should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        });
                                    }
                                }))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        SearchResult sr = new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score() != null ? hit.score() : 0.0,
                                hit.score() != null ? hit.score() : 0.0,
                                SearchResult.SOURCE_KNN,
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null);
                        sr.setParentContent(hit.source().getParentContent());
                        return sr;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("KNN搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 文本搜索（BM25），支持权限过滤
     */
    private List<SearchResult> textSearchWithPermission(String query, String userDbId,
            List<String> userEffectiveTags, int topK) {
        try {
            logger.debug("执行BM25文本搜索，topK: {}", topK);

            SearchResponse<EsDocument> response = esClient.search(s -> {
                s.index("knowledge_base_qwen_v1");
                s.query(q -> q.bool(b -> b
                        .must(m -> m.match(mt -> mt
                                .field("textContent")
                                .query(query)
                                .operator(Operator.And)))
                        .filter(f -> f.bool(bf -> bf
                                .should(s1 -> s1.term(t -> t.field("userId").value(userDbId)))
                                .should(s2 -> s2.term(t -> t.field("public").value(true)))
                                .should(s3 -> {
                                    if (userEffectiveTags.isEmpty()) {
                                        return s3.matchNone(mn -> mn);
                                    } else if (userEffectiveTags.size() == 1) {
                                        return s3.term(t -> t.field("orgTag").value(userEffectiveTags.get(0)));
                                    } else {
                                        return s3.bool(inner -> {
                                            userEffectiveTags.forEach(tag -> inner
                                                    .should(sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        });
                                    }
                                })))));
                s.size(topK);
                return s;
            }, EsDocument.class);

            return response.hits().hits().stream()
                    .map(hit -> {
                        assert hit.source() != null;
                        SearchResult sr = new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                hit.score() != null ? hit.score() : 0.0,
                                hit.score() != null ? hit.score() : 0.0,
                                SearchResult.SOURCE_TEXT,
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null);
                        sr.setParentContent(hit.source().getParentContent());
                        return sr;
                    })
                    .collect(Collectors.toList());

        } catch (Exception e) {
            logger.error("文本搜索失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 获取用户的数据库ID
     */
    private String getUserDbId(String userId) {
        User user = userRepository.findByUsername(userId)
                .orElseThrow(() -> new CustomException("用户不存在: " + userId, HttpStatus.NOT_FOUND));
        return user.getId().toString();
    }

    /**
     * 获取用户有效的组织标签（包含层级关系）
     */
    private List<String> getUserEffectiveOrgTags(String userId) {
        return orgTagCacheService.getUserEffectiveOrgTags(userId);
    }

    /**
     * 将查询文本转换为向量
     */
    private List<Float> embedToVectorList(String query) {
        try {
            List<float[]> vectors = embeddingClient.embed(Collections.singletonList(query));
            if (vectors == null || vectors.isEmpty() || vectors.get(0) == null) {
                logger.warn("向量生成结果为空");
                return null;
            }
            float[] vector = vectors.get(0);
            List<Float> result = new ArrayList<>(vector.length);
            for (float v : vector) {
                result.add(v);
            }
            return result;
        } catch (Exception e) {
            logger.error("向量生成失败", e);
            return null;
        }
    }

    /**
     * 归一化分数到 [0, 1] 范围
     */
    private List<SearchResult> normalizeScores(List<SearchResult> results) {
        if (results.isEmpty()) {
            return results;
        }

        double maxScore = results.stream()
                .mapToDouble(SearchResult::getScore)
                .max()
                .orElse(1.0);
        double minScore = results.stream()
                .mapToDouble(SearchResult::getScore)
                .min()
                .orElse(0.0);

        if (maxScore - minScore < 0.0001) {
            return results;
        }

        for (SearchResult result : results) {
            double normalizedScore = (result.getScore() - minScore) / (maxScore - minScore);
            result.setScore(normalizedScore);
        }

        return results;
    }

    /**
     * 为搜索结果附加文件名
     */
    private void attachFileNames(List<SearchResult> results) {
        if (results.isEmpty()) {
            return;
        }

        List<String> fileMd5s = results.stream()
                .map(SearchResult::getFileMd5)
                .distinct()
                .collect(Collectors.toList());

        try {
            List<FileUpload> files = fileUploadRepository.findByFileMd5In(fileMd5s);
            Map<String, String> fileNameMap = files.stream()
                    .collect(Collectors.toMap(
                            FileUpload::getFileMd5,
                            FileUpload::getFileName,
                            (existing, replacement) -> existing));

            for (SearchResult result : results) {
                String fileName = fileNameMap.get(result.getFileMd5());
                if (fileName != null) {
                    result.setFileName(fileName);
                }
            }
        } catch (Exception e) {
            logger.warn("附加文件名失败", e);
        }
    }

    /**
     * RRF（Reciprocal Rank Fusion）多路融合。
     * <p>
     * 同一文档在每条召回路径中按位次投票：score = Σ 1/(k + rank)，rank 为 1 起始的位次。
     * 相比对分数做 min-max 归一化再加权，RRF 只依赖各路径内部排序，跨路径分数尺度不一致时更稳健。
     *
     * @param pathResults 每个召回路径按相关性降序排列的结果列表
     * @return 按 RRF 得分降序、去重后的结果列表
     */
    private List<SearchResult> rrfFuse(List<List<SearchResult>> pathResults) {
        if (pathResults == null || pathResults.isEmpty()) {
            return Collections.emptyList();
        }

        int k = 60; // RRF 常用常数
        Map<String, SearchResult> resultByKey = new LinkedHashMap<>();
        Map<String, Double> rrfScore = new HashMap<>();

        for (List<SearchResult> path : pathResults) {
            if (path == null) {
                continue;
            }
            for (int i = 0; i < path.size(); i++) {
                SearchResult sr = path.get(i);
                String key = sr.getFileMd5() + "_" + sr.getChunkId();
                resultByKey.putIfAbsent(key, sr);
                rrfScore.merge(key, 1.0 / (k + i + 1), Double::sum);
            }
        }

        List<SearchResult> finalResults = new ArrayList<>(resultByKey.values());
        finalResults.forEach(r -> r.setScore(rrfScore.getOrDefault(r.getFileMd5() + "_" + r.getChunkId(), 0.0)));
        finalResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));
        return finalResults;
    }
}
