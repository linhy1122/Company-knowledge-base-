package com.corpedia.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话（表 conversation）。某用户的一次对话线程。
 */
@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String title;
    private Long departmentId;          // 冗余用户当前部门（阶段4 权限用）
    private Integer status;             // 1 正常 / 0 已归档
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}