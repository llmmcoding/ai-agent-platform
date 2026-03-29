package com.aiagent.service.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ResourceAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenTelemetry 配置
 * 实现分布式链路追踪，支持导出到 Jaeger/Zipkin
 */
@Slf4j
@Configuration
public class OpenTelemetryConfig {

    @Value("${aiagent.tracing.enabled:true}")
    private boolean tracingEnabled;

    @Value("${aiagent.tracing.endpoint:http://localhost:4317}")
    private String otlpEndpoint;

    @Value("${aiagent.tracing.service-name:ai-agent}")
    private String serviceName;

    @Value("${aiagent.tracing.sample-rate:1.0}")
    private double sampleRate;

    @Value("${aiagent.tracing.export.batch-size:512}")
    private int batchSize;

    /**
     * 配置 OpenTelemetry SDK
     */
    @Bean
    public OpenTelemetry openTelemetry() {
        if (!tracingEnabled) {
            log.info("Tracing is disabled");
            return OpenTelemetry.noop();
        }

        log.info("Configuring OpenTelemetry with endpoint: {}, service: {}, sampleRate: {}",
                otlpEndpoint, serviceName, sampleRate);

        // 创建 Resource
        Resource resource = Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        ResourceAttributes.SERVICE_NAME, serviceName,
                        ResourceAttributes.SERVICE_VERSION, "1.0.0"
                )));

        // 配置采样器
        Sampler sampler = Sampler.traceIdRatioBased(sampleRate);

        // 配置 Span Exporter (OTLP gRPC)
        SpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(otlpEndpoint)
                .build();

        // 配置 Tracer Provider
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(spanExporter)
                        .setMaxQueueSize(batchSize)
                        .build())
                .setResource(resource)
                .setSampler(sampler)
                .build();

        // 创建 OpenTelemetry SDK
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        // 注册shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(tracerProvider::close));

        log.info("OpenTelemetry configured successfully");
        return openTelemetry;
    }

    /**
     * 获取 Tracer Bean
     */
    @Bean
    public Tracer tracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer(serviceName, "1.0.0");
    }
}
