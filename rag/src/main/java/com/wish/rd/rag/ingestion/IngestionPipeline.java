package com.wish.rd.rag.ingestion;

import com.wish.rd.rag.ingestion.model.DocumentSource;


public interface IngestionPipeline {

    void ingest(DocumentSource source);
}
