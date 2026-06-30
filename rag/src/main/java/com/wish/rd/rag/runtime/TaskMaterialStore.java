package com.wish.rd.rag.runtime;

import java.util.List;
import java.util.Optional;

/**
 * 任务材料持久化端口。
 */
public interface TaskMaterialStore {

    /**
     * 保存任务材料。
     *
     * @param material 材料快照
     * @return 保存后的材料
     */
    TaskMaterial save(TaskMaterial material);

    /**
     * 按材料 ID 查询。
     *
     * @param materialId 材料 ID
     * @return 材料快照
     */
    Optional<TaskMaterial> findById(String materialId);

    /**
     * 查询某任务的全部材料。
     *
     * @param taskId 任务 ID
     * @return 材料列表
     */
    List<TaskMaterial> listByTask(String taskId);

    /**
     * 删除某任务的全部材料。
     *
     * @param taskId 任务 ID
     * @return 删除数量
     */
    int deleteByTask(String taskId);
}
