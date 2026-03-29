package com.aiagent.service.tenant;

import com.aiagent.common.dto.*;
import com.aiagent.service.alert.QuotaAlertService;
import com.aiagent.service.usage.ApiUsageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 租户管理 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/admin/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantApiKeyService tenantApiKeyService;
    private final ApiUsageService apiUsageService;
    private final QuotaAlertService quotaAlertService;
    private final TenantRateLimiter tenantRateLimiter;

    // ==================== 租户管理 ====================

    /**
     * 创建租户
     */
    @PostMapping
    public Mono<ResponseEntity<Tenant>> createTenant(
            @RequestBody CreateTenantRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey,
            ServerWebExchange exchange) {

        // 验证平台管理员权限
        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant tenant = Tenant.builder()
                .name(request.getName())
                .code(request.getCode())
                .rpmLimit(request.getRpmLimit())
                .tpmLimit(request.getTpmLimit())
                .maxConcurrentRequests(request.getMaxConcurrentRequests() != null ? request.getMaxConcurrentRequests().longValue() : null)
                .metadata(request.getMetadata())
                .build();

        Tenant created = tenantApiKeyService.createTenant(tenant);
        log.info("Created tenant: id={}, code={}", created.getId(), created.getCode());

        return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(created));
    }

    /**
     * 获取租户
     */
    @GetMapping("/{id}")
    public Mono<ResponseEntity<Tenant>> getTenant(
            @PathVariable Long id,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant tenant = tenantApiKeyService.getTenant(id);
        if (tenant == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.ok(tenant));
    }

    /**
     * 获取租户 By Code
     */
    @GetMapping("/code/{code}")
    public Mono<ResponseEntity<Tenant>> getTenantByCode(
            @PathVariable String code,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant tenant = tenantApiKeyService.getTenantByCode(code);
        if (tenant == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return Mono.just(ResponseEntity.ok(tenant));
    }

    /**
     * 获取所有租户
     */
    @GetMapping
    public Mono<ResponseEntity<List<Tenant>>> getAllTenants(
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        List<Tenant> tenants = tenantApiKeyService.getAllTenants();
        return Mono.just(ResponseEntity.ok(tenants));
    }

    /**
     * 更新租户
     */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<Tenant>> updateTenant(
            @PathVariable Long id,
            @RequestBody UpdateTenantRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant existing = tenantApiKeyService.getTenant(id);
        if (existing == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        // 更新字段
        if (request.getName() != null) existing.setName(request.getName());
        if (request.getRpmLimit() != null) existing.setRpmLimit(request.getRpmLimit());
        if (request.getTpmLimit() != null) existing.setTpmLimit(request.getTpmLimit());
        if (request.getMaxConcurrentRequests() != null) existing.setMaxConcurrentRequests(request.getMaxConcurrentRequests().longValue());
        if (request.getMetadata() != null) existing.setMetadata(request.getMetadata());

        Tenant updated = tenantApiKeyService.updateTenant(id, existing);
        return Mono.just(ResponseEntity.ok(updated));
    }

    /**
     * 停用租户
     */
    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> deactivateTenant(
            @PathVariable Long id,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant existing = tenantApiKeyService.getTenant(id);
        if (existing == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        tenantApiKeyService.deactivateTenant(id);
        return Mono.just(ResponseEntity.noContent().build());
    }

    // ==================== API Key 管理 ====================

    /**
     * 为租户生成 API Key
     */
    @PostMapping("/{id}/keys")
    public Mono<ResponseEntity<TenantApiKeyService.ApiKeyGenerateResult>> generateApiKey(
            @PathVariable Long id,
            @RequestBody GenerateKeyRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant tenant = tenantApiKeyService.getTenant(id);
        if (tenant == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        try {
            TenantApiKeyService.ApiKeyGenerateResult result = tenantApiKeyService.generateApiKey(
                    id,
                    request.getUserId(),
                    request.getRpmLimit(),
                    request.getTpmLimit(),
                    request.getAllowedTools(),
                    request.getExpiresAt()
            );
            return Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(result));
        } catch (Exception e) {
            log.error("Failed to generate API key: {}", e.getMessage());
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
        }
    }

    /**
     * 获取租户所有 API Key
     */
    @GetMapping("/{id}/keys")
    public Mono<ResponseEntity<List<TenantApiKey>>> getTenantKeys(
            @PathVariable Long id,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant tenant = tenantApiKeyService.getTenant(id);
        if (tenant == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        List<TenantApiKey> keys = tenantApiKeyService.getKeysByTenant(id);
        return Mono.just(ResponseEntity.ok(keys));
    }

    /**
     * 停用 API Key
     */
    @DeleteMapping("/{tenantId}/keys/{keyId}")
    public Mono<ResponseEntity<Void>> deactivateApiKey(
            @PathVariable Long tenantId,
            @PathVariable Long keyId,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        TenantApiKey key = tenantApiKeyService.getKeyById(keyId);
        if (key == null || !key.getTenantId().equals(tenantId)) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        tenantApiKeyService.deactivateApiKey(keyId);
        return Mono.just(ResponseEntity.noContent().build());
    }

    // ==================== 使用量统计 ====================

    /**
     * 获取租户使用量统计
     */
    @GetMapping("/{id}/usage")
    public Mono<ResponseEntity<ApiUsageStatistics>> getTenantUsage(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant tenant = tenantApiKeyService.getTenant(id);
        if (tenant == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        if (start == null) start = LocalDateTime.now().minusDays(7);
        if (end == null) end = LocalDateTime.now();

        // 获取该租户下所有 key 的汇总
        List<TenantApiKey> keys = tenantApiKeyService.getKeysByTenant(id);
        long totalRequests = 0, totalInputTokens = 0, totalOutputTokens = 0, totalErrors = 0;
        long totalLatency = 0;

        for (TenantApiKey key : keys) {
            ApiUsageStatistics stats = apiUsageService.getKeyUsageStatistics(key.getId(), start, end);
            totalRequests += stats.getTotalRequests();
            totalInputTokens += stats.getTotalInputTokens();
            totalOutputTokens += stats.getTotalOutputTokens();
            totalErrors += stats.getTotalErrors();
            totalLatency += stats.getAvgLatencyMs();
        }

        long avgLatency = totalRequests > 0 ? totalLatency / keys.size() : 0;

        ApiUsageStatistics summary = ApiUsageStatistics.builder()
                .tenantId(id)
                .startTime(start)
                .endTime(end)
                .totalRequests(totalRequests)
                .totalInputTokens(totalInputTokens)
                .totalOutputTokens(totalOutputTokens)
                .totalTokens(totalInputTokens + totalOutputTokens)
                .totalErrors(totalErrors)
                .avgLatencyMs(avgLatency)
                .build();

        return Mono.just(ResponseEntity.ok(summary));
    }

    /**
     * 获取 Key 使用量统计
     */
    @GetMapping("/{tenantId}/keys/{keyId}/usage")
    public Mono<ResponseEntity<ApiUsageStatistics>> getKeyUsage(
            @PathVariable Long tenantId,
            @PathVariable Long keyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        if (start == null) start = LocalDateTime.now().minusDays(7);
        if (end == null) end = LocalDateTime.now();

        ApiUsageStatistics stats = apiUsageService.getKeyUsageStatistics(keyId, start, end);
        return Mono.just(ResponseEntity.ok(stats));
    }

    /**
     * 获取配额使用率
     */
    @GetMapping("/{id}/quota")
    public Mono<ResponseEntity<QuotaUsage>> getQuotaUsage(
            @PathVariable Long id,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        Tenant tenant = tenantApiKeyService.getTenant(id);
        if (tenant == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        // 获取租户级别使用量
        TenantRateLimiter.RateLimitUsage tenantUsage = tenantRateLimiter.getTenantUsage(id);

        QuotaUsage quotaUsage = QuotaUsage.builder()
                .tenantId(id)
                .tenantRpmLimit(tenant.getRpmLimit())
                .tenantRpmUsed(tenantUsage.currentRpm())
                .tenantRpmPercent(tenant.getRpmLimit() > 0 ?
                        (double) tenantUsage.currentRpm() / tenant.getRpmLimit() * 100 : 0)
                .tenantTpmLimit(tenant.getTpmLimit())
                .tenantTpmUsed(tenantUsage.currentTpm())
                .tenantTpmPercent(tenant.getTpmLimit() > 0 ?
                        (double) tenantUsage.currentTpm() / tenant.getTpmLimit() * 100 : 0)
                .build();

        return Mono.just(ResponseEntity.ok(quotaUsage));
    }

    // ==================== 告警管理 ====================

    /**
     * 获取租户告警列表
     */
    @GetMapping("/{id}/alerts")
    public Mono<ResponseEntity<List<QuotaAlert>>> getAlerts(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean acknowledgedOnly,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        List<QuotaAlert> alerts = quotaAlertService.getAlerts(id, acknowledgedOnly);
        return Mono.just(ResponseEntity.ok(alerts));
    }

    /**
     * 确认告警
     */
    @PostMapping("/{tenantId}/alerts/{alertId}/acknowledge")
    public Mono<ResponseEntity<Void>> acknowledgeAlert(
            @PathVariable Long tenantId,
            @PathVariable Long alertId,
            @RequestBody AcknowledgeAlertRequest request,
            @RequestHeader(value = "X-API-Key", required = false) String apiKey) {

        if (!tenantApiKeyService.validatePlatformKey(apiKey)) {
            return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN).build());
        }

        quotaAlertService.acknowledgeAlert(alertId, request.getAcknowledgedBy());
        return Mono.just(ResponseEntity.ok().build());
    }

    // ==================== Request DTOs ====================

    @lombok.Data
    public static class CreateTenantRequest {
        private String name;
        private String code;
        private Long rpmLimit;
        private Long tpmLimit;
        private Integer maxConcurrentRequests;
        private java.util.Map<String, String> metadata;
    }

    @lombok.Data
    public static class UpdateTenantRequest {
        private String name;
        private Long rpmLimit;
        private Long tpmLimit;
        private Integer maxConcurrentRequests;
        private java.util.Map<String, String> metadata;
    }

    @lombok.Data
    public static class GenerateKeyRequest {
        private String userId;
        private Long rpmLimit;
        private Long tpmLimit;
        private String allowedTools;  // 逗号分隔
        private java.time.LocalDateTime expiresAt;  // null = 永久
    }

    @lombok.Data
    public static class AcknowledgeAlertRequest {
        private String acknowledgedBy;
    }
}
