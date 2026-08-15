package com.smartcs.smart_cs_agent.deployment;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Docker 生产文件的静态回归门禁。
 *
 * <p>它不能代替真实容器启动，但能防止以后重写 Dockerfile/Compose 时再次漏掉 Skill、MySQL 或
 * readiness。真实运行验收由独立 real-mysql profile 和 Docker 命令完成。</p>
 */
class DockerDeploymentConfigurationTest {

    @Test
    void dockerIgnoreShouldRemainAsciiForDocker24Compatibility() throws IOException {
        String dockerIgnore = Files.readString(Path.of(".dockerignore"));

        /*
         * Docker Desktop 24.0.7 会把 ignore 规则写进 BuildKit 的 exclude-patterns 请求头。
         * 如果规则里直接包含中文路径，旧版 BuildKit 会以“请求头含非打印字符”为由中止构建。
         * 当前镜像不需要 docs 下的任何文件，所以忽略整个 docs 目录既能缩小上下文，也能保持规则为 ASCII。
         */
        assertTrue(dockerIgnore.chars().allMatch(character -> character <= 0x7F));
        assertTrue(dockerIgnore.lines().anyMatch("docs"::equals));
    }

    @Test
    void dockerImageShouldPackageSkillsAndExposeReadinessHealthcheck() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(dockerfile.contains("COPY skills skills"));
        // 校验运行 Maven 打包前已经把 Skill 包复制进构建上下文；不要匹配前面
        // frontend/package.json 中无关的 "package" 字符串。
        assertTrue(dockerfile.indexOf("COPY skills skills") < dockerfile.indexOf("RUN chmod +x mvnw"));
        assertTrue(dockerfile.contains("/actuator/health/readiness"));
        assertTrue(dockerfile.contains("HEALTHCHECK"));
    }

    @Test
    void composeShouldWireHealthyMysqlBeforeStartingJdbcAgent() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"));

        assertTrue(compose.contains("mysql:"));
        assertTrue(compose.contains("mysql:8.4.0"));
        assertTrue(compose.contains("condition: service_healthy"));
        assertTrue(compose.contains("SMARTCS_PERSISTENCE_MODE: ${SMARTCS_PERSISTENCE_MODE:-jdbc}"));
        assertTrue(compose.contains("jdbc:mysql://mysql:3306/smart_cs_agent"));
        assertTrue(compose.contains("/actuator/health/readiness"));
        assertTrue(compose.contains("mysql_data:/var/lib/mysql"));
        assertTrue(compose.contains("OPENAI_MAX_RETRIES: ${OPENAI_MAX_RETRIES:-1}"));
        assertTrue(compose.contains("SMARTCS_AGENT_TURN_TIMEOUT_MS: ${SMARTCS_AGENT_TURN_TIMEOUT_MS:-30000}"));
        assertTrue(compose.contains("SMARTCS_LLM_EXECUTION_CALL_TIMEOUT_MS:"));
        // 宿主机端口必须可覆盖；容器内端口保持 8080，健康探针因此不需要跟着变化。
        assertTrue(compose.contains("${SMARTCS_AGENT_PORT:-8080}:8080"));
    }
}
