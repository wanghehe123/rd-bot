package com.wish.rd.rag.retrieval;

import com.wish.rd.adapter.CodeRepositorySearchPort;
import com.wish.rd.adapter.CodeSearchQuery;
import com.wish.rd.framework.convention.RetrievedChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码仓库检索通道。
 *
 * <p>仅当注入了代码端口、命中主意图且意图绑定了代码仓库时启用。
 * 遍历意图绑定的每个仓库，通过 {@link CodeRepositorySearchPort} 拉取相关代码片段，
 * 拼成该通道的结果集。代码片段的 knowledgeType 由端口实现决定（通常为 code-snippet）。
 */
public final class CodeRepositorySearchChannel implements SearchChannel {

    private final CodeRepositorySearchPort codeRepositorySearchPort;

    public CodeRepositorySearchChannel(CodeRepositorySearchPort codeRepositorySearchPort) {
        this.codeRepositorySearchPort = codeRepositorySearchPort;
    }

    @Override
    public String name() {
        return "CodeRepositorySearch";
    }

    /** 启用条件：代码端口存在、命中意图且意图绑定了至少一个代码仓库。 */
    @Override
    public boolean isEnabled(RetrievalRequest request) {
        return codeRepositorySearchPort != null
                && request.primaryIntent().isPresent()
                && !request.targetCodeRepositoryIds().isEmpty();
    }

    @Override
    public ChannelSearchResult search(RetrievalRequest request) {
        ArrayList<RetrievedChunk> chunks = new ArrayList<>();
        // 逐仓库检索并合并；具体打分与片段包装由端口实现负责
        for (String repositoryId : request.targetCodeRepositoryIds()) {
            chunks.addAll(codeRepositorySearchPort.search(new CodeSearchQuery(
                    repositoryId,
                    request.query(),
                    List.of(),
                    request.topK()
            )));
        }
        return new ChannelSearchResult(name(), chunks);
    }
}
