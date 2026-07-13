-- 客服 Agent 20.3：PendingHandoff 人工处置追加式审计表。
--
-- 使用场景：生产环境禁止应用账号执行 DDL 时，由数据库管理员在发布 20.3 代码前执行本脚本。
-- 该表只记录脚本执行后的人工动作。历史动作缺少可靠的 actor/reason/evidence，不能凭空回填。
--
-- 注意：IF NOT EXISTS 只能避免重复建表，不能证明同名旧表的字段和索引正确。
-- 若环境中已经存在同名表，仍需用 SHOW CREATE TABLE 手工核对下面的完整结构。

CREATE TABLE IF NOT EXISTS cs_pending_handoff_review_audit (
    audit_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    outbox_id VARCHAR(64) NOT NULL,
    action VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    authority_used VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    decision_note VARCHAR(256) NOT NULL,
    evidence_source VARCHAR(64) NOT NULL,
    from_status VARCHAR(32) NOT NULL,
    to_status VARCHAR(32) NOT NULL,
    from_version BIGINT NOT NULL,
    to_version BIGINT NOT NULL,
    review_failure_type VARCHAR(128) NOT NULL,
    attempt_count_at_decision INT NOT NULL,
    scheduled_at BIGINT NULL,
    occurred_at BIGINT NOT NULL,
    PRIMARY KEY (audit_id),
    UNIQUE KEY uk_cs_pending_handoff_review_audit_transition
        (tenant_id, outbox_id, to_version),
    INDEX idx_cs_pending_handoff_review_audit_page
        (tenant_id, outbox_id, occurred_at, audit_id)
);

-- 部署核对：输出实际结构，并确认当前没有被伪造的历史行。
SHOW CREATE TABLE cs_pending_handoff_review_audit;
SELECT COUNT(*) AS audit_row_count
FROM cs_pending_handoff_review_audit;
