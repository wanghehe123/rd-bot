package com.wish.rd.exec.repair.execution.model;

/** Binary input mounted into an isolated repair workspace. */
public record RepairInputAttachment(String filename, String mimeType, byte[] content) {

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    public RepairInputAttachment {
        filename = filename == null ? "" : filename.strip();
        if (filename.isBlank()) {
            throw new IllegalArgumentException("attachment filename must not be blank");
        }
        mimeType = mimeType == null || mimeType.isBlank() ? "application/octet-stream" : mimeType.strip();
        content = content == null ? new byte[0] : content.clone();
        if (content.length == 0) {
            throw new IllegalArgumentException("attachment content must not be empty");
        }
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("attachment exceeds 10 MiB limit");
        }
    }

    @Override
    public byte[] content() {
        return content.clone();
    }
}
