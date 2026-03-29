package com.aiagent.service.chunk;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 递归字符切片器
 * 按优先级尝试使用不同的分隔符进行切片，保持段落完整性
 */
@Slf4j
@Component
public class RecursiveCharacterChunker implements TextChunker {

    private static final Pattern TITLE_PATTERN = Pattern.compile("^#{1,6}\\s+(.+)$");

    @Override
    public List<Chunk> chunk(String text, ChunkConfig config) {
        if (text == null || text.isBlank()) {
            return new ArrayList<>();
        }

        List<Chunk> chunks = new ArrayList<>();
        String documentId = config.getDocumentIdPrefix() + "-" + UUID.randomUUID().toString().substring(0, 8);

        int chunkSize = config.getChunkSize();
        int overlap = config.getOverlap();
        int minChunkSize = config.getMinChunkSize();
        List<String> separators = config.getSeparators();

        // 预处理：分割成行，保留标题信息
        String[] lines = text.split("\n");
        List<String> processedLines = new ArrayList<>();
        String currentSection = "";
        String currentTitle = "";

        for (String line : lines) {
            // 检查是否是标题行
            if (TITLE_PATTERN.matcher(line).matches()) {
                // 保存当前 section
                if (!currentSection.isEmpty()) {
                    processedLines.add(currentSection.trim());
                }
                currentTitle = line;
                currentSection = "";
            } else {
                currentSection += (currentSection.isEmpty() ? "" : "\n") + line;
            }
        }
        if (!currentSection.isEmpty()) {
            processedLines.add(currentSection.trim());
        }

        // 对每个 section 进行切片
        int chunkIndex = 0;
        for (String section : processedLines) {
            if (section.length() <= chunkSize) {
                // Section 较小，直接添加
                chunks.add(createChunk(section, config, documentId, chunkIndex++));
            } else {
                // Section 较大，需要递归切片
                List<String> subChunks = splitBySeparators(section, separators, chunkSize, minChunkSize);

                String previousChunk = "";
                for (int i = 0; i < subChunks.size(); i++) {
                    String subChunk = subChunks.get(i);

                    // 添加重叠
                    if (i > 0 && !previousChunk.isEmpty() && overlap > 0) {
                        // 从 previousChunk 尾部取 overlap 字符
                        int overlapStart = Math.max(0, previousChunk.length() - overlap);
                        String overlapText = previousChunk.substring(overlapStart);
                        subChunk = overlapText + subChunk;
                    }

                    chunks.add(createChunk(subChunk.trim(), config, documentId, chunkIndex++));
                    previousChunk = subChunk;
                }
            }
        }

        // 更新 chunkIndex
        for (int i = 0; i < chunks.size(); i++) {
            chunks.get(i).setChunkIndex(i);
        }

        log.debug("Chunked text into {} chunks using RecursiveCharacter strategy", chunks.size());
        return chunks;
    }

    /**
     * 使用分隔符递归切片
     */
    private List<String> splitBySeparators(String text, List<String> separators, int chunkSize, int minChunkSize) {
        List<String> chunks = new ArrayList<>();

        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        // 尝试使用第一个分隔符
        String separator = separators.isEmpty() ? "" : separators.get(0);
        List<String> remainingSeparators = separators.size() > 1
                ? separators.subList(1, separators.size())
                : List.of("");

        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());

            // 如果不是最后一块，尝试找到分隔符
            if (end < text.length() && !separator.isEmpty()) {
                int lastSeparator = text.lastIndexOf(separator, end);
                if (lastSeparator > start + minChunkSize) {
                    end = lastSeparator + separator.length();
                }
            }

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 移动起始位置 (考虑重叠)
            start = end;
        }

        // 如果没有找到合适的分割，处理剩余文本
        if (chunks.isEmpty()) {
            chunks.add(text.substring(0, Math.min(text.length(), chunkSize)));
        }

        return chunks;
    }

    /**
     * 创建 Chunk
     */
    private Chunk createChunk(String content, ChunkConfig config, String documentId, int chunkIndex) {
        return Chunk.builder()
                .id(documentId + "-chunk-" + chunkIndex)
                .content(content)
                .documentId(documentId)
                .chunkIndex(chunkIndex)
                .startPosition(0)
                .endPosition(content.length())
                .build();
    }

    @Override
    public String getName() {
        return "recursive_character";
    }
}
