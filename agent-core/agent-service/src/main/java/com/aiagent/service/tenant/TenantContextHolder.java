package com.aiagent.service.tenant;

import com.aiagent.common.dto.TenantContext;

/**
 * 租户上下文持有者 (ThreadLocal 存储)
 * 用于在请求生命周期内传递租户上下文
 */
public class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CONTEXT = new ThreadLocal<>();

    /**
     * 设置租户上下文
     */
    public static void setContext(TenantContext context) {
        CONTEXT.set(context);
    }

    /**
     * 获取租户上下文
     */
    public static TenantContext getContext() {
        return CONTEXT.get();
    }

    /**
     * 获取租户上下文，如果不存在则抛异常
     */
    public static TenantContext requireContext() {
        TenantContext context = CONTEXT.get();
        if (context == null) {
            throw new IllegalStateException("No tenant context available");
        }
        return context;
    }

    /**
     * 清除租户上下文
     */
    public static void clear() {
        CONTEXT.remove();
    }

    /**
     * 检查是否有租户上下文
     */
    public static boolean hasContext() {
        return CONTEXT.get() != null;
    }
}
