package com.wish.rd.bootstrap.controller.testchannel;

import com.wish.rd.engine.testflow.IngestionPipelineTestEngine;
import com.wish.rd.engine.testflow.IngestionPipelineTestResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 摄取默认管线测试通道控制器。
 *
 * <p>对外暴露 /test/ingestion/default-pipeline 接口，触发默认管线的端到端摄取
 * 冒烟测试，委托 {@link IngestionPipelineTestEngine} 执行并返回结果。
 */
@RestController
public class IngestionTestChannelController {

    @PostMapping("/test/ingestion/default-pipeline")
    public IngestionPipelineTestResult defaultPipeline() {
        return new IngestionPipelineTestEngine().runDefaultPipeline();
    }
}
