package com.aiagent.service.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 通道注册表 - 参考 OpenClaw ChannelRegistry
 */
@Slf4j
@Component
public class ChannelRegistry {

    /**
     * 已注册的通道
     */
    private final Map<String, Channel> channels = new ConcurrentHashMap<>();

    /**
     * 通道初始化状态
     */
    private final Set<String> initializedChannels = new HashSet<>();

    /**
     * 注册通道
     */
    public void register(Channel channel) {
        if (channel == null) {
            throw new IllegalArgumentException("Channel cannot be null");
        }

        String channelId = channel.getId();
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("Channel ID cannot be null or empty");
        }

        channels.put(channelId, channel);
        log.info("Channel registered: {} ({})", channel.getName(), channel.getType());
    }

    /**
     * 注销通道
     */
    public void unregister(String channelId) {
        Channel channel = channels.remove(channelId);
        if (channel != null) {
            try {
                channel.stop();
            } catch (Channel.ChannelException e) {
                log.error("Error stopping channel: {}", channelId, e);
            }
            initializedChannels.remove(channelId);
            log.info("Channel unregistered: {}", channelId);
        }
    }

    /**
     * 获取通道
     */
    public Channel get(String channelId) {
        return channels.get(channelId);
    }

    /**
     * 获取所有通道
     */
    public Collection<Channel> getAll() {
        return channels.values();
    }

    /**
     * 按类型获取通道
     */
    public List<Channel> getByType(Channel.ChannelType type) {
        List<Channel> result = new ArrayList<>();
        for (Channel channel : channels.values()) {
            if (channel.getType() == type) {
                result.add(channel);
            }
        }
        return result;
    }

    /**
     * 初始化所有通道
     */
    public void initializeAll() {
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            String channelId = entry.getKey();
            Channel channel = entry.getValue();

            if (!initializedChannels.contains(channelId)) {
                try {
                    channel.initialize(new DefaultChannelContext(channelId));
                    channel.start();
                    initializedChannels.add(channelId);
                    log.info("Channel initialized and started: {}", channelId);
                } catch (Channel.ChannelException e) {
                    log.error("Failed to initialize channel: {}", channelId, e);
                }
            }
        }
    }

    /**
     * 停止所有通道
     */
    public void stopAll() {
        for (Map.Entry<String, Channel> entry : channels.entrySet()) {
            try {
                entry.getValue().stop();
                log.info("Channel stopped: {}", entry.getKey());
            } catch (Channel.ChannelException e) {
                log.error("Error stopping channel: {}", entry.getKey(), e);
            }
        }
        initializedChannels.clear();
    }

    /**
     * 发送消息到指定通道
     */
    public void sendTo(String channelId, String message, String target) throws Channel.ChannelException {
        Channel channel = channels.get(channelId);
        if (channel != null && channel.isConnected()) {
            channel.send(message, target);
        } else {
            throw new Channel.ChannelException("Channel not available: " + channelId);
        }
    }

    /**
     * 检查通道是否已初始化
     */
    public boolean isInitialized(String channelId) {
        return initializedChannels.contains(channelId);
    }

    /**
     * 获取通道数量
     */
    public int size() {
        return channels.size();
    }

    /**
     * 默认通道上下文
     */
    private static class DefaultChannelContext implements Channel.ChannelContext {
        private final String channelId;

        public DefaultChannelContext(String channelId) {
            this.channelId = channelId;
        }

        @Override
        public String getConfig(String key) {
            return getConfig(key, null);
        }

        @Override
        public String getConfig(String key, String defaultValue) {
            return System.getProperty("channel." + channelId + "." + key, defaultValue);
        }

        @Override
        public String getChannelId() {
            return channelId;
        }
    }
}
