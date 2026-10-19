package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MVS path dialect for dataset and member addressing.
 *
 * <p>Übernommen aus corenth ({@code proasteion.emporion.holkas.mvs}).
 */
final class MvsPathDialect {

    public static final String MVS_ROOT = "''";

    public String toAbsolutePath(String path) {
        if (path == null) {
            return MVS_ROOT;
        }
        String trimmed = path.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return MVS_ROOT;
        }
        String normalized = trimmed.indexOf('/') >= 0 ? trimmed.replace('/', '.') : trimmed;
        return MvsQuoteNormalizer.normalize(normalized);
    }

    public boolean isMemberPath(String path) {
        if (path == null) {
            return false;
        }
        String unquoted = MvsQuoteNormalizer.unquote(path.trim());
        int open = unquoted.indexOf('(');
        int close = unquoted.lastIndexOf(')');
        return open > 0 && close == unquoted.length() - 1 && open < close;
    }

    public String[] splitMember(String path) {
        MvsMemberLocator member = MvsMemberLocator.parse(path);
        return new String[] { member.dataset().datasetName(), member.memberName() };
    }

    public String toMemberSpec(String dataset, String member) {
        String ds = dataset == null ? "" : MvsQuoteNormalizer.unquote(dataset).trim();
        String mem = member == null ? "" : member.trim();
        if (ds.isEmpty()) {
            return mem;
        }
        if (mem.isEmpty()) {
            return ds;
        }
        return ds + "(" + mem + ")";
    }

    public List<String> resolveCandidates(String path) {
        String trimmed = path == null ? "" : path.trim();
        if (trimmed.isEmpty() || "/".equals(trimmed)) {
            return Collections.singletonList(MVS_ROOT);
        }
        if (trimmed.startsWith("'") || isMemberPath(trimmed)) {
            return Collections.singletonList(toAbsolutePath(trimmed));
        }
        int lastDot = trimmed.lastIndexOf('.');
        if (lastDot > 0 && lastDot < trimmed.length() - 1) {
            List<String> candidates = new ArrayList<String>(2);
            // Erst das exakte Dataset, dann die Kurzform DS.MEMBER: entdeckte Member tragen schon DS(MEMBER).
            candidates.add(toAbsolutePath(trimmed));
            candidates.add(toAbsolutePath(toMemberSpec(trimmed.substring(0, lastDot), trimmed.substring(lastDot + 1))));
            return candidates;
        }
        return Collections.singletonList(toAbsolutePath(trimmed));
    }

    public String childOf(String parentAbsolutePath, String childName) {
        String parent = MvsQuoteNormalizer.unquote(parentAbsolutePath == null ? "" : parentAbsolutePath.trim());
        String child = childName == null ? "" : childName.trim();
        if (parent.isEmpty()) {
            return toAbsolutePath(child);
        }
        if (child.isEmpty()) {
            return toAbsolutePath(parent);
        }
        String unquotedChild = MvsQuoteNormalizer.unquote(child);
        if (unquotedChild.indexOf('.') >= 0 || unquotedChild.indexOf('(') >= 0) {
            return toAbsolutePath(unquotedChild);
        }
        if (parent.indexOf('.') < 0 && parent.indexOf('(') < 0) {
            return toAbsolutePath(parent + "." + unquotedChild);
        }
        if (isMemberLike(unquotedChild) && parent.indexOf('(') < 0) {
            return toAbsolutePath(toMemberSpec(parent, unquotedChild));
        }
        return toAbsolutePath(parent + "." + unquotedChild);
    }

    private boolean isMemberLike(String name) {
        if (name == null || name.isEmpty() || name.length() > 8) {
            return false;
        }
        if (name.indexOf('.') >= 0 || name.indexOf('(') >= 0 || name.indexOf(')') >= 0) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '@' && c != '#' && c != '$') {
                return false;
            }
        }
        return true;
    }
}
