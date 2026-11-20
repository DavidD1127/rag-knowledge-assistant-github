package com.yizhaoqi.smartpai.service;

import com.yizhaoqi.smartpai.config.OcrProperties;
import com.yizhaoqi.smartpai.model.DocumentVector;
import com.yizhaoqi.smartpai.repository.DocumentVectorRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 文档内嵌图片 OCR：用 PDFBox 抽取图片，再调 VLM(OpenAI 兼容/Qwen-VL) 把图片转为可检索文本块入库。
 * <p>
 * 业界主流做法：解析层抽图 → 多模态模型生成"OCR + 语义描述" → 作为文本 chunk 入库向量化，
 * 解决图文档检索不到的问题。默认关闭，需配置 file.parsing.ocr.*（含 VLM 接口与 key）。
 */
@Service
public class ImageOcrService {

    private static final Logger logger = LoggerFactory.getLogger(ImageOcrService.class);

    private final OcrProperties props;
    private final DocumentVectorRepository vectorRepository;
    private final WebClient webClient;

    public ImageOcrService(OcrProperties props,
            DocumentVectorRepository vectorRepository,
            @Value("${file.parsing.ocr.url:}") String ocrUrl) {
        this.props = props;
        this.vectorRepository = vectorRepository;
        this.webClient = ocrUrl == null || ocrUrl.isBlank()
                ? WebClient.builder().build()
                : WebClient.builder().baseUrl(ocrUrl).build();
    }

    /**
     * 处理 PDF 内嵌图片：抽图 -> VLM 转文本 -> 以 DocumentVector chunk 入库。
     * 非 PDF / 未启用 / 文件过大时不处理。
     */
    public void processPdfImages(byte[] fileBytes, String fileName, String fileMd5,
            String userId, String orgTag, boolean isPublic) {
        if (!props.isEnabled()) {
            return;
        }
        if (fileName == null || !fileName.toLowerCase().endsWith(".pdf")) {
            return;
        }
        if (fileBytes == null || fileBytes.length > props.getMaxFileBytes()) {
            logger.info("文件过大或无字节，跳过多模态 OCR: {}", fileName);
            return;
        }

        List<String> imageTexts = new ArrayList<>();
        try (PDDocument pdf = Loader.loadPDF(fileBytes)) {
            int count = 0;
            int pageIndex = 0;
            for (PDPage page : pdf.getPages()) {
                pageIndex++;
                PDResources resources = page.getResources();
                if (resources == null) {
                    continue;
                }
                for (COSName name : resources.getXObjectNames()) {
                    if (count >= props.getMaxImages()) {
                        break;
                    }
                    PDXObject xobject = resources.getXObject(name);
                    if (xobject instanceof PDImageXObject imageXObject) {
                        String text = ocrImage(imageXObject.getImage());
                        if (text == null || text.isBlank()) {
                            continue;
                        }
                        imageTexts.add("[PDF图片 第" + pageIndex + "页] " + text);
                        count++;
                    }
                }
            }
        } catch (Exception e) {
            logger.warn("PDF 抽图失败，跳过多模态 OCR: {}", e.getMessage());
            return;
        }

        if (imageTexts.isEmpty()) {
            return;
        }
        saveImageChunks(imageTexts, fileMd5, userId, orgTag, isPublic);
        logger.info("多模态 OCR 完成，共 {} 张图片转为文本入库: {}", imageTexts.size(), fileMd5);
    }

    /**
     * 调用 VLM 将 BufferedImage 识别为可检索文本（OCR + 语义描述）。
     */
    private String ocrImage(BufferedImage image) {
        if (image == null) {
            return null;
        }
        byte[] png = toPngBytes(image);
        if (png == null) {
            return null;
        }
        String b64 = Base64.getEncoder().encodeToString(png);
        Map<String, Object> body = Map.of(
                "model", props.getModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "text",
                                        "text", "请识别这张图片：既识别图中的文字，也用简短的句子描述图片表达的内容，输出一段可直接用于文本检索的内容。"),
                                Map.of("type", "image_url",
                                        "image_url", Map.of("url", "data:image/png;base64," + b64))))));
        try {
            String resp = webClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + props.getKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(java.time.Duration.ofSeconds(60));
            return extractContent(resp);
        } catch (Exception e) {
            logger.warn("VLM 图片识别失败: {}", e.getMessage());
            return null;
        }
    }

    private String extractContent(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(response);
            String content = node.path("choices").path(0).path("message").path("content").asText("");
            return content.isBlank() ? null : content.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] toPngBytes(BufferedImage image) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 把 OCR 文本作为 DocumentVector chunk 追加入库，chunkId 从当前文件最大 chunkId 之后继续。
     */
    private void saveImageChunks(List<String> imageTexts, String fileMd5,
            String userId, String orgTag, boolean isPublic) {
        int nextId = vectorRepository.findByFileMd5(fileMd5).stream()
                .mapToInt(DocumentVector::getChunkId)
                .max()
                .orElse(0) + 1;
        for (String text : imageTexts) {
            DocumentVector v = new DocumentVector();
            v.setFileMd5(fileMd5);
            v.setChunkId(nextId++);
            v.setTextContent(text);
            v.setUserId(userId);
            v.setOrgTag(orgTag);
            v.setPublic(isPublic);
            vectorRepository.save(v);
        }
    }
}