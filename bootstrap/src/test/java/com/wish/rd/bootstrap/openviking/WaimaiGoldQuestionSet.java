package com.wish.rd.bootstrap.openviking;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * 外卖知识库评测题集。资源路径固定，避免评测跑到合成 fixture 上。
 *
 * @param corpusKnowledgeBaseId   选用的知识库
 * @param corpusKnowledgeBaseName 知识库名
 * @param corpusDocumentCount     文档数
 * @param corpusChoice            为何不用另外两个短文档 QA 库
 * @param k                       需求交付链路的 topK
 * @param questions               题目
 */
record WaimaiGoldQuestionSet(
        String corpusKnowledgeBaseId,
        String corpusKnowledgeBaseName,
        int corpusDocumentCount,
        String corpusChoice,
        int k,
        List<WaimaiGoldQuestion> questions
) {

    static final String RESOURCE = "/openviking-eval/waimai-gold-questions.json";

    static WaimaiGoldQuestionSet load() {
        try (InputStream input = WaimaiGoldQuestionSet.class.getResourceAsStream(RESOURCE)) {
            Objects.requireNonNull(input, "missing classpath resource " + RESOURCE);
            return new ObjectMapper().readValue(input, WaimaiGoldQuestionSet.class);
        } catch (Exception exception) {
            throw new IllegalStateException("failed to load " + RESOURCE, exception);
        }
    }
}
