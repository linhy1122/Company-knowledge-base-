package com.corpedia.dto.response;

/**
 * 原文上下文片段（功能扩展01：引用溯源/原文高亮）。highlight 为 chunk 区间原文，before/after 为前后窗口。
 */
public record DocContextVO(
        Long documentId,
        String filename,
        String before,
        String highlight,
        String after
) {
}