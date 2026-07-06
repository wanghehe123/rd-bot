package com.wish.rd.rag.intent;

import java.util.ArrayList;
import java.util.List;
import com.wish.rd.rag.intent.model.IntentNode;

public final class IntentTree {

    private final List<IntentNode> roots;
    private final List<IntentNode> flattened;

    public IntentTree(List<IntentNode> roots) {
        this.roots = roots == null ? List.of() : List.copyOf(roots);
        this.flattened = flatten(this.roots);
    }

    public List<IntentNode> roots() {
        return roots;
    }

    public List<IntentNode> nodes() {
        return flattened;
    }

    private static List<IntentNode> flatten(List<IntentNode> nodes) {
        ArrayList<IntentNode> result = new ArrayList<>();
        for (IntentNode node : nodes) {
            result.add(node);
            result.addAll(flatten(node.children()));
        }
        return List.copyOf(result);
    }
}
