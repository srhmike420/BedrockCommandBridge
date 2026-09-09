package com.flamebourne.bedrockcommandbridge;

import java.util.*;

final class CommandRules {
    private CommandRules() {}

    static Set<String> rootsFromEntries(Collection<String> entries) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (String raw : entries) {
            if (raw == null) continue;
            String entry = raw.trim();
            if (entry.isEmpty() || entry.regionMatches(true, 0, "plugin:", 0, 7)) continue;
            if (entry.equals("*")) {
                roots.clear();
                roots.add("*");
                return Collections.unmodifiableSet(roots);
            }
            if (entry.charAt(0) == '/') entry = entry.substring(1);
            int space = entry.indexOf(' ');
            String root = (space >= 0 ? entry.substring(0, space) : entry).trim().toLowerCase(Locale.ROOT);
            if (!root.isEmpty() && !root.equals("*")) roots.add(root);
        }
        return Collections.unmodifiableSet(roots);
    }

    static Set<String> resolveInherited(String group, Map<String, GroupDef> groups) {
        LinkedHashSet<String> entries = new LinkedHashSet<>();
        resolve(group, groups, entries, new HashSet<>());
        return rootsFromEntries(entries);
    }

    private static void resolve(String group, Map<String, GroupDef> groups, Set<String> entries, Set<String> visiting) {
        if (!visiting.add(group)) return;
        GroupDef def = groups.get(group);
        if (def == null) return;
        for (String parent : def.inherited()) resolve(parent, groups, entries, visiting);
        entries.addAll(def.tabcomplete());
        visiting.remove(group);
    }

    record GroupDef(int priority, List<String> inherited, List<String> tabcomplete) {}
}
