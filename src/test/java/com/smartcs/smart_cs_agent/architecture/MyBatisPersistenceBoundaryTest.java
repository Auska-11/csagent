package com.smartcs.smart_cs_agent.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止原生 JDBC 访问链路或生产 SQL 重新绕过 MyBatis Mapper。 */
class MyBatisPersistenceBoundaryTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    @Test
    void productionCodeShouldNotUseNativeJdbcAccessTypes() throws IOException {
        List<String> forbidden = List.of(
                "import java.sql.",
                "JdbcDatabaseClient",
                "JdbcDatabaseOperations",
                "DriverManager",
                "PreparedStatement",
                "ResultSet"
        );
        try (var files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                for (String token : forbidden) {
                    assertFalse(
                            source.contains(token),
                            () -> file + " must not reintroduce native JDBC token: " + token
                    );
                }
            }
        }
    }

    @Test
    void removedNativeJdbcTypesShouldStayDeletedAndXmlMappersShouldExist() {
        assertFalse(Files.exists(MAIN_JAVA.resolve(
                "com/smartcs/smart_cs_agent/persistence/JdbcDatabaseClient.java"
        )));
        assertFalse(Files.exists(MAIN_JAVA.resolve(
                "com/smartcs/smart_cs_agent/persistence/JdbcDatabaseOperations.java"
        )));
        assertTrue(Files.exists(Path.of(
                "src/main/resources/mapper/session/SessionMapper.xml"
        )));
        assertTrue(Files.exists(Path.of(
                "src/main/resources/mapper/idempotency/CapabilityIdempotencyMapper.xml"
        )));
        assertTrue(Files.exists(Path.of(
                "src/main/resources/mapper/handoff/PendingHandoffMapper.xml"
        )));
    }
}
