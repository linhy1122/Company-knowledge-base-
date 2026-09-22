package com.corpedia.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识文档（表 document）。命名 KbDocument 以避免与 Spring AI 的 org.springframework.ai.document.Document 混淆。
 */
@Data
@TableName("document")
public class KbDocument {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long kbId;
    private String filename;
    private String filePath;
    private String fileHash;            // 文件内容 SHA-256，用于同库查重
    private String fileType;            // md/pdf/docx/txt
    private Long size;
    private String status;              // PARSING / READY / FAILED
    private Integer chunkCount;
    private String permissionLevel;     // 上传时继承所属知识库
    private Long departmentId;          // 文档级所属部门（上传时快照自知识库；权限设置可独立修改），可空=全司
    private Long uploadedBy;
    private String cleanedText;         // 功能扩展01: 清洗后全文(引用溯源/原文高亮用)，可空
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
