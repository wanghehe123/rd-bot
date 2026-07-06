package com.wish.rd.rag.ingestion.model;

import java.util.List;

public record IngestionTaskPage(
        List<ManagedIngestionTask> records,
        long total,
        int pageNo,
        int pageSize
) {

    public IngestionTaskPage {
        records = records == null ? List.of() : List.copyOf(records);
        pageNo = pageNo <= 0 ? 1 : pageNo;
        pageSize = pageSize <= 0 ? 10 : pageSize;
    }
}
