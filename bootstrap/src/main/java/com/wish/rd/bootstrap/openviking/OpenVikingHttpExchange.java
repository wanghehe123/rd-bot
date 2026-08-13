package com.wish.rd.bootstrap.openviking;

import com.wish.rd.bootstrap.openviking.model.OpenVikingResponse;

import java.util.Map;

/**
 * OpenViking HTTP 传输层。把它抽成接口是为了让适配器的协议判定可以脱离真实服务被测：
 * "2xx 但 root_uri 不一致""任务 completed 但队列有错"这类分支，在真实服务上很难稳定复现。
 */
public interface OpenVikingHttpExchange {

    OpenVikingResponse get(String path, Map<String, String> query);

    OpenVikingResponse postJson(String path, Object body);

    /**
     * 上传临时文件。这一步不触碰我们自己的资源根，失败永远是安全可重试的。
     *
     * @param fileName 上传文件名
     * @param content  文件字节
     * @return 响应
     */
    OpenVikingResponse uploadMarkdown(String fileName, byte[] content);
}
