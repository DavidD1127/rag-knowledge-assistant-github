package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import com.hankcs.hanlp.seg.common.Term;
import com.hankcs.hanlp.tokenizer.StandardTokenizer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;

@Service
public class ParseService {

    private static final Logger logger = LoggerFactory.getLogger(ParseService.class);

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    @Value("${file.parsing.chunk-size}")
    private int chunkSize;

    @Value("${file.parsing.chunk-overlap:50}")
    private int chunkOverlap;

    @Value("${file.parsing.parent-token-size:2000}")
    private int parentTokenSize;

    @Value("${file.parsing.parent-token-overlap:300}")
    private int parentTokenOverlap;

    @Value("${file.parsing.parent-chunk-size:1048576}")
    private int parentChunkSize;

    @Value("${file.parsing.buffer-size:8192}")
    private int bufferSize;

    @Value("${file.parsing.max-memory-threshold:0.8}")
    private double maxMemoryThreshold;

    public ParseService() {
        // 无需初始化，StandardTokenizer是静态方法
    }

    /**
     * 以流式方式解析文件，将内容分块并保存到数据库，以避免OOM。
     * 采用"父文档-子切片"策略。
     *
     * @param fileMd5    文件的MD5哈希值，用于唯一标识文件
     * @param fileStream 文件输入流，用于读取文件内容
     * @param userId     上传用户ID
     * @param orgTag     组织标签
     * @param isPublic   是否公开
     * @throws IOException   如果文件读取过程中发生错误
     * @throws TikaException 如果文件解析过程中发生错误
     */
    public void parseAndSave(String fileMd5, InputStream fileStream,
            String userId, String orgTag, boolean isPublic) throws IOException, TikaException {
        logger.info("开始流式解析文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}",
                fileMd5, userId, orgTag, isPublic);

        checkMemoryThreshold();

        try (BufferedInputStream bufferedStream = new BufferedInputStream(fileStream, bufferSize)) {
            // 创建一个流式处理器，它会在内部处理父块的切分和子块的保存
            StreamingContentHandler handler = new StreamingContentHandler(fileMd5, userId, orgTag, isPublic);
            Metadata metadata = new Metadata();
            ParseContext context = new ParseContext();
            AutoDetectParser parser = new AutoDetectParser();

            // Tika的parse方法会驱动整个流式处理过程
            // 当handler的characters方法接收到足够数据时，会触发分块、切片和保存
            parser.parse(bufferedStream, handler, metadata, context);

            logger.info("文件流式解析和入库完成，fileMd5: {}", fileMd5);

        } catch (SAXException e) {
            logger.error("文档解析失败，fileMd5: {}", fileMd5, e);
            throw new RuntimeException("文档解析失败", e);
        }
    }

    /**
     * 兼容旧版本的解析方法
     */
    public void parseAndSave(String fileMd5, InputStream fileStream) throws IOException, TikaException {
        // 使用默认值调用新方法
        parseAndSave(fileMd5, fileStream, "unknown", "DEFAULT", false);
    }

    private void checkMemoryThreshold() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        long totalMemory = runtime.totalMemory();
        long freeMemory = runtime.freeMemory();
        long usedMemory = totalMemory - freeMemory;

        double memoryUsage = (double) usedMemory / maxMemory;

