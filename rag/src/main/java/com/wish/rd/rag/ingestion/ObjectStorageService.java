package com.wish.rd.rag.ingestion;

import java.io.InputStream;
import com.wish.rd.rag.ingestion.model.StoredIngestionFile;

public interface ObjectStorageService {

    StoredIngestionFile upload(
            String bucketName,
            InputStream content,
            long size,
            String originalFilename,
            String contentType
    );

    InputStream openStream(String url);

    /**
     * Delete an object by its private storage URL.
     *
     * @return {@code true} when the object was deleted or the backend accepted the deletion
     */
    default boolean delete(String url) {
        throw new UnsupportedOperationException("object deletion is not supported");
    }
}
