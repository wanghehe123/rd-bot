package com.wish.rd.rag.text;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本分析工具：提供术语切分、重叠打分与文本合并，是检索与意图分类的底层基石。
 *
 * <p>MVP 未接入分词器或 Embedding，本类用规则化的"中英文混合切分 + n-gram + 词项重叠"
 * 实现一套可用的确定性打分：
 * <ul>
 *   <li>{@link #terms(String)}：把文本切成术语集合——ASCII 串按 `[a-z0-9_./-]+` 抽取，
 *       中文连续片段生成 2~5 元 gram，并用标点二次切分兜底；</li>
 *   <li>{@link #overlapScore(String, String)}：基于术语集合的重叠打分，长术语权重更高，
 *       命中方式分精确（1.0×）与子串包含（0.75×）两档；</li>
 *   <li>{@link #combined(String, Iterable)}：把描述与多条日志合并成统一查询文本。</li>
 * </ul>
 */
public final class TextAnalyzer {

    /** ASCII 术语（英文标识符、路径、URL 片段等）匹配模式。 */
    private static final Pattern ASCII_TERM = Pattern.compile("[a-z0-9_./-]+");
    /** 中文连续片段匹配模式，用于生成 n-gram。 */
    private static final Pattern HAN_SEQUENCE = Pattern.compile("\\p{IsHan}+");

    private TextAnalyzer() {
    }

    /**
     * 把文本切分为术语集合。
     *
     * <p>三种来源叠加：ASCII 术语、中文 n-gram、标点切分兜底，最终去重返回不可变集合。
     *
     * @param text 原始文本
     * @return 术语集合（小写），空文本返回空集合
     */
    public static Set<String> terms(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> terms = new LinkedHashSet<>();

        // 1. 抽取 ASCII 术语（接口名、字段名、路径等）
        Matcher ascii = ASCII_TERM.matcher(normalized);
        while (ascii.find()) {
            addIfUseful(terms, ascii.group());
        }

        // 2. 抽取中文片段并生成 n-gram
        Matcher han = HAN_SEQUENCE.matcher(normalized);
        while (han.find()) {
            addHanTerms(terms, han.group());
        }

        // 3. 用标点切分兜底，覆盖前两步可能遗漏的片段
        for (String part : normalized.split("[\\s,，。；;：:、|/()（）\\[\\]{}<>《》\"'`]+")) {
            addIfUseful(terms, part);
            if (containsHan(part)) {
                addHanTerms(terms, part);
            }
        }
        return Set.copyOf(terms);
    }

    /**
     * 计算查询与内容的术语重叠分：命中越多、术语越长，分数越高。
     *
     * <p>精确命中按权重满分计入；查询术语作为子串出现在内容中（且长度≥3）按 0.75 倍计分，
     * 用于奖励"部分匹配"。该分数被 {@link com.wish.rd.rag.vector.InMemoryVectorStore}
     * 用作向量/关键词检索的统一打分。
     *
     * @param query   查询文本
     * @param content 被检索的内容
     * @return 重叠得分，越大越相关；任一为空返回 0
     */
    public static double overlapScore(String query, String content) {
        Set<String> queryTerms = terms(query);
        Set<String> contentTerms = terms(content);
        if (queryTerms.isEmpty() || contentTerms.isEmpty()) {
            return 0.0d;
        }
        double score = 0.0d;
        String loweredContent = content == null ? "" : content.toLowerCase(Locale.ROOT);
        for (String queryTerm : queryTerms) {
            if (contentTerms.contains(queryTerm)) {
                // 精确命中：按术语长度权重满分
                score += weight(queryTerm);
            } else if (queryTerm.length() >= 3 && loweredContent.contains(queryTerm)) {
                // 子串包含命中：打折计分，鼓励但弱于精确命中
                score += weight(queryTerm) * 0.75d;
            }
        }
        return score;
    }

    /**
     * 把多段文本（描述 + 日志）合并成单一查询文本，用换行分隔，空片段跳过。
     *
     * @param first 首段文本（通常是描述）
     * @param rest  其余片段（通常是日志列表）
     * @return 合并后的查询文本
     */
    public static String combined(String first, Iterable<String> rest) {
        StringBuilder builder = new StringBuilder(first == null ? "" : first);
        if (rest != null) {
            for (String item : rest) {
                if (item != null && !item.isBlank()) {
                    builder.append('\n').append(item);
                }
            }
        }
        return builder.toString();
    }

    /**
     * 为中文片段生成术语：≤12 字整串保留 + 2~5 元 gram 全覆盖。
     * 整串保留保证短术语可精确命中，gram 覆盖保证子串也能被检索到。
     */
    private static void addHanTerms(Set<String> terms, String sequence) {
        if (sequence == null || sequence.length() < 2) {
            return;
        }
        // 短中文片段整串保留，便于精确匹配
        if (sequence.length() <= 12) {
            terms.add(sequence);
        }
        // 生成 2~5 元 gram，覆盖各种长度的子串
        int maxGram = Math.min(5, sequence.length());
        for (int gram = 2; gram <= maxGram; gram++) {
            for (int index = 0; index <= sequence.length() - gram; index++) {
                terms.add(sequence.substring(index, index + gram));
            }
        }
    }

    /** 仅保留长度≥2 的术语，过滤单字符噪声。 */
    private static void addIfUseful(Set<String> terms, String term) {
        if (term == null || term.length() < 2) {
            return;
        }
        terms.add(term);
    }

    /** 判断文本是否包含中文字符。 */
    private static boolean containsHan(String text) {
        return text != null && HAN_SEQUENCE.matcher(text).find();
    }

    /** 术语权重：≥6 字符 2.0 分，≥3 字符 1.4 分，其余 1.0 分。长术语代表更强信号。 */
    private static double weight(String term) {
        if (term.length() >= 6) {
            return 2.0d;
        }
        if (term.length() >= 3) {
            return 1.4d;
        }
        return 1.0d;
    }
}
