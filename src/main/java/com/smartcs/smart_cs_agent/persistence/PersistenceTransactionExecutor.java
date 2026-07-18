package com.smartcs.smart_cs_agent.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.function.Supplier;

/** 使用 Spring 事务管理器执行一组 Mapper 调用。 */
@Component
@ConditionalOnProperty(name = "smartcs.persistence.mode", havingValue = "jdbc")
public class PersistenceTransactionExecutor {

    private final TransactionTemplate transactionTemplate;

    public PersistenceTransactionExecutor(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = Objects.requireNonNull(
                transactionTemplate,
                "transactionTemplate cannot be null"
        );
    }

    public <T> T required(Supplier<T> work) {
        Objects.requireNonNull(work, "transaction work cannot be null");
        return transactionTemplate.execute(status -> work.get());
    }
}
