package com.aiagent.service.plugin;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件注册表 - 参考 OpenClaw PluginRegistry
 * 统一管理所有插件的注册、发现和调用
 */
@Slf4j
@Component
public class PluginRegistry {

    /**
     * 已注册的插件
     */
    private final Map<String, Plugin> plugins = new ConcurrentHashMap<>();

    /**
     * 按类型索引的插件
     */
    private final Map<Plugin.PluginType, List<Plugin>> pluginsByType = new ConcurrentHashMap<>();

    /**
     * 插件初始化状态
     */
    private final Set<String> initializedPlugins = new HashSet<>();

    /**
     * 注册插件
     *
     * @param plugin 插件实例
     */
    public void register(Plugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("Plugin cannot be null");
        }

        String pluginId = plugin.getId();
        if (pluginId == null || pluginId.isEmpty()) {
            throw new IllegalArgumentException("Plugin ID cannot be null or empty");
        }

        if (plugins.containsKey(pluginId)) {
            log.warn("Plugin {} already registered, replacing", pluginId);
        }

        plugins.put(pluginId, plugin);

        // 按类型索引
        pluginsByType.computeIfAbsent(plugin.getType(), k -> new ArrayList<>()).add(plugin);

        log.info("Plugin registered: {} v{} ({})", plugin.getName(), plugin.getVersion(), plugin.getType());
    }

    /**
     * 注销插件
     *
     * @param pluginId 插件ID
     */
    public void unregister(String pluginId) {
        Plugin plugin = plugins.remove(pluginId);
        if (plugin != null) {
            // 从类型索引中移除
            List<Plugin> typePlugins = pluginsByType.get(plugin.getType());
            if (typePlugins != null) {
                typePlugins.remove(plugin);
            }

            // 销毁插件
            try {
                plugin.destroy();
            } catch (Exception e) {
                log.error("Error destroying plugin: {}", pluginId, e);
            }

            initializedPlugins.remove(pluginId);
            log.info("Plugin unregistered: {}", pluginId);
        }
    }

    /**
     * 获取插件
     *
     * @param pluginId 插件ID
     * @return 插件实例
     */
    public Plugin get(String pluginId) {
        return plugins.get(pluginId);
    }

    /**
     * 获取所有插件
     */
    public Collection<Plugin> getAll() {
        return plugins.values();
    }

    /**
     * 按类型获取插件
     */
    public List<Plugin> getByType(Plugin.PluginType type) {
        return pluginsByType.getOrDefault(type, Collections.emptyList());
    }

    /**
     * 获取所有工具类插件
     */
    public List<Plugin> getTools() {
        return getByType(Plugin.PluginType.TOOL);
    }

    /**
     * 获取所有通道类插件
     */
    public List<Plugin> getChannels() {
        return getByType(Plugin.PluginType.CHANNEL);
    }

    /**
     * 初始化单个插件
     */
    public void initialize(String pluginId) {
        Plugin plugin = plugins.get(pluginId);
        if (plugin != null && !initializedPlugins.contains(pluginId)) {
            try {
                plugin.initialize(new DefaultPluginContext());
                initializedPlugins.add(pluginId);
                log.info("Plugin initialized: {}", pluginId);
            } catch (Plugin.PluginException e) {
                log.error("Failed to initialize plugin: {}", pluginId, e);
            }
        }
    }

    /**
     * 初始化所有插件
     */
    public void initializeAll() {
        for (String pluginId : plugins.keySet()) {
            initialize(pluginId);
        }
    }

    /**
     * 检查插件是否已初始化
     */
    public boolean isInitialized(String pluginId) {
        return initializedPlugins.contains(pluginId);
    }

    /**
     * 检查插件是否存在
     */
    public boolean has(String pluginId) {
        return plugins.containsKey(pluginId);
    }

    /**
     * 获取已注册的插件数量
     */
    public int size() {
        return plugins.size();
    }

    /**
     * 获取已初始化的插件数量
     */
    public int getInitializedCount() {
        return initializedPlugins.size();
    }

    /**
     * 默认插件上下文实现
     */
    private static class DefaultPluginContext implements Plugin.PluginContext {
        @Override
        public String getConfig(String key) {
            return getConfig(key, null);
        }

        @Override
        public String getConfig(String key, String defaultValue) {
            // 从 Spring Environment 获取
            return System.getProperty("plugin." + key,
                    System.getenv("PLUGIN_" + key.toUpperCase().replace(".", "_").replace("-", "_")));
        }

        @Override
        public String getPluginDir() {
            return System.getProperty("plugin.dir", "/tmp/plugins");
        }

        @Override
        public java.util.logging.Logger getLogger() {
            return java.util.logging.Logger.getLogger("plugin");
        }
    }
}
