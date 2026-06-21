package com.wish.rd.rag.ingestion;

public interface IngestionPipeline {

    void ingest(DocumentSource source);
}
