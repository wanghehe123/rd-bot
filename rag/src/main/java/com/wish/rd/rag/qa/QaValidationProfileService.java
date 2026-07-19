package com.wish.rd.rag.qa;

import com.wish.rd.rag.qa.model.QaValidationProfile;
import com.wish.rd.rag.qa.model.QaValidationProfileCommand;

import java.util.Optional;

/** Resolves task override then project default for QA execution. */
public final class QaValidationProfileService {

    private final QaValidationProfileStore store;

    public QaValidationProfileService(QaValidationProfileStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        this.store = store;
    }

    public Optional<QaValidationProfile> getProject(String projectId) {
        return store.find("PROJECT", requireId(projectId, "projectId"));
    }

    public Optional<QaValidationProfile> getTask(String taskId) {
        return store.find("TASK", requireId(taskId, "taskId"));
    }

    public QaValidationProfile updateProject(String projectId, QaValidationProfileCommand command) {
        return update("PROJECT", requireId(projectId, "projectId"), command);
    }

    public QaValidationProfile updateTask(String taskId, QaValidationProfileCommand command) {
        return update("TASK", requireId(taskId, "taskId"), command);
    }

    public Resolution resolve(String taskId, String projectId) {
        String safeTaskId = text(taskId);
        if (!safeTaskId.isBlank()) {
            Optional<QaValidationProfile> task = store.find("TASK", safeTaskId);
            if (task.isPresent()) {
                return new Resolution("TASK_OVERRIDE", task);
            }
        }
        String safeProjectId = text(projectId);
        if (!safeProjectId.isBlank()) {
            Optional<QaValidationProfile> project = store.find("PROJECT", safeProjectId);
            if (project.isPresent()) {
                return new Resolution("PROJECT_PROFILE", project);
            }
        }
        return new Resolution("", Optional.empty());
    }

    private QaValidationProfile update(
            String scopeType,
            String scopeId,
            QaValidationProfileCommand command
    ) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        long now = System.currentTimeMillis();
        long createdAt = store.find(scopeType, scopeId)
                .map(QaValidationProfile::createTimeEpochMillis)
                .filter(value -> value > 0L)
                .orElse(now);
        return store.save(new QaValidationProfile(
                scopeType,
                scopeId,
                command.mode(),
                command.baseUrl(),
                command.startCommand(),
                command.healthPath(),
                command.allowedHosts(),
                command.regressionCommands(),
                createdAt,
                now
        ));
    }

    private static String requireId(String value, String fieldName) {
        String normalized = text(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    public record Resolution(String source, Optional<QaValidationProfile> profile) {
        public Resolution {
            source = source == null ? "" : source.strip();
            profile = profile == null ? Optional.empty() : profile;
        }
    }
}
