FROM eclipse-temurin:17-jdk-alpine AS builder

WORKDIR /app

# 复制 Maven 配置文件
COPY pom.xml .
COPY agent-core/pom.xml agent-core/
COPY agent-core/agent-common/pom.xml agent-core/agent-common/
COPY agent-core/agent-api/pom.xml agent-core/agent-api/
COPY agent-core/agent-service/pom.xml agent-core/agent-service/
COPY agent-core/agent-boot/pom.xml agent-core/agent-boot/

# 下载依赖（利用 Docker 缓存）
RUN mvn dependency:go-offline -B || true

# 复制源码
COPY . .

# 构建
RUN mvn clean package -DskipTests -B

# 运行镜像
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# 安装 curl 用于健康检查
RUN apk add --no-cache curl

# 从构建阶段复制 JAR 文件
COPY --from=builder /app/agent-core/agent-boot/target/*.jar app.jar

# 环境变量
ENV JAVA_OPTS="-Xms2g -Xmx4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# 端口
EXPOSE 8080

# 启动命令
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
