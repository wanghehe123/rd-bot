package com.wish.rd.rag.context;

import java.util.List;
import java.util.Optional;
import com.wish.rd.rag.context.model.RoleContextPackage;

/**
 * 角色上下文包存储端口。
 *
 * <p>供多 Agent 编排保存每个角色实际使用的证据包。生产实现应落 PostgreSQL，
 * 内存实现只用于开发和测试。
 */
public interface RoleContextPackageStore {

    /**
     * 保存上下文包。
     *
     * @param contextPackage 上下文包
     * @return 保存后的上下文包
     */
    RoleContextPackage save(RoleContextPackage contextPackage);

    /**
     * 按上下文包 ID 查询。
     *
     * @param packageId 上下文包 ID
     * @return 上下文包
     */
    Optional<RoleContextPackage> findById(String packageId);

    /**
     * 查询一个任务的全部角色上下文包。
     *
     * @param taskId RD 任务 ID
     * @return 上下文包列表
     */
    List<RoleContextPackage> listByTask(String taskId);

    /**
     * 查询一个任务和角色的全部上下文包版本。
     *
     * @param taskId RD 任务 ID
     * @param role   Agent 角色
     * @return 上下文包列表
     */
    List<RoleContextPackage> listByTaskAndRole(String taskId, String role);
}
