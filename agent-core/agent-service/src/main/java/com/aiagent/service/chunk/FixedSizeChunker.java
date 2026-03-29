package com.aiagent.service.chunk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 固定大小切片器
 * 按固定字符数切片，可配置 overlap
 */
@Slf4j
@Component
public class FixedSizeChunker implements TextChunker {

    @Override
    public List<Chunk> chunk(String text, ChunkConfig config) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        List<Chunk> chunks = new ArrayList<>();
        String documentId = config.getDocumentIdPrefix() + "-" + UUID.randomUUID().toString().substring(0, 8);

        int chunkSize = config.getChunkSize();
        int overlap = config.getOverlap();

        int start = 0;
        int chunkIndex = 0;

        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            String chunkContent;

            if (end < text.length() && start > 0) {
                // 不是最后一块，需要找到单词边界
                chunkContent = findWordBoundary(text, start, end);
            } else {
                chunkContent = text.substring(start, end);
            }

            if (!chunkContent.isBlank()) {
                chunks.add(Chunk.builder()
                        .id(documentId + "-chunk-" + chunkIndex)
                        .content(chunkContent)
                        .documentId(documentId)
                        .chunkIndex(chunkIndex)
                        .startPosition(start)
                        .endPosition(start + chunkContent.length())
                        .build());
                chunkIndex++;
            }

            // 移动起始位置，减去重叠部分
            start = start + chunkContent.length() - overlap;
            if (start <= 0 || start >= text.length()) {
                break;
            }
        }

        log.debug("Chunked text into {} chunks using FixedSize strategy", chunks.size());
        return chunks;
    }

    /**
     * 找到单词边界
     */
    private String findWordBoundary(String text, int start, int end) {
        // 优先在空格处分割
        for (int i = end - 1; i >= Math.max(start + 1, end - 50); i--) {
            if (Character.isWhitespace(text.charAt(i))) {
                return text.substring(start, i).trim();
            }
        }

        // 没有找到空格，直接返回
        return text.substring(start, end).trim();
    }

    @Override
    public String getName() {
        return "fixed_size";
    }
}
