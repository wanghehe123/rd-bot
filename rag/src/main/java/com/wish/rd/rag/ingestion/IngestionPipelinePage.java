package com.wish.rd.rag.ingestion;

import java.util.List;

public record IngestionPipelinePage(
        List<ManagedIngestionPipeline> records,
        long total,
        int pageNo,
        int pageSize
) {

    public IngestionPipelinePage {
        records = records == null ? List.of() : List.copyOf(records);
        pageNo = pageNo <= 0 ? 1 : pageNo;
        pageSize = pageSize <= 0 ? 10 : pageSize;
    }
}
