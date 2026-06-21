package com.wish.rd.engine.admin.sample;

import com.wish.rd.rag.sample.ManagedSampleQuestion;
import com.wish.rd.rag.sample.SampleQuestionCommand;
import com.wish.rd.rag.sample.SampleQuestionPage;
import com.wish.rd.rag.sample.SampleQuestionRegistry;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 样例问题管理业务编排引擎。
 *
 * <p>委托 {@link SampleQuestionRegistry} 完成样例问题的增删改查、分页与欢迎问题列表；
 * 当未注入 registry 时回退到内存实现。供 {@code SampleQuestionController} 调用。
 */
@Service
public final class SampleQuestionAdminEngine {

    private final SampleQuestionRegistry registry;

    public SampleQuestionAdminEngine(SampleQuestionRegistry registry) {
        this.registry = registry == null ? SampleQuestionRegistry.inMemory() : registry;
    }

    public ManagedSampleQuestion create(SampleQuestionCommand command) {
        return registry.create(command);
    }

    public ManagedSampleQuestion update(String id, SampleQuestionCommand command) {
        return registry.update(id, command);
    }

    public ManagedSampleQuestion get(String id) {
        return registry.get(id);
    }

    public SampleQuestionPage page(String keyword, int current, int size) {
        return registry.page(keyword, current, size);
    }

    public List<ManagedSampleQuestion> listWelcomeQuestions() {
        return registry.listWelcomeQuestions();
    }

    public void delete(String id) {
        registry.delete(id);
    }
}
