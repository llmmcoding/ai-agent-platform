package com.aiagent.service.chunk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 语义切片器
 * 基于句子边界进行切片，保持语义完整性
 */
@Slf4j
@Component
public class SemanticChunker implements TextChunker {

    // 句子结束符
    private static final String[] SENTENCE_ENDINGS = {". ", "! ", "? ", "。", "！", "？", ".\n", "!\n", "?\n"};

    // 段落分隔符
    private static final String[] PARAGRAPH_SEPARATORS = {"\n\n", "\n", "\r\n"};

    @Override
    public List<Chunk> chunk(String text, ChunkConfig config) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        List<Chunk> chunks = new ArrayList<>();
        String documentId = config.getDocumentIdPrefix() + "-" + UUID.randomUUID().toString().substring(0, 8);

        int maxChunkSize = config.getMaxChunkSize();
        int minChunkSize = config.getMinChunkSize();

        // 第一步：按段落分割
        List<String> paragraphs = splitByParagraphs(text);

        // 第二步：按句子分割每个段落
        List<String> sentences = new ArrayList<>();
        for (String paragraph : paragraphs) {
            sentences.addAll(splitBySentences(paragraph));
        }

        // 第三步：合并句子形成 chunk
        StringBuilder currentChunk = new StringBuilder();
        int chunkIndex = 0;
        int startPosition = 0;

        for (String sentence : sentences) {
            // 检查是否需要开始新的 chunk
            if (currentChunk.length() + sentence.length() > maxChunkSize
                    && currentChunk.length() >= minChunkSize) {
                // 保存当前 chunk
                chunks.add(createChunk(currentChunk.toString().trim(), config, documentId, chunkIndex++, startPosition));

                // 开始新 chunk，保留最后一个句子作为开头 (overlap 效果)
                String overlap = getLastSentence(currentChunk.toString());
                currentChunk = new StringBuilder(overlap);
                startPosition = startPosition + currentChunk.length() - overlap.length();
            }

            if (currentChunk.length() > 0) {
                currentChunk.append(" ");
            }
            currentChunk.append(sentence);
        }

        // 添加最后一个 chunk
        if (currentChunk.length() > 0) {
            String finalChunk = currentChunk.toString().trim();
            if (!finalChunk.isBlank()) {
                chunks.add(createChunk(finalChunk, config, documentId, chunkIndex, startPosition));
            }
        }

        log.debug("Chunked text into {} chunks using Semantic strategy", chunks.size());
        return chunks;
    }

    /**
     * 按段落分割
     */
    private List<String> splitByParagraphs(String text) {
        List<String> paragraphs = new ArrayList<>();
        String remaining = text;

        while (!remaining.isEmpty()) {
            int earliestSeparator = -1;
            String earliestSep = "";

            for (String sep : PARAGRAPH_SEPARATORS) {
                int pos = remaining.indexOf(sep);
                if (pos > 0 && (earliestSeparator < 0 || pos < earliestSeparator)) {
                    earliestSeparator = pos;
                    earliestSep = sep;
                }
            }

            if (earliestSeparator < 0) {
                paragraphs.add(remaining);
                break;
            }

            paragraphs.add(remaining.substring(0, earliestSeparator + earliestSep.length()));
            remaining = remaining.substring(earliestSeparator + earliestSep.length());
        }

        return paragraphs;
    }

    /**
     * 按句子分割
     */
    private List<String> splitBySentences(String text) {
        List<String> sentences = new ArrayList<>();
        String remaining = text.trim();

        while (!remaining.isEmpty()) {
            int earliestEnd = -1;
            String earliestSep = "";

            for (String sep : SENTENCE_ENDINGS) {
                int pos = remaining.indexOf(sep);
                if (pos > 0 && (earliestEnd < 0 || pos < earliestEnd)) {
                    earliestEnd = pos;
                    earliestSep = sep;
                }
            }

            if (earliestEnd < 0) {
                // 没有找到句子结束符
                if (!remaining.isBlank()) {
                    sentences.add(remaining);
                }
                break;
            }

            String sentence = remaining.substring(0, earliestEnd + earliestSep.length()).trim();
            if (!sentence.isBlank()) {
                sentences.add(sentence);
            }
            remaining = remaining.substring(earliestEnd + earliestSep.length()).trim();
        }

        return sentences;
    }

    /**
     * 获取最后一个完整句子
     */
    private String getLastSentence(String text) {
        for (String sep : SENTENCE_ENDINGS) {
            int pos = text.lastIndexOf(sep);
            if (pos > 0) {
                return text.substring(pos + sep.length()).trim();
            }
        }
        return "";
    }

    /**
     * 创建 Chunk
     */
    private Chunk createChunk(String content, ChunkConfig config, String documentId, int chunkIndex, int startPosition) {
        return Chunk.builder()
                .id(documentId + "-chunk-" + chunkIndex)
                .content(content)
                .documentId(documentId)
                .chunkIndex(chunkIndex)
                .startPosition(startPosition)
                .endPosition(startPosition + content.length())
                .build();
    }

    @Override
    public String getName() {
        return "semantic";
    }
}
