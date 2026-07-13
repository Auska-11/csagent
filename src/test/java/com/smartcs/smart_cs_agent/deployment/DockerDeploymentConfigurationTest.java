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
    void dockerImageShouldPackageSkillsAndExposeReadinessHealthcheck() throws IOException {
        String dockerfile = Files.readString(Path.of("Dockerfile"));

        assertTrue(dockerfile.contains("COPY skills skills"));
        assertTrue(dockerfile.indexOf("COPY skills skills") < dockerfile.indexOf("package"));
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
    }
}
