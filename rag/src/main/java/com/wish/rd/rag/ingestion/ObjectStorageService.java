package com.wish.rd.rag.ingestion;

import java.io.InputStream;

public interface ObjectStorageService {

    StoredIngestionFile upload(
            String bucketName,
            InputStream content,
            long size,
            String originalFilename,
            String contentType
    );

    InputStream openStream(String url);
}