        if (memoryUsage > maxMemoryThreshold) {
            logger.warn("内存使用率过高: {:.2f}%, 触发垃圾回收", memoryUsage * 100);
            System.gc();

            // 重新检查
            usedMemory = runtime.totalMemory() - runtime.freeMemory();
            memoryUsage = (double) usedMemory / maxMemory;

            if (memoryUsage > maxMemoryThreshold) {
                throw new RuntimeException("内存不足，无法处理大文件。当前使用率: " +
                        String.format("%.2f%%", memoryUsage * 100));
            }
        }
    }

    /**
     * 内部流式内容处理器，实现了父子文档切分策略的核心逻辑。
     * Tika解析器会调用characters方法，当累积的文本达到"父块"大小时，
     * 就触发processParentChunk方法，进行"子切片"的生成和入库。
     */
    private class StreamingContentHandler extends BodyContentHandler {
        private final StringBuilder buffer = new StringBuilder();
        private final String fileMd5;
        private final String userId;
        private final String orgTag;
        private final boolean isPublic;
        private int savedChunkCount = 0;

        public StreamingContentHandler(String fileMd5, String userId, String orgTag, boolean isPublic) {
            super(-1); // 禁用Tika的内部写入限制，我们自己管理缓冲区
            this.fileMd5 = fileMd5;
            this.userId = userId;
            this.orgTag = orgTag;
            this.isPublic = isPublic;
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            buffer.append(ch, start, length);
            // 攒到目标大小时，若停在一个段落边界则按边界切开入库；过久无边界则按兜底上限强制 flush
            maybeFlushAtBoundary();
        }

        @Override
        public void endDocument() {
            // 处理文档末尾剩余的最后一部分内容
            if (buffer.length() > 0) {
                processText(buffer.toString());
                buffer.setLength(0);
            }
        }

        /**
         * 攒齐目标大小时，在最后一个段落边界处切开并入库，边界之后的文本作为 carry-over 留给下个窗口。
         * 这样流式缓冲只产出"语义完整的候选段落单元"，把(接近)段落中间的截断问题从根本上消除。
         */
        private void maybeFlushAtBoundary() {
            int cur = buffer.length();
            if (cur < parentChunkSize) {
                return; // 没攒够，继续攒
            }
            int boundary = lastParagraphBreak(buffer);
            if (boundary >= parentChunkSize) {
                // 目标大小内已出现段落边界 → 切到该边界，前面内容注入器处理，剩余的保留
                processText(buffer.substring(0, boundary));
                buffer.delete(0, boundary);
            } else if (cur >= parentChunkSize + overflowCap()) {
                // 一直没段落边界(超长单段)且已超兜底上限 → 在能找到的最后边界处强制 flush，找不到只能整段
                int cut = boundary > 0 ? boundary : cur;
                processText(buffer.substring(0, cut));
                buffer.delete(0, cut);
            }
        }

        /**
         * 找 StringBuilder 中最后一个段落边界(\n\n)或换行符的位置(含分隔符)。
         * 
         * @return 边界后一位的下标；无任何换行时返回 -1
         */
        private int lastParagraphBreak(StringBuilder sb) {
            String s = sb.toString();
            int idx = s.lastIndexOf("\n\n");
            if (idx >= 0) {
                return idx + 2;
            }
            idx = s.lastIndexOf("\n");
            return idx >= 0 ? idx + 1 : -1;
        }

        /** 兜底上限：允许多攒一点等段落收尾，同时避免超长单段无限占用内存 */
        private int overflowCap() {
            return Math.max(8192, bufferSize * 4);
        }

        /**
         * 核心注入器：把一段文本切成父子两级(Small-to-Big)并入库。
         */
        private void processText(String text) {
            logger.debug("处理文本块，大小: {} bytes", text.length());

            // 1. 切成中等"父块"(~parentTokenSize token，Small-to-Big 的 context 单元)
            List<String> parents = ParseService.this.splitIntoParents(text);
            if (parents.isEmpty()) {
                parents = List.of(text);
            }

            // 2. 每个父块再切成子块(精确检索单元)，并把父块全文作为 context 挂到各子块上
            int next = this.savedChunkCount;
            for (String parent : parents) {
                List<String> children = ParseService.this.splitChildren(parent);
                next = ParseService.this.saveChildChunks(fileMd5, children, userId, orgTag, isPublic, next, parent);
            }
            this.savedChunkCount = next;
        }
    }

    /**
     * 将子切片列表保存到数据库。
     *
     * @param fileMd5         文件的 MD5 哈希值
     * @param chunks          子切片文本列表
     * @param userId          上传用户ID
     * @param orgTag          组织标签
     * @param isPublic        是否公开
     * @param startingChunkId 当前批次的起始分片ID
     * @return 保存后总的分片数量
     */
    private int saveChildChunks(String fileMd5, List<String> chunks,
            String userId, String orgTag, boolean isPublic, int startingChunkId, String parentContent) {
        int currentChunkId = startingChunkId;
        for (String chunk : chunks) {
            currentChunkId++;
            var vector = new DocumentVector();
            vector.setFileMd5(fileMd5);
            vector.setChunkId(currentChunkId);
            vector.setTextContent(chunk);
            vector.setParentContent(parentContent);
            vector.setUserId(userId);
            vector.setOrgTag(orgTag);
            vector.setPublic(isPublic);
            documentVectorRepository.save(vector);
        }
        logger.info("成功保存 {} 个子切片到数据库", chunks.size());
        return currentChunkId;
    }

    /**
     * 父层分块（Small-to-Big 的 context 单元）：把 1MB 流式块切成约 parentTokenSize token 的中等父块。
     * 父块用于"命中子块后回填给 LLM 的上下文"，而子块用于精确检索。
     */
    private List<String> splitIntoParents(String text) {
        try {
            Document doc = Document.from(text);
            List<TextSegment> segments = DocumentSplitters.recursive(parentTokenSize, parentTokenOverlap).split(doc);
            List<String> parents = new ArrayList<>();
            for (TextSegment segment : segments) {
                String trimmed = segment.text().trim();
                if (!trimmed.isEmpty()) {
                    parents.add(trimmed);
                }
            }
            return parents;
        } catch (Exception e) {
            logger.warn("父层分块失败，回退到整块作为父块: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * 子块切分：优先使用 langchain4j 的递归分块器（DocumentSplitters.recursive），
     * 失败或为空时回退到自研的语义边界分块，保证父块-子切片流程不受影响。
     */
    private List<String> splitChildren(String parentChunkText) {
        try {
            Document doc = Document.from(parentChunkText);
            DocumentSplitter splitter = DocumentSplitters.recursive(chunkSize, chunkOverlap);
            List<TextSegment> segments = splitter.split(doc);
            List<String> chunks = new ArrayList<>();
            for (TextSegment segment : segments) {
                String text = segment.text().trim();
                if (!text.isEmpty()) {
                    chunks.add(text);
                }
            }
            if (!chunks.isEmpty()) {
                logger.debug("langchain4j 分块完成 -> {} 个子块", chunks.size());
                return chunks;
            }
        } catch (Exception e) {
            logger.warn("langchain4j 分块失败，回退到自研分块: {}", e.getMessage());
        }
        return splitTextIntoChunksWithSemantics(parentChunkText, chunkSize);
    }

    /**
     * 智能文本分割，保持语义完整性，支持重叠窗口
     * 企业常见做法：相邻块之间有 10%-20% 的重叠，保证边界语义不丢失
     */
    private List<String> splitTextIntoChunksWithSemantics(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();

        // 计算有效块大小（减去重叠部分）
        int effectiveChunkSize = chunkSize - chunkOverlap;
        if (effectiveChunkSize <= 0) {
            effectiveChunkSize = chunkSize; // 如果重叠太大，忽略重叠
            logger.warn("chunk-overlap ({}) >= chunk-size ({}), 忽略重叠设置", chunkOverlap, chunkSize);
        }

        // 按段落分割
        String[] paragraphs = text.split("\n\n+");

        StringBuilder currentChunk = new StringBuilder();
        String lastOverlap = ""; // 上一个块的重叠文本

        for (String paragraph : paragraphs) {
            // 如果单个段落超过chunk大小，需要进一步分割
            if (paragraph.length() > effectiveChunkSize) {
                // 先保存当前chunk（带上一个块的重叠）
                if (currentChunk.length() > 0) {
                    String chunkText = lastOverlap + currentChunk.toString().trim();
                    chunks.add(chunkText);
                    // 保存当前块的后 overlap 字符作为下一个块的重叠
                    lastOverlap = getOverlapText(chunkText, chunkOverlap);
                    currentChunk = new StringBuilder();
                }

                // 按句子分割长段落，带上重叠
                List<String> sentenceChunks = splitLongParagraphWithOverlap(paragraph, effectiveChunkSize,
                        chunkOverlap);
                for (String sentenceChunk : sentenceChunks) {
                    String chunkWithOverlap = lastOverlap + sentenceChunk;
                    chunks.add(chunkWithOverlap);
                    // 更新重叠文本
                    lastOverlap = getOverlapText(chunkWithOverlap, chunkOverlap);
                }
            }
            // 如果添加这个段落会超过有效chunk大小
            else if (currentChunk.length() + paragraph.length() > effectiveChunkSize) {
                // 保存当前chunk（带上一个块的重叠）
                if (currentChunk.length() > 0) {
                    String chunkText = lastOverlap + currentChunk.toString().trim();
                    chunks.add(chunkText);
                    // 保存当前块的后 overlap 字符作为下一个块的重叠
                    lastOverlap = getOverlapText(chunkText, chunkOverlap);
                }
                // 开始新chunk
                currentChunk = new StringBuilder(paragraph);
            }
            // 可以添加到当前chunk
            else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n\n");
                }
                currentChunk.append(paragraph);
            }
        }

        // 添加最后一个chunk（带上一个块的重叠）
        if (currentChunk.length() > 0) {
            String chunkText = lastOverlap + currentChunk.toString().trim();
            chunks.add(chunkText);
        }

        return chunks;
    }

    /**
     * 获取文本的后 overlap 个字符作为重叠部分
     */
    private String getOverlapText(String text, int overlap) {
        if (text == null || text.length() <= overlap) {
            return text;
        }
        return text.substring(text.length() - overlap);
    }

    /**
     * 分割长段落，按句子边界，支持重叠窗口
     */
    private List<String> splitLongParagraphWithOverlap(String paragraph, int effectiveChunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        // 按句子分割
        String[] sentences = paragraph.split("(?<=[。！？；])|(?<=[.!?;])\\s+");

        StringBuilder currentChunk = new StringBuilder();
        String lastOverlap = "";

        for (String sentence : sentences) {
            if (currentChunk.length() + sentence.length() > effectiveChunkSize) {
                if (currentChunk.length() > 0) {
                    String chunkText = lastOverlap + currentChunk.toString().trim();
                    chunks.add(chunkText);
                    lastOverlap = getOverlapText(chunkText, overlap);
                    currentChunk = new StringBuilder();
                }

                // 如果单个句子太长，按词分割（也带上重叠）
                if (sentence.length() > effectiveChunkSize) {
                    List<String> wordChunks = splitLongSentenceWithOverlap(sentence, effectiveChunkSize, overlap);
                    for (String wordChunk : wordChunks) {
                        String chunkWithOverlap = lastOverlap + wordChunk;
                        chunks.add(chunkWithOverlap);
                        lastOverlap = getOverlapText(chunkWithOverlap, overlap);
                    }
                } else {
                    currentChunk.append(sentence);
                }
            } else {
                currentChunk.append(sentence);
            }
        }

        if (currentChunk.length() > 0) {
            String chunkText = lastOverlap + currentChunk.toString().trim();
            chunks.add(chunkText);
        }

        return chunks;
    }

    /**
     * 使用HanLP智能分割超长句子，中文按语义切割，支持重叠窗口
     */
    private List<String> splitLongSentenceWithOverlap(String sentence, int effectiveChunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();

        try {
            // 使用HanLP StandardTokenizer进行分词
            List<Term> termList = StandardTokenizer.segment(sentence);

            StringBuilder currentChunk = new StringBuilder();
            String lastOverlap = "";

            for (Term term : termList) {
                String word = term.word;

                // 如果添加这个词会超过有效chunk大小限制，且当前chunk不为空
                if (currentChunk.length() + word.length() > effectiveChunkSize && !currentChunk.isEmpty()) {
                    String chunkText = lastOverlap + currentChunk.toString();
                    chunks.add(chunkText);
                    lastOverlap = getOverlapText(chunkText, overlap);
                    currentChunk = new StringBuilder();
                }

                currentChunk.append(word);
            }

            if (!currentChunk.isEmpty()) {
                String chunkText = lastOverlap + currentChunk.toString();
                chunks.add(chunkText);
            }

            logger.debug("HanLP智能分词成功，原文长度: {}, 分词数: {}, 分块数: {}",
                    sentence.length(), termList.size(), chunks.size());

        } catch (Exception e) {
            logger.warn("HanLP分词异常: {}, 使用字符分割作为备用方案", e.getMessage());
            chunks = splitByCharactersWithOverlap(sentence, effectiveChunkSize, overlap);
        }

        return chunks;
    }

    /**
     * 备用方案：按字符分割，支持重叠窗口
     */
    private List<String> splitByCharactersWithOverlap(String sentence, int effectiveChunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();
        String lastOverlap = "";

        for (int i = 0; i < sentence.length(); i++) {
            char c = sentence.charAt(i);

            if (currentChunk.length() + 1 > effectiveChunkSize && !currentChunk.isEmpty()) {
                String chunkText = lastOverlap + currentChunk.toString();
                chunks.add(chunkText);
                lastOverlap = getOverlapText(chunkText, overlap);
                currentChunk = new StringBuilder();
            }

            currentChunk.append(c);
        }

        if (!currentChunk.isEmpty()) {
            String chunkText = lastOverlap + currentChunk.toString();
            chunks.add(chunkText);
        }

        return chunks;
    }
}
