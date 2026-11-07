package com.lanlink.shopping.module.task.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lanlink.shopping.module.task.entity.TaskRecord;

/**
 * 任务激励流水 Mapper（包路径命中 @MapperScan 的 com.lanlink.shopping.module.*.mapper 通配）。
 * 并发防重依赖 t_task_record 唯一键 uk_task_user_key_ref(user_id, task_key, ref_no)，
 * 服务层 insert 捕获 DuplicateKeyException 幂等返回，禁止先查后改作为唯一防线。
 */
public interface TaskRecordMapper extends BaseMapper<TaskRecord> {
}
