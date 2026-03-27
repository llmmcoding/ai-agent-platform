package com.aiagent.service.channel;

/**
 * 消息通道接口 - 参考 OpenClaw Channels 架构
 */
public interface Channel {

    /**
     * 获取通道 ID
     */
    String getId();

    /**
     * 获取通道名称
     */
    String getName();

    /**
     * 获取通道类型
     */
    ChannelType getType();

    /**
     * 初始化通道
     */
    void initialize(ChannelContext context) throws ChannelException;

    /**
     * 启动通道
     */
    void start() throws ChannelException;

    /**
     * 停止通道
     */
    void stop() throws ChannelException;

    /**
     * 发送消息
     *
     * @param message 消息内容
     * @param target  目标 (可选)
     */
    void send(String message, String target) throws ChannelException;

    /**
     * 检查通道是否已连接
     */
    boolean isConnected();

    /**
     * 通道类型
     */
    enum ChannelType {
        /** HTTP 通道 */
        HTTP,
        /** WebSocket 通道 */
        WEBSOCKET,
        /** Kafka 通道 */
        KAFKA,
        /** SSE 通道 */
        SSE,
        /** gRPC 通道 */
        GRPC,
        /** WebHook 通道 */
        WEBHOOK
    }

    /**
     * 通道上下文
     */
    interface ChannelContext {
        /**
         * 获取通道配置
         */
        String getConfig(String key);

        /**
         * 获取通道配置，带默认值
         */
        String getConfig(String key, String defaultValue);

        /**
         * 获取通道 ID
         */
        String getChannelId();
    }

    /**
     * 通道异常
     */
    class ChannelException extends Exception {
        public ChannelException(String message) {
            super(message);
        }

        public ChannelException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
