package com.aiagent.service.plugin;

/**
 * 插件接口 - 参考 OpenClaw Plugin 架构
 * 所有 Agent 插件都必须实现此接口
 */
public interface Plugin {

    /**
     * 获取插件唯一标识
     */
    String getId();

    /**
     * 获取插件名称
     */
    String getName();

    /**
     * 获取插件版本
     */
    String getVersion();

    /**
     * 获取插件描述
     */
    String getDescription();

    /**
     * 插件类型
     */
    PluginType getType();

    /**
     * 初始化插件
     */
    void initialize(PluginContext context) throws PluginException;

    /**
     * 执行插件
     *
     * @param input 插件输入
     * @return 插件输出
     */
    String execute(String input) throws PluginException;

    /**
     * 销毁插件，释放资源
     */
    void destroy();

    /**
     * 获取插件配置
     */
    default PluginConfig getConfig() {
        return null;
    }

    /**
     * 插件是否启用
     */
    default boolean isEnabled() {
        return true;
    }

    /**
     * 插件类型
     */
    enum PluginType {
        /** 工具类插件 */
        TOOL,
        /** 记忆类插件 */
        MEMORY,
        /** 通道类插件 */
        CHANNEL,
        /** 安全类插件 */
        SECURITY,
        /** 钩子类插件 */
        HOOK,
        /** 自定义插件 */
        CUSTOM
    }

    /**
     * 插件上下文
     */
    interface PluginContext {
        /**
         * 获取配置值
         */
        String getConfig(String key);

        /**
         * 获取配置值，带默认值
         */
        String getConfig(String key, String defaultValue);

        /**
         * 获取插件目录
         */
        String getPluginDir();

        /**
         * 获取日志实例
         */
        java.util.logging.Logger getLogger();
    }

    /**
     * 插件配置
     */
    interface PluginConfig {
        /**
         * 获取配置项
         */
        String get(String key);

        /**
         * 获取配置项，带默认值
         */
        String get(String key, String defaultValue);

        /**
         * 获取所有配置
         */
        java.util.Map<String, String> getAll();
    }

    /**
     * 插件异常
     */
    class PluginException extends Exception {
        public PluginException(String message) {
            super(message);
        }

        public PluginException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
