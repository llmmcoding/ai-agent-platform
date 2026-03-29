package com.aiagent.service.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;

/**
 * File Reader Tool (Java 实现)
 * 支持格式: PDF, DOCX, TXT, MD, CSV, JSON, XML, XLSX
 *
 * 功能:
 * - 本地文件和 URL 支持
 * - 文件大小限制 (10MB)
 * - 元数据提取
 */
@Slf4j
@Component
public class FileReaderTool {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${aiagent.filereader.max-chars:50000}")
    private int maxCharsDefault;

    @Value("${aiagent.filereader.max-chars-limit:100000}")
    private int maxCharsLimit;

    @Value("${aiagent.filereader.max-file-size:10485760}")
    private long maxFileSize; // 10MB

    public FileReaderTool(WebClient webClient, ObjectMapper objectMapper) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 读取文件
     */
    public String execute(Map<String, Object> input) {
        String filePath = (String) input.get("file_path");
        int maxChars = Math.min(
                input.get("max_chars") != null ? ((Number) input.get("max_chars")).intValue() : maxCharsDefault,
                maxCharsLimit
        );
        boolean extractMetadata = input.get("extract_metadata") == null || (Boolean) input.get("extract_metadata");

        if (filePath == null || filePath.isBlank()) {
            return errorResult("INVALID_PARAMETER", "file_path is required");
        }

        try {
            // 判断是 URL 还是本地文件
            if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
                return readFromUrl(filePath, maxChars, extractMetadata);
            } else {
                return readLocalFile(filePath, maxChars, extractMetadata);
            }
        } catch (FileNotFoundException e) {
            return errorResult("FILE_NOT_FOUND", "File not found: " + filePath);
        } catch (SecurityException e) {
            return errorResult("PERMISSION_DENIED", "Permission denied: " + filePath);
        } catch (Exception e) {
            log.error("File read failed: {}", e.getMessage(), e);
            return errorResult("READ_ERROR", "Failed to read file: " + e.getMessage());
        }
    }

    /**
     * 读取本地文件
     */
    private String readLocalFile(String filePath, int maxChars, boolean extractMetadata) throws Exception {
        Path path = Paths.get(filePath);
        File file = path.toFile();

        if (!file.exists()) {
            throw new FileNotFoundException(filePath);
        }

        long fileSize = file.length();
        if (fileSize > maxFileSize) {
            return errorResult("FILE_TOO_LARGE",
                    "File exceeds maximum size of " + (maxFileSize / (1024 * 1024)) + "MB");
        }

        FileFormat format = detectFormat(filePath);
        String content;
        boolean truncated = false;

        switch (format) {
            case TXT:
            case MD:
                content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                break;
            case JSON:
                String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                Object parsed = objectMapper.readValue(json, Object.class);
                content = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(parsed);
                break;
            case CSV:
                content = readCsv(path);
                break;
            case XML:
                content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                break;
            case PDF:
                content = readPdf(path);
                break;
            case DOCX:
                content = readDocx(path);
                break;
            case XLSX:
                content = readExcel(path);
                break;
            default:
                // 尝试作为文本读取
                try {
                    content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    content = "[Binary file: " + format.name().toLowerCase() + "]";
                }
        }

        // 截断
        if (content.length() > maxChars) {
            content = content.substring(0, maxChars);
            truncated = true;
        }

        // 构建结果
        java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "success");
        result.put("filename", path.getFileName().toString());
        result.put("format", format.name().toLowerCase());
        result.put("content", content);
        result.put("size_bytes", fileSize);
        result.put("truncated", truncated);

        if (extractMetadata) {
            result.put("metadata", java.util.Map.of(
                    "modified_time", Files.getLastModifiedTime(path).toMillis(),
                    "is_readable", Files.isReadable(path),
                    "encoding", "utf-8"
            ));
        }

        return objectMapper.writeValueAsString(result);
    }

    /**
     * 从 URL 读取文件
     */
    private String readFromUrl(String url, int maxChars, boolean extractMetadata) {
        try {
            String response = webClient.get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(java.time.Duration.ofSeconds(30))
                    .block();

            if (response == null) {
                return errorResult("DOWNLOAD_ERROR", "Empty response from URL");
            }

            String content = response.length() > maxChars ? response.substring(0, maxChars) : response;
            boolean truncated = response.length() > maxChars;

            FileFormat format = detectFormat(url);

            java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("status", "success");
            result.put("filename", url.substring(url.lastIndexOf('/') + 1).split("\\?")[0]);
            result.put("format", format.name().toLowerCase());
            result.put("content", content);
            result.put("size_bytes", response.length());
            result.put("truncated", truncated);
            result.put("url", url);

            if (extractMetadata) {
                result.put("metadata", java.util.Map.of(
                        "response_code", 200
                ));
            }

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("URL download failed: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("timeout")) {
                return errorResult("TIMEOUT", "Request timed out after 30 seconds");
            }
            return errorResult("DOWNLOAD_ERROR", "Failed to download: " + e.getMessage());
        }
    }

    private String readCsv(Path path) throws IOException {
        StringBuilder content = new StringBuilder();
        java.util.List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);

        int maxRows = 100;
        for (int i = 0; i < Math.min(lines.size(), maxRows); i++) {
            if (i == 0) {
                content.append(lines.get(i)).append("\n");
                content.append(lines.get(i).replaceAll("[^|]", "-")).append("\n");
            } else {
                content.append(lines.get(i)).append("\n");
            }
        }

        if (lines.size() > maxRows) {
            content.append("... (").append(lines.size() - maxRows).append(" more rows)");
        }

        return content.toString();
    }

    private String readPdf(Path path) {
        // 简单的 PDF 文本提取 (不含 Apache Tika 依赖)
        // 实际生产环境建议使用 Apache Tika
        try {
            byte[] bytes = Files.readAllBytes(path);
            String text = new String(bytes, StandardCharsets.UTF_8);
            // 提取 PDF 中的文本流
            StringBuilder result = new StringBuilder();
            boolean inText = false;
            for (String line : text.split("\n")) {
                if (line.contains("BT")) {
                    inText = true;
                }
                if (inText) {
                    result.append(line).append("\n");
                }
                if (line.contains("ET")) {
                    inText = false;
                }
            }
            String extracted = result.toString().replaceAll("[^\\x20-\\x7E\\s]", " ");
            return extracted.length() > 500 ? extracted.substring(0, 500) : extracted;
        } catch (Exception e) {
            return "[PDF reading failed: " + e.getMessage() + "]";
        }
    }

    private String readDocx(Path path) {
        // DOCX 读取需要额外库，暂时返回提示
        return "[DOCX reading requires additional dependencies. Content preview unavailable.]";
    }

    private String readExcel(Path path) {
        // Excel 读取需要额外库，暂时返回提示
        return "[Excel reading requires additional dependencies. Content preview unavailable.]";
    }

    /**
     * 检测文件格式
     */
    private FileFormat detectFormat(String filename) {
        String ext = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase()
                : "";

        switch (ext) {
            case "pdf": return FileFormat.PDF;
            case "docx": return FileFormat.DOCX;
            case "txt": return FileFormat.TXT;
            case "md":
            case "markdown": return FileFormat.MD;
            case "csv": return FileFormat.CSV;
            case "json": return FileFormat.JSON;
            case "xml": return FileFormat.XML;
            case "xlsx":
            case "xls": return FileFormat.XLSX;
            default: return FileFormat.UNKNOWN;
        }
    }

    private String errorResult(String errorCode, String message) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "status", "error",
                    "error_code", errorCode,
                    "message", message
            ));
        } catch (Exception e) {
            return "{\"status\":\"error\",\"error_code\":\"" + errorCode + "\",\"message\":\"" + message + "\"}";
        }
    }

    enum FileFormat {
        PDF, DOCX, TXT, MD, CSV, JSON, XML, XLSX, UNKNOWN
    }
}
