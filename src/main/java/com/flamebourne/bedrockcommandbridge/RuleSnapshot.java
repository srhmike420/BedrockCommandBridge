package com.flamebourne.bedrockcommandbridge;

import java.util.Set;

record RuleSnapshot(String group, Set<String> allowedRoots, boolean passthrough, String source) {
    static RuleSnapshot passthrough(String source) {
        return new RuleSnapshot("passthrough", Set.of("*"), true, source);
    }

    boolean allows(String command) {
        return passthrough || allowedRoots.contains("*") || allowedRoots.contains(command.toLowerCase(java.util.Locale.ROOT));
    }
}
