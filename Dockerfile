FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# 先复制 Maven 描述文件，方便 Docker 利用依赖缓存。
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn

# 再复制源码并打包。
# Docker 构建阶段默认跳过测试，测试仍然建议在本地或 CI 里单独执行。
COPY src src
# Skill Package 位于项目根目录，不在 src 中；缺少这一行时本地 JAR 正常、容器 JAR 却没有 Skill。
COPY skills skills
RUN chmod +x mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app

# Compose 使用 readiness 探针判断应用是否可以接收流量。
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

ENV SERVER_PORT=8080
ENV JAVA_OPTS=""

COPY --from=build /app/target/smart-cs-agent-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=6 \
    CMD curl --fail --silent http://localhost:8080/actuator/health/readiness > /dev/null || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
