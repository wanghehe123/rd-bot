package com.wish.rd.rag.ingestion;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class InMemoryObjectStorageService implements ObjectStorageService {

    private final Map<String, byte[]> files = new LinkedHashMap<>();

    @Override
    public synchronized StoredIngestionFile upload(
            String bucketName,
            InputStream content,
            long size,
            String originalFilename,
            String contentType
    ) {
        if (bucketName == null || bucketName.isBlank()) {
            throw new IllegalArgumentException("bucketName must not be blank");
        }
        try {
            byte[] bytes = content == null ? new byte[0] : content.readAllBytes();
            if (bytes.length == 0) {
                throw new IllegalArgumentException("upload file must not be empty");
            }
            String safeFilename = originalFilename == null || originalFilename.isBlank() ? "uploaded-file" : originalFilename;
            String url = "s3://" + bucketName + "/" + randomKey(safeFilename);
            files.put(url, bytes);
            return new StoredIngestionFile(url, detectType(safeFilename, contentType), bytes.length, safeFilename);
        } catch (Exception exception) {
            throw new IllegalStateException("upload file failed: " + exception.getMessage(), exception);
        }
    }

    @Override
    public synchronized InputStream openStream(String url) {
        byte[] bytes = files.get(url);
        if (bytes == null) {
            throw new IllegalArgumentException("stored file not found: " + url);
        }
        return new ByteArrayInputStream(bytes);
    }

    private String randomKey(String originalFilename) {
        String suffix = suffix(originalFilename);
        String key = UUID.randomUUID().toString().replace("-", "");
        return suffix.isBlank() ? key : key + "." + suffix;
    }

    private String suffix(String filename) {
        int index = filename.lastIndexOf('.');
        if (index < 0 || index == filename.length() - 1) {
            return "";
        }
        return filename.substring(index + 1).strip();
    }

    private String detectType(String filename, String contentType) {
        if (contentType != null && !contentType.isBlank()) {
            return contentType;
        }
        String normalized = filename.toLowerCase();
        if (normalized.endsWith(".md") || normalized.endsWith(".markdown")) {
            return "text/markdown";
        }
        return "text/plain";
    }
}
