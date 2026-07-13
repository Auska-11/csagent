-- 客服 Agent 第二十批：PendingHandoff 三张表的租户隔离迁移（MySQL 8.4）
--
-- 重要：旧表没有 tenant_id，数据库无法自动猜出历史记录属于哪个租户。
-- 执行前必须：
--   1. 停止所有 Agent 实例和 Recovery Worker；
--   2. 完成数据库备份；
--   3. 把下面的 CHANGE_ME 改成经过业务确认的历史租户 ID；
--   4. 确认历史数据确实只属于这一个租户。

SET @legacy_tenant_id = 'CHANGE_ME';

DROP PROCEDURE IF EXISTS migrate_pending_handoff_tenant;

DELIMITER //

CREATE PROCEDURE migrate_pending_handoff_tenant()
BEGIN
    IF @legacy_tenant_id IS NULL
        OR TRIM(@legacy_tenant_id) = ''
        OR @legacy_tenant_id = 'CHANGE_ME' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Set @legacy_tenant_id to the confirmed historical tenant before migration';
    END IF;

    -- 与 Java/JWT 的租户规范保持一致，避免运维误填空格后产生永远查询不到的历史记录。
    SET @legacy_tenant_id = TRIM(@legacy_tenant_id);

    IF CHAR_LENGTH(@legacy_tenant_id) > 64 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'The historical tenant id must not exceed 64 characters';
    END IF;

    -- 第一步先扩展为可空列，使旧版本与新版本之间的迁移意图清晰可见。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff'
          AND column_name = 'tenant_id'
    ) THEN
        ALTER TABLE cs_pending_handoff
            ADD COLUMN tenant_id VARCHAR(64) NULL AFTER outbox_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff_result'
          AND column_name = 'tenant_id'
    ) THEN
        ALTER TABLE cs_pending_handoff_result
            ADD COLUMN tenant_id VARCHAR(64) NULL FIRST;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff_claim'
          AND column_name = 'tenant_id'
    ) THEN
        ALTER TABLE cs_pending_handoff_claim
            ADD COLUMN tenant_id VARCHAR(64) NULL FIRST;
    END IF;

    -- 旧主表的 idempotency_key 是全局唯一，因此可先给主记录回填已确认的历史租户。
    UPDATE cs_pending_handoff
    SET tenant_id = @legacy_tenant_id
    WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';

    -- 结果和租约通过旧主表的全局唯一幂等键继承租户；不能凭默认值吞掉孤儿记录。
    UPDATE cs_pending_handoff_result AS result_row
    JOIN cs_pending_handoff AS handoff
      ON handoff.idempotency_key = result_row.idempotency_key
    SET result_row.tenant_id = handoff.tenant_id
    WHERE result_row.tenant_id IS NULL OR TRIM(result_row.tenant_id) = '';

    UPDATE cs_pending_handoff_claim AS claim_row
    JOIN cs_pending_handoff AS handoff
      ON handoff.idempotency_key = claim_row.idempotency_key
    SET claim_row.tenant_id = handoff.tenant_id
    WHERE claim_row.tenant_id IS NULL OR TRIM(claim_row.tenant_id) = '';

    IF EXISTS (
        SELECT 1 FROM cs_pending_handoff_result
        WHERE tenant_id IS NULL OR TRIM(tenant_id) = ''
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Orphan pending handoff result rows require manual reconciliation';
    END IF;

    IF EXISTS (
        SELECT 1 FROM cs_pending_handoff_claim
        WHERE tenant_id IS NULL OR TRIM(tenant_id) = ''
    ) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'Orphan pending handoff claim rows require manual reconciliation';
    END IF;

    -- 数据回填和孤儿检查完成后，才收紧 NOT NULL。
    ALTER TABLE cs_pending_handoff
        MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL;
    ALTER TABLE cs_pending_handoff_result
        MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL;
    ALTER TABLE cs_pending_handoff_claim
        MODIFY COLUMN tenant_id VARCHAR(64) NOT NULL;

    -- 主表先建立新复合唯一键，再删除旧单列唯一键，避免迁移中出现无约束窗口。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff'
          AND index_name = 'uk_cs_pending_handoff_tenant_idempotency'
    ) THEN
        ALTER TABLE cs_pending_handoff
            ADD UNIQUE KEY uk_cs_pending_handoff_tenant_idempotency
                (tenant_id, idempotency_key);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff'
          AND index_name = 'uk_cs_pending_handoff_idempotency'
    ) THEN
        ALTER TABLE cs_pending_handoff
            DROP INDEX uk_cs_pending_handoff_idempotency;
    END IF;

    -- 人工复核列表使用 tenant + status + (updated_at, outbox_id) 做 keyset 分页。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff'
          AND index_name = 'idx_cs_pending_handoff_tenant_review'
    ) THEN
        ALTER TABLE cs_pending_handoff
            ADD INDEX idx_cs_pending_handoff_tenant_review
                (tenant_id, status, updated_at, outbox_id);
    END IF;

    -- 结果表和租约表也必须使用复合主键，否则跨租户仍会回放结果或互相抢租约。
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff_result'
          AND index_name = 'PRIMARY'
          AND column_name = 'tenant_id'
    ) THEN
        ALTER TABLE cs_pending_handoff_result
            DROP PRIMARY KEY,
            ADD PRIMARY KEY (tenant_id, idempotency_key);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 'cs_pending_handoff_claim'
          AND index_name = 'PRIMARY'
          AND column_name = 'tenant_id'
    ) THEN
        ALTER TABLE cs_pending_handoff_claim
            DROP PRIMARY KEY,
            ADD PRIMARY KEY (tenant_id, idempotency_key);
    END IF;
END//

DELIMITER ;

CALL migrate_pending_handoff_tenant();
DROP PROCEDURE migrate_pending_handoff_tenant;

-- 验收查询：三项都应返回 0。
SELECT COUNT(*) AS pending_without_tenant
FROM cs_pending_handoff
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';

SELECT COUNT(*) AS result_without_tenant
FROM cs_pending_handoff_result
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';

SELECT COUNT(*) AS claim_without_tenant
FROM cs_pending_handoff_claim
WHERE tenant_id IS NULL OR TRIM(tenant_id) = '';
