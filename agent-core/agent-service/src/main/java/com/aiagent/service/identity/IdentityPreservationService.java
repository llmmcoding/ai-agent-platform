package com.aiagent.service.identity;

import com.aiagent.common.dto.AgentRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identity Re-Injection 机制
 *
 * learn-claude-code s11 启发: 对话压缩后重新注入身份信息
 * 防止 Agent 在长对话或上下文压缩后丢失身份
 *
 * 原理:
 * 1. 记录 Agent 的身份信息 (name, role, team)
 * 2. 压缩后重新注入身份 XML 标签
 * 3. 确保 Agent 始终知道自己的角色
 */
@Slf4j
@Service
public class IdentityPreservationService {

    /**
     * 身份信息 Key: sessionId
     * 身份信息包含: name, role, team, personality, instructions
     */
    private final Map<String, AgentIdentity> identityStore = new ConcurrentHashMap<>();

    /**
     * 压缩后注入的身份标签
     */
    private static final String IDENTITY_TAG_TEMPLATE =
            "<identity name=\"%s\" role=\"%s\" team=\"%s\">\n" +
            "You are '%s', your role is: %s.\n" +
            "%s" +
            "Reminder: You are continuing a conversation that was previously compressed. " +
            "Maintain your identity and continue helping the user.\n" +
            "</identity>";

    /**
     * 注册/更新 Agent 身份
     *
     * @param sessionId 会话 ID
     * @param name Agent 名称
     * @param role Agent 角色
     * @param team 团队名称 (可选)
     * @param personality 个性描述 (可选)
     * @param customInstructions 自定义指令 (可选)
     */
    public void registerIdentity(String sessionId, String name, String role, String team,
                                  String personality, String customInstructions) {
        AgentIdentity identity = AgentIdentity.builder()
                .name(name)
                .role(role)
                .team(team)
                .personality(personality)
                .customInstructions(customInstructions)
                .registeredAt(System.currentTimeMillis())
                .build();

        identityStore.put(sessionId, identity);
        log.info("Identity registered for session {}: name={}, role={}, team={}",
                sessionId, name, role, team);
    }

    /**
     * 从 AgentRequest 提取并注册身份
     */
    public void registerFromRequest(AgentRequest request) {
        String sessionId = request.getSessionId() != null ? request.getSessionId() : "default";

        // 从 systemPrompt 或 agentType 推断身份
        String name = extractName(request);
        String role = request.getAgentType() != null ? request.getAgentType() : "assistant";
        String team = extractTeam(request);
        String personality = extractPersonality(request);

        registerIdentity(sessionId, name, role, team, personality, null);
    }

    /**
     * 生成压缩后需要注入的身份标签
     *
     * @param sessionId 会话 ID
     * @param compressedPrompt 压缩后的 prompt
     * @return 包含身份标签的 prompt
     */
    public String injectIdentity(String sessionId, String compressedPrompt) {
        AgentIdentity identity = identityStore.get(sessionId);

        if (identity == null) {
            log.debug("No identity found for session {}, skipping injection", sessionId);
            return compressedPrompt;
        }

        // 构建身份标签
        String identityTag = buildIdentityTag(identity);

        // 在 compressedPrompt 开头插入身份标签
        // 检测是否已经有身份标签，避免重复注入
        if (compressedPrompt.contains("<identity")) {
            log.debug("Identity already present in prompt for session {}", sessionId);
            return compressedPrompt;
        }

        String result = identityTag + "\n\n" + compressedPrompt;

        // 更新 last injected 时间
        identity.setLastInjectedAt(System.currentTimeMillis());

        log.debug("Identity injected for session {}, tag length: {}", sessionId, identityTag.length());
        return result;
    }

    /**
     * 检查是否需要重新注入身份
     *
     * @param sessionId 会话 ID
     * @param currentPrompt 当前 prompt
     * @param roundsSinceInjection 自上次注入后的轮次
     * @return 是否需要注入
     */
    public boolean needsReInjection(String sessionId, String currentPrompt, int roundsSinceInjection) {
        // 如果 prompt 中没有身份标签，需要注入
        if (!currentPrompt.contains("<identity")) {
            return true;
        }

        // 如果超过 5 轮没有注入，强制注入
        if (roundsSinceInjection > 5) {
            return true;
        }

        return false;
    }

    /**
     * 获取身份摘要 (用于日志和调试)
     */
    public String getIdentitySummary(String sessionId) {
        AgentIdentity identity = identityStore.get(sessionId);
        if (identity == null) {
            return "No identity";
        }
        return String.format("name=%s, role=%s, team=%s",
                identity.getName(), identity.getRole(), identity.getTeam());
    }

    /**
     * 清除会话身份
     */
    public void clearIdentity(String sessionId) {
        identityStore.remove(sessionId);
        log.info("Identity cleared for session {}", sessionId);
    }

    /**
     * 从 request 提取 name
     */
    private String extractName(AgentRequest request) {
        // 优先从 systemPrompt 中提取
        if (request.getSystemPrompt() != null) {
            // 尝试匹配 "You are 'XXX'" 模式
            String prompt = request.getSystemPrompt();
            int quoteStart = prompt.indexOf("You are '");
            if (quoteStart >= 0) {
                int start = quoteStart + 9;
                int quoteEnd = prompt.indexOf("'", start);
                if (quoteEnd > start) {
                    return prompt.substring(start, quoteEnd);
                }
            }
            // 尝试匹配 "name: XXX" 模式
            int nameStart = prompt.indexOf("name:");
            if (nameStart >= 0) {
                int start = nameStart + 5;
                int end = prompt.indexOf("\n", start);
                if (end > start) {
                    return prompt.substring(start, end).trim();
                }
            }
        }
        // 降级: 使用 agentType
        return request.getAgentType() != null ?
                capitalize(request.getAgentType()) + " Agent" : "AI Assistant";
    }

    /**
     * 从 request 提取 team
     */
    private String extractTeam(AgentRequest request) {
        // 目前从 parameters 或 customFields 中提取
        if (request.getParameters() != null && request.getParameters().containsKey("team")) {
            return (String) request.getParameters().get("team");
        }
        return "single"; // 默认单 Agent
    }

    /**
     * 从 request 提取 personality
     */
    private String extractPersonality(AgentRequest request) {
        if (request.getParameters() != null && request.getParameters().containsKey("personality")) {
            return (String) request.getParameters().get("personality");
        }
        return null;
    }

    /**
     * 构建身份标签
     */
    private String buildIdentityTag(AgentIdentity identity) {
        String personalitySection = identity.getPersonality() != null ?
                "Personality: " + identity.getPersonality() + "\n" : "";

        return String.format(IDENTITY_TAG_TEMPLATE,
                identity.getName(),
                identity.getRole(),
                identity.getTeam() != null ? identity.getTeam() : "",
                identity.getName(),
                identity.getRole(),
                personalitySection);
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /**
     * Agent 身份数据模型
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    private static class AgentIdentity {
        private String name;
        private String role;
        private String team;
        private String personality;
        private String customInstructions;
        private long registeredAt;
        private long lastInjectedAt;
    }
}
