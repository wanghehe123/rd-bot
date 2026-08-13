package com.wish.rd.rag.knowledge.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * OpenViking 投影 URI 与 ownership marker 的冻结构造器。
 *
 * <p>逻辑文档 URI 只使用数据库生成的数字 ID，不把名称、来源 URL 或 revision
 * 编进路径。合同测试必须落在 {@code wp0-contract} 专属根下，清理也只能删除该根内
 * 自己创建的 URI。
 */
public final class OpenVikingProjectionUris {

    public static final String OWNED_ROOT = "viking://resources/rd-bot/";
    public static final String CONTRACT_TEST_PREFIX = OWNED_ROOT + "wp0-contract/";

    public static final String SOURCE_FILE_NAME = "source.md";

    /** 按 URI 反查绑定时最多回溯的祖先级数，见 {@link #ancestorUrisInclusive}。 */
    public static final int MAX_ANCESTOR_LOOKUP = 12;

    private static final Pattern NUMERIC_ID = Pattern.compile("[1-9][0-9]*");
    private static final Pattern SAFE_SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");
    private static final Pattern MARKDOWN_FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*\\.md");

    private OpenVikingProjectionUris() {
    }

    /**
     * 构造 add_resource 的 {@code to} 资源根。v0.4.13 会把该 URI 建成目录。
     *
     * @param knowledgeBaseId 知识库数字 ID
     * @param documentId      逻辑文档数字 ID
     * @return {@code viking://resources/rd-bot/kb/{kbId}/documents/{docId}}
     */
    public static String documentRootUri(String knowledgeBaseId, String documentId) {
        String kbId = requireNumericId("knowledgeBaseId", knowledgeBaseId);
        String docId = requireNumericId("documentId", documentId);
        return OWNED_ROOT + "kb/" + kbId + "/documents/" + docId;
    }

    /**
     * 构造知识库专属根，带尾斜杠。
     *
     * @param knowledgeBaseId 知识库数字 ID
     * @return {@code viking://resources/rd-bot/kb/{kbId}/}
     */
    public static String knowledgeBaseRoot(String knowledgeBaseId) {
        String kbId = requireNumericId("knowledgeBaseId", knowledgeBaseId);
        return OWNED_ROOT + "kb/" + kbId + "/";
    }

    /**
     * 构造 L2 规范 Markdown 文件 URI。
     *
     * @param knowledgeBaseId 知识库数字 ID
     * @param documentId      逻辑文档数字 ID
     * @return {@code viking://resources/rd-bot/kb/{kbId}/documents/{docId}/source.md}
     */
    public static String documentSourceUri(String knowledgeBaseId, String documentId) {
        return documentRootUri(knowledgeBaseId, documentId) + "/" + SOURCE_FILE_NAME;
    }

    /**
     * 由资源根和上传文件名构造 L2 文件 URI。
     *
     * @param resourceRoot     add_resource 返回的 root_uri
     * @param uploadedFileName 上传时的文件名，合同测试固定为 {@code source.md}
     * @return L2 文件 URI
     */
    public static String l2ContentUri(String resourceRoot, String uploadedFileName) {
        String root = stripTrailingSlash(normalizeUri(resourceRoot));
        String fileName = requireMarkdownFileName(uploadedFileName);
        return root + "/" + fileName;
    }

    /**
     * 构造一次合同测试运行的专属根，带尾斜杠。
     *
     * @param runId 本次运行的安全单段标识
     * @return 测试根 URI
     */
    public static String contractTestRoot(String runId) {
        return CONTRACT_TEST_PREFIX + requireSafeSegment("runId", runId) + "/";
    }

    /**
     * 构造合同测试文档的 add_resource 根。
     *
     * @param runId      本次运行标识
     * @param documentId 测试文档数字 ID
     * @return 测试文档目录 URI
     */
    public static String contractTestDocumentRoot(String runId, String documentId) {
        String docId = requireNumericId("documentId", documentId);
        return stripTrailingSlash(contractTestRoot(runId)) + "/documents/" + docId;
    }

    /**
     * 构造合同测试文档的 L2 文件 URI。
     *
     * @param runId      本次运行标识
     * @param documentId 测试文档数字 ID
     * @return 测试文档 source.md URI
     */
    public static String contractTestDocumentUri(String runId, String documentId) {
        return contractTestDocumentRoot(runId, documentId) + "/" + SOURCE_FILE_NAME;
    }

