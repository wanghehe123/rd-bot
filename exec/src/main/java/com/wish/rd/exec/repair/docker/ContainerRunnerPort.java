package com.wish.rd.exec.repair.docker;

import java.io.IOException;

/**
 * 容器执行端口，供后续 Docker Claude Code 执行器同步运行一次容器任务。
 */
@FunctionalInterface
public interface ContainerRunnerPort {

    /**
     * 同步运行容器请求，返回时容器进程已经结束且可读取输出产物。
     *
     * @param request 容器执行请求
     * @return 容器退出结果和标准协议产物引用
     * @throws IOException 容器运行或产物写入失败
     */
    ContainerRunResult run(ContainerRunRequest request) throws IOException;
}
