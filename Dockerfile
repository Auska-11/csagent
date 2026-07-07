FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

# 先复制 Maven 描述文件，方便 Docker 利用依赖缓存。
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn

# 再复制源码并打包。
# Docker 构建阶段默认跳过测试，测试仍然建议在本地或 CI 里单独执行。
COPY src src
RUN chmod +x mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre

WORKDIR /app

ENV SERVER_PORT=8080
ENV JAVA_OPTS=""

COPY --from=build /app/target/smart-cs-agent-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
