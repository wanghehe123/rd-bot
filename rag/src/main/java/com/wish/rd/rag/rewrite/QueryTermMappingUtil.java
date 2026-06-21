package com.wish.rd.rag.rewrite;

public final class QueryTermMappingUtil {

    private QueryTermMappingUtil() {
    }

    public static String applyMapping(String text, String sourceTerm, String targetTerm) {
        if (text == null || text.isEmpty() || sourceTerm == null || sourceTerm.isEmpty()) {
            return text;
        }
        StringBuilder builder = new StringBuilder();
        int index = 0;
        int sourceLength = sourceTerm.length();
        int targetLength = targetTerm == null ? 0 : targetTerm.length();
        while (index < text.length()) {
            int hit = text.indexOf(sourceTerm, index);
            if (hit < 0) {
                builder.append(text, index, text.length());
                break;
            }
            builder.append(text, index, hit);
            boolean alreadyTarget = targetTerm != null
                    && hit + targetLength <= text.length()
                    && text.startsWith(targetTerm, hit);
            if (alreadyTarget) {
                builder.append(text, hit, hit + targetLength);
                index = hit + targetLength;
            } else {
                builder.append(targetTerm);
                index = hit + sourceLength;
            }
        }
        return builder.toString();
    }
}
