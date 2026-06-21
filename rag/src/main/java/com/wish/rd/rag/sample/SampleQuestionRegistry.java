package com.wish.rd.rag.sample;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;

public final class SampleQuestionRegistry {

    private static final int DEFAULT_WELCOME_LIMIT = 3;

    private final AtomicLong sequence = new AtomicLong(0);
    private final LinkedHashMap<String, ManagedSampleQuestion> questions = new LinkedHashMap<>();

    public static SampleQuestionRegistry inMemory() {
        return new SampleQuestionRegistry();
    }

    public synchronized ManagedSampleQuestion create(SampleQuestionCommand command) {
        validateCreate(command);
        long now = System.currentTimeMillis();
        String id = Long.toString(sequence.incrementAndGet());
        ManagedSampleQuestion question = new ManagedSampleQuestion(
                id,
                command.title(),
                command.description(),
                command.question(),
                now,
                now
        );
        questions.put(id, question);
        return question;
    }

    public synchronized ManagedSampleQuestion update(String id, SampleQuestionCommand command) {
        ManagedSampleQuestion existing = require(id);
        validateUpdate(command);
        ManagedSampleQuestion updated = new ManagedSampleQuestion(
                id,
                command.title() == null ? existing.title() : command.title(),
                command.description() == null ? existing.description() : command.description(),
                command.question() == null ? existing.question() : command.question(),
                existing.createTime(),
                System.currentTimeMillis()
        );
        questions.put(id, updated);
        return updated;
    }

    public synchronized ManagedSampleQuestion get(String id) {
        return require(id);
    }

    public synchronized SampleQuestionPage page(String keyword, int current, int size) {
        int safeCurrent = current <= 0 ? 1 : current;
        int safeSize = size <= 0 ? 10 : size;
        List<ManagedSampleQuestion> filtered = orderedQuestions().stream()
                .filter(question -> matches(question, keyword))
                .toList();
        int fromIndex = Math.min((safeCurrent - 1) * safeSize, filtered.size());
        int toIndex = Math.min(fromIndex + safeSize, filtered.size());
        return new SampleQuestionPage(filtered.subList(fromIndex, toIndex), filtered.size(), safeCurrent, safeSize);
    }

    public synchronized List<ManagedSampleQuestion> listWelcomeQuestions() {
        return orderedQuestions().stream()
                .limit(DEFAULT_WELCOME_LIMIT)
                .toList();
    }

    public synchronized void delete(String id) {
        require(id);
        questions.remove(id);
    }

    private List<ManagedSampleQuestion> orderedQuestions() {
        return questions.values().stream()
                .sorted(Comparator.comparingLong(ManagedSampleQuestion::updateTime).reversed()
                        .thenComparing(ManagedSampleQuestion::id, Comparator.reverseOrder()))
                .toList();
    }

    private ManagedSampleQuestion require(String id) {
        ManagedSampleQuestion question = questions.get(id);
        if (question == null) {
            throw new NoSuchElementException("sample question not found: " + id);
        }
        return question;
    }

    private void validateCreate(SampleQuestionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("sample question command must not be null");
        }
        if (command.question() == null || command.question().isBlank()) {
            throw new IllegalArgumentException("sample question must not be blank");
        }
    }

    private void validateUpdate(SampleQuestionCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("sample question command must not be null");
        }
        if (command.question() != null && command.question().isBlank()) {
            throw new IllegalArgumentException("sample question must not be blank");
        }
    }

    private boolean matches(ManagedSampleQuestion question, String keyword) {
        String normalized = normalize(keyword);
        if (normalized.isBlank()) {
            return true;
        }
        return normalize(question.title()).contains(normalized)
                || normalize(question.description()).contains(normalized)
                || normalize(question.question()).contains(normalized);
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
