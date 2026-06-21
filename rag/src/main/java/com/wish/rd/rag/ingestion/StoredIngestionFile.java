package com.wish.rd.rag.ingestion;

public record StoredIngestionFile(
        String url,
        String detectedType,
        long size,
        String originalFilename
) {

    public StoredIngestionFile {
        url = url == null ? "" : url;
        detectedType = detectedType == null ? "" : detectedType;
        originalFilename = originalFilename == null || originalFilename.isBlank() ? "uploaded-file" : originalFilename;
    }
}
