package com.aresstack.enterpriseai.source.api.files;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Include and exclude rules of a file-tree source (local files #120, Git repositories #137), as glob patterns on the
 * path relative to the source root with {@code /} as separator: {@code *} matches within one name, {@code **}
 * across folders, {@code ?} one character. Like {@code .gitignore}, a pattern applies below any folder ({@code *.tmp}, {@code ~$*},
 * {@code node_modules/**}) unless a leading {@code /} ties it to the source folder; a pattern ending in {@code /**}
 * excludes a whole folder. Matching ignores letter case, like Windows. Without include patterns every indexable file
 * is included.
 */
public final class FilePatterns {

    private final List<Rule> includes;
    private final List<Rule> excludes;

    public FilePatterns(List<String> includes, List<String> excludes) {
        this.includes = compile(includes);
        this.excludes = compile(excludes);
    }

    /** Patterns from a setting: separated by commas, semicolons or line breaks. */
    public static List<String> split(String value) {
        List<String> patterns = new ArrayList<String>();
        if (value != null) {
            for (String part : value.split("[,;\\r\\n]+")) {
                if (!part.trim().isEmpty()) {
                    patterns.add(part.trim());
                }
            }
        }
        return patterns;
    }

    /** Whether a file (relative path) is indexed: included (or no includes) and not excluded. */
    public boolean acceptsFile(String relative) {
        if (excluded(relative)) {
            return false;
        }
        if (includes.isEmpty()) {
            return true;
        }
        for (Rule include : includes) {
            if (matches(include, relative)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a folder (relative path, empty for the source folder itself) is walked into. */
    public boolean entersFolder(String relative) {
        return relative.isEmpty() || !(excluded(relative) || excluded(relative + "/"));
    }

    private boolean excluded(String relative) {
        for (Rule exclude : excludes) {
            if (matches(exclude, relative)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matches(Rule rule, String relative) {
        if (rule.pattern.matcher(relative).matches()) {
            return true;
        }
        // An unanchored pattern also applies below any folder, like .gitignore.
        if (!rule.anchored) {
            for (int slash = relative.indexOf('/'); slash >= 0 && slash + 1 < relative.length();
                 slash = relative.indexOf('/', slash + 1)) {
                if (rule.pattern.matcher(relative.substring(slash + 1)).matches()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<Rule> compile(List<String> globs) {
        List<Rule> rules = new ArrayList<Rule>();
        for (String glob : globs) {
            String normalized = glob.replace('\\', '/');
            rules.add(new Rule(Pattern.compile(regex(normalized), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
                    normalized.startsWith("/")));
        }
        return Collections.unmodifiableList(rules);
    }

    private static String regex(String glob) {
        StringBuilder regex = new StringBuilder();
        String trimmed = glob.startsWith("/") ? glob.substring(1) : glob;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '*' && i + 2 < trimmed.length() && trimmed.charAt(i + 1) == '*' && trimmed.charAt(i + 2) == '/') {
                // "**/" also spans zero folders: **/*.pdf matches a.pdf, docs/**/x.md matches docs/x.md.
                regex.append("(?:.*/)?");
                i += 2;
            } else if (c == '*' && i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '*') {
                regex.append(".*");
                i++;
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    private static final class Rule {
        final Pattern pattern;
        /** A leading {@code /} ties the pattern to the source folder. */
        final boolean anchored;

        Rule(Pattern pattern, boolean anchored) {
            this.pattern = pattern;
            this.anchored = anchored;
        }
    }
}
