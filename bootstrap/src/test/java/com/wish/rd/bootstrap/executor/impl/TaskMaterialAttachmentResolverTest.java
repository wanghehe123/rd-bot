package com.wish.rd.bootstrap.executor.impl;

import com.wish.rd.rag.ingestion.impl.InMemoryObjectStorageService;
import com.wish.rd.rag.runtime.impl.InMemoryTaskMaterialStore;
import com.wish.rd.rag.runtime.model.TaskMaterial;
import com.wish.rd.rag.runtime.model.TaskMaterialSourceType;
import com.wish.rd.rag.runtime.model.TaskMaterialType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TaskMaterialAttachmentResolverTest {
    @Test
    void shouldResolveObjectBackedMaterialBytes() {
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        byte[] bytes = new byte[]{1, 2, 3};
        String uri = storage.upload("rd-task-materials", new ByteArrayInputStream(bytes), bytes.length,
                "broken.png", "image/png").url();
        InMemoryTaskMaterialStore store = new InMemoryTaskMaterialStore();
        store.save(new TaskMaterial(
                "7482000000000000601", "7482000000000000602", TaskMaterialType.SCREENSHOT,
                TaskMaterialSourceType.LOCAL_UPLOAD, "截图", "local-upload://broken.png", "image/png",
                sha256(bytes), "图片附件", uri, "", "", "{\"filename\":\"broken.png\"}", 1, 1
        ));

        var attachments = new TaskMaterialAttachmentResolver(store, storage)
                .resolveByTask("7482000000000000602");

        assertEquals(1, attachments.size());
        assertEquals("7482000000000000601-broken.png", attachments.getFirst().filename());
        assertArrayEquals(bytes, attachments.getFirst().content());
    }

    @Test
    void shouldKeepSameNamedMaterialsAsDistinctWorkspaceAttachments() {
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        InMemoryTaskMaterialStore store = new InMemoryTaskMaterialStore();
        byte[] first = new byte[]{1, 2, 3};
        byte[] second = new byte[]{4, 5, 6};
        saveMaterial(store, storage, "7482000000000000611", first);
        saveMaterial(store, storage, "7482000000000000612", second);

        var attachments = new TaskMaterialAttachmentResolver(store, storage)
                .resolveByTask("7482000000000000699");

        assertEquals(2, attachments.size());
        assertNotEquals(attachments.get(0).filename(), attachments.get(1).filename());
        assertArrayEquals(first, attachments.get(0).content());
        assertArrayEquals(second, attachments.get(1).content());
    }

    @Test
    void shouldRejectObjectBytesThatDoNotMatchAuditedHash() {
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        byte[] bytes = new byte[]{1, 2, 3};
        String uri = storage.upload("rd-task-materials", new ByteArrayInputStream(bytes), bytes.length,
                "broken.png", "image/png").url();
        InMemoryTaskMaterialStore store = new InMemoryTaskMaterialStore();
        store.save(new TaskMaterial(
                "7482000000000000621", "7482000000000000699", TaskMaterialType.SCREENSHOT,
                TaskMaterialSourceType.LOCAL_UPLOAD, "截图", "local-upload://broken.png", "image/png",
                sha256(new byte[]{9}), "图片附件", uri, "", "", "{\"filename\":\"broken.png\"}", 1, 1));

        assertThrows(IllegalStateException.class,
                () -> new TaskMaterialAttachmentResolver(store, storage)
                        .resolveByTask("7482000000000000699"));
    }

    @Test
    void shouldRejectObjectBackedMaterialWithoutAuditedHash() {
        InMemoryObjectStorageService storage = new InMemoryObjectStorageService();
        byte[] bytes = new byte[]{1, 2, 3};
        String uri = storage.upload("rd-task-materials", new ByteArrayInputStream(bytes), bytes.length,
                "missing-hash.png", "image/png").url();
        InMemoryTaskMaterialStore store = new InMemoryTaskMaterialStore();
        store.save(new TaskMaterial(
                "7482000000000000631", "7482000000000000699", TaskMaterialType.SCREENSHOT,
                TaskMaterialSourceType.LOCAL_UPLOAD, "截图", "local-upload://missing-hash.png", "image/png",
                "", "图片附件", uri, "", "", "{\"filename\":\"missing-hash.png\"}", 1, 1));

        assertThrows(IllegalStateException.class,
                () -> new TaskMaterialAttachmentResolver(store, storage)
                        .resolveByTask("7482000000000000699"));
    }

    private static void saveMaterial(
            InMemoryTaskMaterialStore store,
            InMemoryObjectStorageService storage,
            String materialId,
            byte[] bytes
    ) {
        String uri = storage.upload("rd-task-materials", new ByteArrayInputStream(bytes), bytes.length,
                "same.png", "image/png").url();
        store.save(new TaskMaterial(
                materialId, "7482000000000000699", TaskMaterialType.SCREENSHOT,
                TaskMaterialSourceType.LOCAL_UPLOAD, "截图", "local-upload://same.png", "image/png",
                sha256(bytes), "图片附件", uri, "", "", "{\"filename\":\"same.png\"}", 1, 1));
    }

    private static String sha256(byte[] bytes) {
        try {
            return "sha256:" + java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
