package com.corpedia.common;

/**
 * 业务常量：文档处理状态、权限级别（含 Milvus 数值映射）、Milvus metadata 键。
 */
public final class Constants {

    private Constants() {
    }

    // 文档处理状态（document.status）
    public static final String DOC_PARSING = "PARSING";
    public static final String DOC_READY = "READY";
    public static final String DOC_FAILED = "FAILED";

    // 知识库/文档权限级别（字符串，落 MySQL）
    public static final String LV_PUBLIC = "PUBLIC";
    public static final String LV_DEPT = "DEPT";
    public static final String LV_CONFIDENTIAL = "CONFIDENTIAL";

    /**
     * 权限级别 -> Milvus 整型（PUBLIC=0 / DEPT=1 / CONFIDENTIAL=2），数值比较规避字符串语义不确定性。
     */
    public static int permissionLevelToInt(String level) {
        if (level == null) {
            return 0;
        }
        return switch (level) {
            case LV_CONFIDENTIAL -> 2;
            case LV_DEPT -> 1;
            default -> 0;
        };
    }

    // Milvus metadata JSON 子字段键（集合 knowledge_chunks）
    public static final String META_DOCUMENT_ID = "document_id";
    public static final String META_TITLE = "title";
    public static final String META_DEPARTMENT_ID = "department_id";
    public static final String META_PERMISSION_LEVEL = "permission_level";
    public static final String META_CATEGORY = "category";
    public static final String META_SOURCE = "source";
    // 功能扩展01: chunk 在原文清洗后文本中的字符区间（引用溯源/原文高亮定位）
    public static final String META_CHUNK_START = "chunk_start";
    public static final String META_CHUNK_END = "chunk_end";
}