    /**
     * 命中 URI 的自身及各级祖先路径，最长在前、已去掉尾斜杠。
     *
     * <p>检索命中经常落在文档根下的派生文件（{@code .abstract.md}、{@code source.md}），
     * 绑定只存文档根。用这组祖先做等值 {@code IN} 查询，才能走
     * {@code UNIQUE (provider, remote_uri)}，也避免 {@code LIKE remote_uri || '%'}
     * 把 {@code .../documents/12} 误当成 {@code .../documents/123} 的前缀。
     *
     * <p>空白、非法、或含空路径段的 URI 返回空列表，调用方应视为查无绑定。
     * 路径中的 {@code ..} 先按 {@link #isWithinOwnedRoot} 同一套规则规范化，
     * 再切祖先——否则裸字符串前缀会把穿越前的目录当成命中文档。
     *
     * <p>只保留最浅的 {@link #MAX_ANCESTOR_LOOKUP} 级。命中 URI 来自远端检索结果，
     * 深度由远端说了算，而这组祖先会直接变成 SQL 的 {@code IN} 绑定参数个数；
     * 不设上限就等于让远端决定一条语句有多大。绑定的 {@code remote_uri} 恒为
     * {@code viking://resources/rd-bot/kb/{kbId}/documents/{docId}} 这个固定深度
     * （真机 55 行实测最深 7 段），比它更深的祖先永远匹配不上任何绑定，
     * 因此裁掉深端不会漏掉任何真实命中。
     *
     * @param uri 远端命中 URI
     * @return 祖先列表，最长在前，无法规范化时为空
     */
    public static List<String> ancestorUrisInclusive(String uri) {
        if (uri == null || uri.isBlank()) {
            return List.of();
        }
        try {
            String current = stripTrailingSlash(canonicalizeUri(uri));
            if (current.isEmpty()) {
                return List.of();
            }
            ArrayList<String> ancestors = new ArrayList<>();
            int schemeEnd = "viking://".length() - 1;
            while (true) {
                ancestors.add(current);
                int slash = current.lastIndexOf('/');
                if (slash <= schemeEnd) {
                    break;
                }
                current = current.substring(0, slash);
            }
            if (ancestors.size() > MAX_ANCESTOR_LOOKUP) {
                // 深端裁掉：祖先按深→浅追加，能匹配绑定的固定深度落在浅端。
                return List.copyOf(ancestors.subList(
                        ancestors.size() - MAX_ANCESTOR_LOOKUP, ancestors.size()));
            }
            return List.copyOf(ancestors);
        } catch (IllegalArgumentException ex) {
            return List.of();
        }
    }

