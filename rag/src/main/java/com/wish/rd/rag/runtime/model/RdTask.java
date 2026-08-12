package com.wish.rd.rag.runtime.model;


/**
 * RD 机器人任务父类型。
 *
 * <p>供运行时状态机、任务查询接口和持久化适配器读取所有任务共有字段。
 */
public interface RdTask {

    /**
     * 返回任务 ID。
     *
     * @return Snowflake 字符串 ID
     */
    String taskId();

    /**
     * 返回任务类型。
     *
     * @return 任务类型标识
     */
    String taskType();

    /**
     * 返回任务状态。
     *
     * @return 当前状态机节点
     */
    RdTaskStatus status();

    /**
     * 返回优先级。
     *
     * @return P0/P1/P2 等优先级
     */
    String priority();

    /**
     * 返回任务标题。
     *
     * @return 展示标题
     */
    String title();

    /**
     * 返回错误或打回原因。
     *
     * @return 错误信息
     */
    String errorMessage();

    /**
     * 返回创建时间。
     *
     * @return epoch millis
     */
    long createTimeEpochMillis();

    /**
     * 返回更新时间。
     *
     * @return epoch millis
     */
    long updateTimeEpochMillis();

    /**
     * Returns the optimistic concurrency version persisted with this snapshot.
     *
     * @return non-negative snapshot version
     */
    default long version() {
        return 0L;
    }

    /**
     * Returns the monotonic fencing token carried by this snapshot.
     *
     * @return non-negative fencing token
     */
    default long fencingToken() {
        return 0L;
    }
}
