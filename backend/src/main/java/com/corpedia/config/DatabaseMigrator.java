package com.corpedia.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 幂等 DDL 迁移（schema.sql 用 create table if not exists 无法给既有表加列）：
 * 阶段4 为 document 表补 department_id 列。启动时检查 information_schema，缺失则 ALTER 补齐。
 */
@Component
public class DatabaseMigrator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrator.class);

    private final JdbcTemplate jdbc;

    public DatabaseMigrator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        migrateAddDocumentDepartmentId();
        migrateAddMessageResponseMs();
        migrateAddDocumentCleanedText();
    }

    private void migrateAddDocumentDepartmentId() {
        String sql = """
                select count(*) from information_schema.columns
                where table_schema = database() and table_name = 'document' and column_name = 'department_id'
                """;
        Integer cnt = jdbc.queryForObject(sql, Integer.class);
        if (cnt != null && cnt > 0) {
            return;
        }
        jdbc.execute("alter table document add column department_id bigint null comment '文档级所属部门(可空=全司)'");
        log.info("[DatabaseMigrator] document.department_id 已补齐");
    }

    /** 阶段5: 为 message 表补 response_ms 列（统计 avgResponseMs）。 */
    private void migrateAddMessageResponseMs() {
        String sql = """
                select count(*) from information_schema.columns
                where table_schema = database() and table_name = 'message' and column_name = 'response_ms'
                """;
        Integer cnt = jdbc.queryForObject(sql, Integer.class);
        if (cnt != null && cnt > 0) {
            return;
        }
        jdbc.execute("alter table message add column response_ms bigint null comment 'ASSISTANT 行 RAG 生成耗时(ms)'");
        log.info("[DatabaseMigrator] message.response_ms 已补齐");
    }

    /** 功能扩展01: 为 document 表补 cleaned_text 列（引用溯源/原文高亮用）。 */
    private void migrateAddDocumentCleanedText() {
        String sql = """
                select count(*) from information_schema.columns
                where table_schema = database() and table_name = 'document' and column_name = 'cleaned_text'
                """;
        Integer cnt = jdbc.queryForObject(sql, Integer.class);
        if (cnt != null && cnt > 0) {
            return;
        }
        jdbc.execute("alter table document add column cleaned_text longtext null comment '清洗后全文(引用溯源/原文高亮用); 解析失败可为 null'");
        log.info("[DatabaseMigrator] document.cleaned_text 已补齐");
    }

    /** 占位：后续迁移在此追加（如新增表/索引），保持顺序执行。 */
    @SuppressWarnings("unused")
    private List<String> pendingMigrations() {
        return List.of();
    }
}
