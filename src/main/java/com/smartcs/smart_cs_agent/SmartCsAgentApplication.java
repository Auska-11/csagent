package com.smartcs.smart_cs_agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/*
 * DataSource 只能由 jdbc 条件配置创建。若保留 Boot 的通用 DataSource 自动配置，测试 classpath
 * 上的 H2（或未来其他嵌入式驱动）会让 memory 模式悄悄创建数据库连接池，破坏双模式边界。
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class SmartCsAgentApplication {

	public static void main(String[] args) {
		SpringApplication.run(SmartCsAgentApplication.class, args);
	}

}
