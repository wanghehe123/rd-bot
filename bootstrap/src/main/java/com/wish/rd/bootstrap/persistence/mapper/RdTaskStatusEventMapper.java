package com.wish.rd.bootstrap.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wish.rd.bootstrap.persistence.entity.RdTaskStatusEventRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * RD 任务状态事件 MyBatis-Plus mapper。
 *
 * <p>供 PostgreSQL 状态事件存储适配器执行追加、按任务查询与清理。
 */
@Mapper
public interface RdTaskStatusEventMapper extends BaseMapper<RdTaskStatusEventRow> {

    /**
     * 按任务 ID 查询全部事件，按进入时间升序。
     *
     * @param taskId 任务 ID
     * @return 事件行列表（升序）
     */
    @Select("SELECT * FROM rd_task_status_events WHERE task_id = #{taskId} ORDER BY entered_at ASC, id ASC")
    List<RdTaskStatusEventRow> listByTask(@Param("taskId") Long taskId);

    /**
     * 删除某任务的全部事件（删除任务时清理）。
     *
     * @param taskId 任务 ID
     * @return 删除条数
     */
    @Delete("DELETE FROM rd_task_status_events WHERE task_id = #{taskId}")
    int deleteByTask(@Param("taskId") Long taskId);
}
