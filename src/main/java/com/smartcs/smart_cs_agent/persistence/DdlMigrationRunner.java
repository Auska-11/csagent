package com.smartcs.smart_cs_agent.persistence;

import java.util.Locale;

/**
 * 幂等 DDL 执行器。
 *
 * <p>MySQL 8 的 {@code ALTER TABLE ... ADD COLUMN / ADD UNIQUE KEY} 不支持
 * {@code IF NOT EXISTS}。启动期迁移只能执行后识别“已经存在”的错误并忽略，
 * 其余数据库故障必须原样抛出，不能把真实的连接或权限问题吞成“迁移成功”。</p>
 */
public final class DdlMigrationRunner {

    private DdlMigrationRunner() {
    }

    /**
     * 执行一次启动期 DDL。
     *
     * @param executor              统一异常翻译的 mapper 执行器
     * @param ddl                   要执行的 DDL 操作
     * @param alreadyAppliedMarker  数据库报错信息中代表“对象已存在”的片段，
     *                              如 MySQL 的 {@code Duplicate column name} / {@code Duplicate key name}
     */
    public static void run(MyBatisMapperExecutor executor, Runnable ddl, String alreadyAppliedMarker) {
        try {
            executor.run(ddl);
        } catch (RuntimeException failure) {
            if (!mentions(failure, alreadyAppliedMarker) && !mentions(failure, ALREADY_EXISTS_MARKER)) {
                throw failure;
            }
        }
    }

    /**
     * H2 对重复列/重复约束的报错措辞是 {@code already exists}（测试上下文用它跑同一套迁移），
     * 与 MySQL 的 Duplicate 措辞表达同一个幂等结果，同样应当忽略。
     */
    private static final String ALREADY_EXISTS_MARKER = "already exists";


    private static boolean mentions(Throwable failure, String marker) {
        String expected = marker.toLowerCase(Locale.ROOT);
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(expected)) {
                return true;
            }
        }
        return false;
    }
}
