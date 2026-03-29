package com.aiagent.service.tenant;

import com.aiagent.common.dto.TenantApiKey;
import com.aiagent.common.dto.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 用户 API Key 管理 (用户查询自己的 Key 信息)
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/keys")
@RequiredArgsConstructor
public class TenantApiKeyController {

    private final TenantApiKeyService tenantApiKeyService;

    /**
     * 获取当前 Key 信息
     */
    @GetMapping("/me")
    public Mono<ResponseEntity<TenantApiKey>> getCurrentKeyInfo(ServerWebExchange exchange) {
        TenantContext context = TenantContextHolder.getContext();
        if (context == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        TenantApiKey key = tenantApiKeyService.getKeyById(context.getApiKeyId());
        if (key == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }

        // 不返回敏感字段
        key.setKeyHash(null);
        return Mono.just(ResponseEntity.ok(key));
    }

    /**
     * 停用当前 Key
     */
    @DeleteMapping("/me")
    public Mono<ResponseEntity<Void>> deactivateCurrentKey(ServerWebExchange exchange) {
        TenantContext context = TenantContextHolder.getContext();
        if (context == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        tenantApiKeyService.deactivateApiKey(context.getApiKeyId());
        log.info("User deactivated their own API key: keyId={}", context.getApiKeyId());

        return Mono.just(ResponseEntity.noContent().build());
    }

    /**
     * 检查工具权限
     */
    @GetMapping("/me/tools/{toolName}")
    public Mono<ResponseEntity<java.util.Map<String, Object>>> checkToolPermission(
            @PathVariable String toolName,
            ServerWebExchange exchange) {

        TenantContext context = TenantContextHolder.getContext();
        if (context == null) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }

        boolean allowed = context.isToolAllowed(toolName);
        return Mono.just(ResponseEntity.ok(java.util.Map.of(
                "tool", toolName,
                "allowed", allowed,
                "tenantId", context.getTenantId(),
                "keyAlias", context.getApiKeyAlias()
        )));
    }
}