    /**
     * {@code remoteUri} 是否为 {@code hitUri} 的路径前缀（相等，或命中以 {@code remoteUri + '/'} 开头）。
     * 两侧都先规范化；非法 URI 或不构成路径边界的数字前缀视为不匹配。
     *
     * @param remoteUri 绑定上的文档根（或更长的已存 URI）
     * @param hitUri    远端命中 URI
     * @return 构成路径前缀时为 true
     */
    public static boolean isRemoteUriPrefixOf(String remoteUri, String hitUri) {
        if (remoteUri == null || remoteUri.isBlank()) {
            return false;
        }
        try {
            String canonicalRemote = stripTrailingSlash(canonicalizeUri(remoteUri));
            return ancestorUrisInclusive(hitUri).contains(canonicalRemote);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 判断 URI 是否位于指定 owned root 之下（含 root 自身）。
     *
     * @param uri       待检查 URI
     * @param ownedRoot 允许操作的根，通常带尾斜杠
     * @return 位于根内时为 true
     */
    public static boolean isWithinOwnedRoot(String uri, String ownedRoot) {
        try {
            String normalizedUri = canonicalizeUri(uri);
            String normalizedRoot = normalizeRoot(canonicalizeUri(ownedRoot));
            if (normalizedUri.isEmpty() || normalizedRoot.isEmpty()) {
                return false;
            }
            return normalizedUri.equals(stripTrailingSlash(normalizedRoot))
                    || normalizedUri.equals(normalizedRoot)
                    || normalizedUri.startsWith(normalizedRoot);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * 校验清理目标必须严格位于本次测试根内，禁止生产 KB 路径和父根。
     *
     * @param uri       准备删除的 URI
     * @param ownedRoot 本次测试根
     * @return 通过校验的 URI
     */
    public static String requireCleanupUri(String uri, String ownedRoot) {
        String normalizedUri = canonicalizeUri(uri);
        String normalizedRoot = canonicalizeUri(ownedRoot);
        if (isForbiddenCleanupRoot(normalizedUri) || isForbiddenCleanupRoot(normalizedRoot)) {
            throw new IllegalArgumentException("cleanup URI is a forbidden production root: " + uri);
        }
        if (!isWithinOwnedRoot(normalizedUri, normalizedRoot)) {
            throw new IllegalArgumentException("cleanup URI is outside owned root: " + uri);
        }
        if (normalizedUri.equals(stripTrailingSlash(normalizedRoot)) || normalizedUri.equals(normalizedRoot)) {
            return normalizedUri;
        }
        if (!normalizedUri.startsWith(normalizeRoot(normalizedRoot))) {
            throw new IllegalArgumentException("cleanup URI is outside owned root: " + uri);
        }
        return normalizedUri;
    }

    /**
     * 构造绑定到逻辑文档的 ownership marker。
     *
     * @param knowledgeBaseId 知识库数字 ID
     * @param documentId      逻辑文档数字 ID
     * @return {@code rd-bot:{kbId}:{docId}}
     */
    public static String ownershipMarker(String knowledgeBaseId, String documentId) {
        return "rd-bot:"
                + requireNumericId("knowledgeBaseId", knowledgeBaseId)
                + ":"
                + requireNumericId("documentId", documentId);
    }

    /**
     * 构造写入 OpenViking 的 ownership/version tags。checksum 必须是规范正文的
     * SHA-256，不能把这些 tag 自己算进去。
     *
     * @param knowledgeBaseId 知识库数字 ID
     * @param documentId      逻辑文档数字 ID
     * @param syncVersion     单调投影版本
     * @param checksum        规范正文 checksum
     * @return {@code k=v} 标签列表
     */
    public static List<String> ownershipTags(
            String knowledgeBaseId,
            String documentId,
            long syncVersion,
            String checksum
    ) {
        if (syncVersion <= 0L) {
            throw new IllegalArgumentException("syncVersion must be positive");
        }
        String digest = requireChecksum(checksum);
        return List.of(
                "rd.owner=rd-bot",
                "rd.kb_id=" + requireNumericId("knowledgeBaseId", knowledgeBaseId),
                "rd.doc_id=" + requireNumericId("documentId", documentId),
                "rd.sync_version=" + syncVersion,
                "rd.checksum=" + digest
        );
    }

    static String requireNumericId(String field, String value) {
        String normalized = requireNonBlank(field, value);
        if (!NUMERIC_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a database numeric id");
        }
        return normalized;
    }

    private static String requireSafeSegment(String field, String value) {
        String normalized = requireNonBlank(field, value);
        if (!SAFE_SEGMENT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a safe single path segment");
        }
        return normalized;
    }

    private static String requireMarkdownFileName(String value) {
        String normalized = requireNonBlank("uploadedFileName", value);
        if (!MARKDOWN_FILE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("uploadedFileName must be a safe markdown file name");
        }
        return normalized;
    }

    private static String requireChecksum(String checksum) {
        String normalized = requireNonBlank("checksum", checksum).toLowerCase(Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("checksum must be a 64-char sha-256 hex digest");
        }
        return normalized;
    }

    private static String canonicalizeUri(String uri) {
        String stripped = normalizeUri(uri);
        if (!stripped.regionMatches(true, 0, "viking://", 0, "viking://".length())) {
            throw new IllegalArgumentException("URI must use viking://");
        }
        if (stripped.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("URI must not contain backslash");
        }
        String decoded = percentDecodeRepeated(stripped);
        boolean trailingSlash = decoded.endsWith("/") && decoded.length() > "viking://".length();
        String rest = decoded.substring("viking://".length());
        if (trailingSlash) {
            rest = rest.substring(0, rest.length() - 1);
        }
        if (rest.contains("//")) {
            throw new IllegalArgumentException("URI must not contain empty path segments");
        }
        String[] raw = rest.split("/");
        ArrayList<String> resolved = new ArrayList<>();
        for (String segment : raw) {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("URI must not contain empty path segments");
            }
            if (".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (resolved.isEmpty()) {
                    throw new IllegalArgumentException("URI path traversal is not allowed");
                }
                resolved.remove(resolved.size() - 1);
                continue;
            }
            resolved.add(segment);
        }
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("URI path traversal is not allowed");
        }
        String joined = "viking://" + String.join("/", resolved);
        return trailingSlash ? joined + "/" : joined;
    }

    private static boolean isForbiddenCleanupRoot(String uri) {
        String normalized = stripTrailingSlash(uri);
        return "viking://resources".equals(normalized) || "viking://resources/rd-bot".equals(normalized);
    }

    private static String percentDecodeRepeated(String value) {
        String current = value;
        for (int attempt = 0; attempt < 4; attempt++) {
            String next = percentDecodeOnce(current);
            if (next.equals(current)) {
                return next;
            }
            current = next;
        }
        throw new IllegalArgumentException("URI encoding is not allowed");
    }

    private static String percentDecodeOnce(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high < 0 || low < 0) {
                    throw new IllegalArgumentException("URI contains invalid percent-encoding");
                }
                decoded.append((char) ((high << 4) + low));
                index += 2;
            } else {
                decoded.append(character);
            }
        }
        return decoded.toString();
    }

    private static String requireNonBlank(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        String normalized = value.strip();
        if (normalized.contains("/") || normalized.contains("\\") || normalized.contains("..")
                || normalized.contains(":") || normalized.contains("+")) {
            throw new IllegalArgumentException(field + " must not contain path or URI fragments");
        }
        return normalized;
    }

    private static String normalizeUri(String uri) {
        return Objects.requireNonNullElse(uri, "").strip();
    }

    private static String normalizeRoot(String ownedRoot) {
        String normalized = normalizeUri(ownedRoot);
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized.endsWith("/") ? normalized : normalized + "/";
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/") && value.length() > "viking://".length()) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
