package com.wish.rd.bootstrap.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

/**
 * RAG 检索评测日志配置。
 *
 * <p>由 {@link RagRetrievalLogConfiguration} 读取并装配文件日志 sink。
 */
@ConfigurationProperties(prefix = "rd.rag.retrieval-log")
public class RagRetrievalLogProperties {

    /** 默认评测日志路径。 */
    public static final Path DEFAULT_PATH = Path.of("logs/rag-retrieval.jsonl");

    private boolean enabled = false;
    private Path path = DEFAULT_PATH;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Path getPath() {
        return path;
    }

    public void setPath(Path path) {
        this.path = path == null ? DEFAULT_PATH : path;
    }
}
