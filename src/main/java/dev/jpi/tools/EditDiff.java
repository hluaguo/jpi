package dev.jpi.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Pure text-matching core of the edit tool, ported from pi's edit-diff.ts.
 *
 * <p><em>Why this exists as a separate class:</em> matching model-supplied
 * {@code oldText} against file bytes is where editing goes wrong (CRLF files,
 * fuzzy whitespace, overlapping edits) — keeping it pure means the failure modes
 * are testable without touching the filesystem. Error message wording is pi's,
 * verbatim: models learn to read these. The line diff is a hand-rolled Myers
 * because jpi carries no third-party diff dependency.
 */
public final class EditDiff {

    /** The line ending {@code content} predominantly uses: CRLF only if a {@code \r\n} precedes every lone {@code \n}. */
    public static String detectLineEnding(String content) {
        int crlf = content.indexOf("\r\n");
        int lf = content.indexOf("\n");
        if (lf == -1 || crlf == -1) {
            return "\n";
        }
        return crlf < lf ? "\r\n" : "\n";
    }

    /** Normalize CRLF and lone CR to LF. */
    public static String normalizeToLF(String text) {
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Re-apply {@code ending} to LF-normalized text. */
    public static String restoreLineEndings(String text, String ending) {
        return "\r\n".equals(ending) ? text.replace("\n", "\r\n") : text;
    }

    /** A fuzzy-match outcome: {@code found} says whether {@code oldText} matched; the rest locate the match. */
    public record FuzzyMatchResult(
            boolean found,
            int index,
            int matchLength,
            boolean usedFuzzyMatch,
            String contentForReplacement) {
    }

    /** A single exact-text replacement. */
    public record Edit(String oldText, String newText) {
    }

    /** The result of applying edits to LF-normalized content: {@code baseContent} is what diffs are computed against. */
    public record AppliedEdits(String baseContent, String newContent) {
    }

    /** BOM and the text under it, so the BOM can be re-prefixed after editing. */
    public record StripBom(String bom, String text) {
    }

    /** Display diff plus the first changed line in the new file ({@code null} when identical). */
    public record DiffString(String diff, Integer firstChangedLine) {
    }

    /** Strip a leading UTF-8 BOM ({@code \uFEFF}) if present. */
    public static StripBom stripBom(String content) {
        return content.startsWith("\uFEFF")
                ? new StripBom("\uFEFF", content.substring(1))
                : new StripBom("", content);
    }

    /*
     * Progressive normalization for fuzzy matching: map the Unicode look-alikes
     * models habitually emit (curly quotes, en/em dashes, NBSP and friends) back
     * to their ASCII twins, then drop per-line trailing whitespace. Normalizing
     * before the whitespace strip matters: a trailing NBSP is trailing whitespace
     * only after it becomes a space. Single pass, line by line — a multiline
     * regex here costs an order of magnitude more than the mapping itself.
     */
    public static String normalizeForFuzzyMatch(String text) {
        int n = text.length();
        StringBuilder sb = new StringBuilder(n);
        int i = 0;
        while (i < n) {
            int lineEnd = text.indexOf('\n', i);
            int end = lineEnd == -1 ? n : lineEnd;
            int lastKept = sb.length();  // end of the last non-[ \t] char on this line
            for (int j = i; j < end; j++) {
                char c = text.charAt(j);
                char mapped = switch (c) {
                    case '\u2018', '\u2019' -> '\'';
                    case '\u201C', '\u201D' -> '"';
                    case '\u2010', '\u2011', '\u2012', '\u2013', '\u2014', '\u2015', '\u2212' -> '-';
                    case '\u00A0', '\u2000', '\u2001', '\u2002', '\u2003', '\u2004', '\u2005',
                         '\u2006', '\u2007', '\u2008', '\u2009', '\u200A', '\u202F', '\u205F', '\u3000' -> ' ';
                    default -> c;
                };
                sb.append(mapped);
                if (mapped != ' ' && mapped != '\t') {
                    lastKept = sb.length();
                }
            }
            sb.setLength(lastKept);
            if (lineEnd != -1) {
                sb.append('\n');
            }
            i = end + 1;
        }
        return sb.toString();
    }

    /** Find {@code oldText} in {@code content}: exact match first, then a match in fuzzy-normalized space. */
    public static FuzzyMatchResult fuzzyFindText(String content, String oldText) {
        return fuzzyFindText(content, oldText, null);
    }

    /*
     * preNormalizedContent (when non-null) is content already passed through
     * normalizeForFuzzyMatch — callers that normalized a base once for a whole
     * edit batch skip the per-edit full pass. Normalization is idempotent, so
     * this only removes redundant work, never changes the result.
     */
    static FuzzyMatchResult fuzzyFindText(String content, String oldText, String preNormalizedContent) {
        int exact = content.indexOf(oldText);
        if (exact >= 0) {
            return new FuzzyMatchResult(true, exact, oldText.length(), false, content);
        }
        String normalizedContent = preNormalizedContent != null ? preNormalizedContent : normalizeForFuzzyMatch(content);
        String normalizedOldText = normalizeForFuzzyMatch(oldText);
        int fuzzy = normalizedContent.indexOf(normalizedOldText);
        if (fuzzy >= 0) {
            return new FuzzyMatchResult(true, fuzzy, normalizedOldText.length(), true, normalizedContent);
        }
        return new FuzzyMatchResult(false, -1, 0, false, content);
    }

    /**
     * Apply one or more exact-text replacements to LF-normalized content.
     *
     * <p>All edits are matched against the same original content, then applied in
     * reverse order so offsets stay stable. If any edit needs fuzzy matching, the
     * operation runs in fuzzy-normalized content space and overlays those
     * line-level changes onto the original so unchanged line blocks keep their
     * original bytes.
     */
    public static AppliedEdits applyEditsToNormalizedContent(String normalizedContent, List<Edit> edits, String path) {
        List<Edit> normalized = new ArrayList<>();
        for (int i = 0; i < edits.size(); i++) {
            Edit edit = edits.get(i);
            if (edit.oldText() == null || edit.oldText().isEmpty()) {
                throw new IllegalArgumentException(emptyOldTextMessage(edits, i, path));
            }
            normalized.add(new Edit(normalizeToLF(edit.oldText()), normalizeToLF(edit.newText())));
        }

        // First pass: does any edit miss an exact match? That moves the whole
        // operation into fuzzy-normalized space so every match index is consistent.
        boolean anyNeedsFuzzy = false;
        for (Edit edit : normalized) {
            FuzzyMatchResult r = fuzzyFindText(normalizedContent, edit.oldText());
            if (!r.found() || r.usedFuzzyMatch()) {
                anyNeedsFuzzy = true;
                break;
            }
        }
        String replacementBase = anyNeedsFuzzy
                ? normalizeForFuzzyMatch(normalizedContent)
                : normalizedContent;

        List<int[]> matches = new ArrayList<>();  // {matchIndex, matchLength, editIndex}
        List<String> newTexts = new ArrayList<>();
        String uniquenessBase = null;  // normalized space, computed at most once for the batch
        for (int i = 0; i < normalized.size(); i++) {
            Edit edit = normalized.get(i);
            FuzzyMatchResult result = fuzzyFindText(replacementBase, edit.oldText(), replacementBase);
            if (!result.found()) {
                throw new IllegalArgumentException(notFoundMessage(edits, i, path));
            }
            if (uniquenessBase == null) {
                uniquenessBase = anyNeedsFuzzy ? replacementBase : normalizeForFuzzyMatch(replacementBase);
            }
            int occurrences = countOccurrences(uniquenessBase, edit.oldText());
            if (occurrences > 1) {
                throw new IllegalArgumentException(duplicateMessage(edits, i, path, occurrences));
            }
            matches.add(new int[] {result.index(), result.matchLength(), i});
            newTexts.add(edit.newText());
        }
        matches.sort((x, y) -> Integer.compare(x[0], y[0]));

        for (int i = 1; i < matches.size(); i++) {
            int[] prev = matches.get(i - 1);
            int[] cur = matches.get(i);
            if (prev[0] + prev[1] > cur[0]) {
                throw new IllegalArgumentException("edits[" + prev[2] + "] and edits[" + cur[2]
                        + "] overlap in " + path + ". Merge them into one edit or target disjoint regions.");
            }
        }

        StringBuilder applied = new StringBuilder(replacementBase);
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] m = matches.get(i);
            applied.replace(m[0], m[0] + m[1], newTexts.get(m[2]));
        }
        String newContent = anyNeedsFuzzy
                ? applyReplacementsPreservingUnchangedLines(normalizedContent, replacementBase, matches, newTexts)
                : applied.toString();

        if (newContent.equals(normalizedContent)) {
            throw new IllegalArgumentException(noChangeMessage(edits, path));
        }
        return new AppliedEdits(normalizedContent, newContent);
    }

    /*
     * When matching ran in fuzzy-normalized space, unchanged lines must not come
     * back normalized — they keep their original bytes. Every replacement is
     * widened to the lines it touches; touched lines are rewritten from the
     * normalized base, all others are copied back from the original. Duplicate
     * normalized lines can't align to the wrong occurrence because the
     * replacement ranges, not the text, drive the choice.
     */
    private static String applyReplacementsPreservingUnchangedLines(
            String originalContent, String baseContent, List<int[]> matches, List<String> newTexts) {
        String[] originalLines = originalContent.split("\\n", -1);
        String[] baseLines = baseContent.split("\\n", -1);

        boolean[] touched = new boolean[baseLines.length];
        int offset = 0;
        for (int i = 0; i < baseLines.length; i++) {
            int lineStart = offset;
            int lineEnd = offset + baseLines[i].length();
            for (int[] m : matches) {
                if (m[0] < lineEnd && m[0] + m[1] > lineStart) {
                    touched[i] = true;
                }
            }
            offset = lineEnd + 1;
        }

        StringBuilder applied = new StringBuilder(baseContent);
        for (int i = matches.size() - 1; i >= 0; i--) {
            int[] m = matches.get(i);
            applied.replace(m[0], m[0] + m[1], newTexts.get(m[2]));
        }
        String[] rewrittenBase = applied.toString().split("\\n", -1);

        StringBuilder out = new StringBuilder(originalContent.length());
        for (int i = 0; i < originalLines.length; i++) {
            out.append(touched[i] ? rewrittenBase[i] : originalLines[i]);
            if (i < originalLines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /* Counts needle occurrences in already-normalized content; only the needle needs normalizing. */
    private static int countOccurrences(String normalizedContent, String oldText) {
        String needle = normalizeForFuzzyMatch(oldText);
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = normalizedContent.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static boolean singleEdit(List<Edit> edits) {
        return edits.size() == 1;
    }

    private static String emptyOldTextMessage(List<Edit> edits, int index, String path) {
        return singleEdit(edits)
                ? "oldText must not be empty in " + path + "."
                : "edits[" + index + "].oldText must not be empty in " + path + ".";
    }

    private static String notFoundMessage(List<Edit> edits, int index, String path) {
        return singleEdit(edits)
                ? "Could not find the exact text in " + path + ". The old text must match exactly including all whitespace and newlines."
                : "Could not find edits[" + index + "] in " + path + ". The oldText must match exactly including all whitespace and newlines.";
    }

    private static String duplicateMessage(List<Edit> edits, int index, String path, int occurrences) {
        return singleEdit(edits)
                ? "Found " + occurrences + " occurrences of the text in " + path + ". The text must be unique. Please provide more context to make it unique."
                : "Found " + occurrences + " occurrences of edits[" + index + "] in " + path + ". Each oldText must be unique. Please provide more context to make it unique.";
    }

    private static String noChangeMessage(List<Edit> edits, String path) {
        return singleEdit(edits)
                ? "No changes made to " + path + ". The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected."
                : "No changes made to " + path + ". The replacements produced identical content.";
    }

    // ------------------------------------------------------------------ diffs

    /** One changed region in old/new line-index space ({@code count} may be 0 for pure insert/delete). */
    record LineChange(int delStart, int delCount, int addStart, int addCount) {
    }

    /** One run in the line diff: unchanged context, or a removed/added side of a change site. */
    record DiffPart(boolean added, boolean removed, List<String> lines) {
        static DiffPart equal(List<String> lines) {
            return new DiffPart(false, false, lines);
        }
    }

    /** Generate a standard unified patch (4 context lines, GNU header/count conventions). */
    public static String generateUnifiedPatch(String path, String oldContent, String newContent) {
        return generateUnifiedPatch(path, oldContent, newContent, 4);
    }

    public static String generateUnifiedPatch(String path, String oldContent, String newContent, int contextLines) {
        return renderUnifiedPatch(path, diffParts(oldContent, newContent),
                splitLines(oldContent), splitLines(newContent), contextLines);
    }

    /** Display diff plus unified patch, from one shared line diff. */
    public record DiffAndPatch(DiffString diff, String patch) {
    }

    /*
     * The edit tool needs both renderings of the same change; computing them
     * separately runs Myers (and the line splits) twice. One parts pass feeds
     * both renderers here.
     */
    public static DiffAndPatch generateDiffAndPatch(String path, String oldContent, String newContent) {
        List<DiffPart> parts = diffParts(oldContent, newContent);
        return new DiffAndPatch(
                renderDiffString(parts, oldContent, newContent, 4),
                renderUnifiedPatch(path, parts, splitLines(oldContent), splitLines(newContent), 4));
    }

    private static String renderUnifiedPatch(String path, List<DiffPart> parts,
                                             List<String> a, List<String> b, int contextLines) {
        List<LineChange> changes = lineChanges(parts);

        StringBuilder patch = new StringBuilder();
        patch.append("--- ").append(path).append('\n');
        patch.append("+++ ").append(path).append('\n');

        int i = 0;
        while (i < changes.size()) {
            int start = i;
            while (i + 1 < changes.size()
                    && changes.get(i + 1).delStart() - (changes.get(i).delStart() + changes.get(i).delCount())
                            <= 2 * contextLines) {
                i++;
            }
            LineChange first = changes.get(start);
            LineChange last = changes.get(i);
            int hunkStart = Math.max(0, first.delStart() - contextLines);
            int hunkEnd = Math.min(a.size(), last.delStart() + last.delCount() + contextLines);
            int oldCount = hunkEnd - hunkStart;
            int newCount = oldCount + changes.subList(start, i + 1).stream()
                    .mapToInt(c -> c.addCount() - c.delCount()).sum();

            patch.append("@@ -").append(oldCount == 0 ? hunkStart : hunkStart + 1);
            if (oldCount != 1) {
                patch.append(',').append(oldCount);
            }
            patch.append(" +").append(newCount == 0 ? hunkStart : hunkStart + 1);
            if (newCount != 1) {
                patch.append(',').append(newCount);
            }
            patch.append(" @@\n");

            int o = hunkStart;
            int nw = hunkStart;
            for (int c = start; c <= i; c++) {
                LineChange change = changes.get(c);
                while (o < change.delStart()) {
                    appendLine(patch, ' ', a.get(o));
                    o++;
                    nw++;
                }
                for (int d = 0; d < change.delCount(); d++) {
                    appendLine(patch, '-', a.get(o));
                    o++;
                }
                for (int d = 0; d < change.addCount(); d++) {
                    appendLine(patch, '+', b.get(nw));
                    nw++;
                }
            }
            while (o < hunkEnd) {
                appendLine(patch, ' ', a.get(o));
                o++;
                nw++;
            }
            i++;
        }
        return patch.toString();
    }

    private static void appendLine(StringBuilder out, char prefix, String line) {
        out.append(prefix).append(line);
        if (!line.endsWith("\n")) {
            out.append('\n');
        }
    }

    /** Generate a display-oriented diff with line numbers and 4 context lines, collapsing the rest. */
    public static DiffString generateDiffString(String oldContent, String newContent) {
        return generateDiffString(oldContent, newContent, 4);
    }

    public static DiffString generateDiffString(String oldContent, String newContent, int contextLines) {
        return renderDiffString(diffParts(oldContent, newContent), oldContent, newContent, contextLines);
    }

    private static DiffString renderDiffString(List<DiffPart> parts, String oldContent, String newContent,
                                                int contextLines) {
        List<String> out = new ArrayList<>();

        // width from the line counts of both sides, JS split semantics (trailing "" counts)
        int maxLineNum = Math.max(countJsSplitLines(oldContent), countJsSplitLines(newContent));
        int width = String.valueOf(maxLineNum).length();
        String dots = " " + " ".repeat(width) + " ...";

        int oldNum = 1;
        int newNum = 1;
        boolean lastWasChange = false;
        Integer firstChangedLine = null;

        for (int i = 0; i < parts.size(); i++) {
            DiffPart part = parts.get(i);
            List<String> raw = part.lines();
            if (part.added() || part.removed()) {
                if (firstChangedLine == null) {
                    firstChangedLine = newNum;
                }
                for (String line : raw) {
                    if (part.added()) {
                        out.add("+" + pad(newNum, width) + " " + line);
                        newNum++;
                    } else {
                        out.add("-" + pad(oldNum, width) + " " + line);
                        oldNum++;
                    }
                }
                lastWasChange = true;
                continue;
            }

            boolean trailingChange = i < parts.size() - 1
                    && (parts.get(i + 1).added() || parts.get(i + 1).removed());
            boolean leadingChange = lastWasChange;
            if (leadingChange && trailingChange) {
                if (raw.size() <= contextLines * 2) {
                    for (String line : raw) {
                        out.add(" " + pad(oldNum, width) + " " + line);
                        oldNum++;
                        newNum++;
                    }
                } else {
                    for (String line : raw.subList(0, contextLines)) {
                        out.add(" " + pad(oldNum, width) + " " + line);
                        oldNum++;
                        newNum++;
                    }
                    int skipped = raw.size() - 2 * contextLines;
                    out.add(dots);
                    oldNum += skipped;
                    newNum += skipped;
                    for (String line : raw.subList(raw.size() - contextLines, raw.size())) {
                        out.add(" " + pad(oldNum, width) + " " + line);
                        oldNum++;
                        newNum++;
                    }
                }
            } else if (leadingChange) {
                List<String> shown = raw.subList(0, Math.min(contextLines, raw.size()));
                for (String line : shown) {
                    out.add(" " + pad(oldNum, width) + " " + line);
                    oldNum++;
                    newNum++;
                }
                if (raw.size() > shown.size()) {
                    out.add(dots);
                    oldNum += raw.size() - shown.size();
                    newNum += raw.size() - shown.size();
                }
            } else if (trailingChange) {
                int skipped = Math.max(0, raw.size() - contextLines);
                if (skipped > 0) {
                    out.add(dots);
                    oldNum += skipped;
                    newNum += skipped;
                }
                for (String line : raw.subList(skipped, raw.size())) {
                    out.add(" " + pad(oldNum, width) + " " + line);
                    oldNum++;
                    newNum++;
                }
            } else {
                oldNum += raw.size();
                newNum += raw.size();
            }
            lastWasChange = false;
        }
        return new DiffString(String.join("\n", out), firstChangedLine);
    }

    private static String pad(int n, int width) {
        String digits = Integer.toString(n);
        int padding = width - digits.length();
        if (padding <= 0) {
            return digits;
        }
        char[] out = new char[width];
        Arrays.fill(out, 0, padding, ' ');
        digits.getChars(0, digits.length(), out, padding);
        return new String(out);
    }

    /* split("\\n", -1).length without splitting: number of separators plus one. */
    private static int countJsSplitLines(String content) {
        int count = 1;
        for (int i = content.indexOf('\n'); i >= 0; i = content.indexOf('\n', i + 1)) {
            count++;
        }
        return count;
    }

    // ------------------------------------------------------------- line diff

    /*
     * Myers O(ND) with common prefix/suffix trimming. The trim is what keeps the
     * trace (O(D^2) memory) small for real edits, which always share long runs
     * of unchanged lines at both ends of the file.
     */
    static List<DiffPart> diffParts(String oldContent, String newContent) {
        List<String> a = splitLines(oldContent);
        List<String> b = splitLines(newContent);
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);

        final int EQUAL = 0;
        final int REMOVE = 1;
        final int INSERT = 2;
        record Op(int kind, String line) {
        }
        List<Op> ops = new ArrayList<>();

        int n = midA.size();
        int m = midB.size();
        int max = n + m;
        if (max > 0) {
            int[] v = new int[2 * max + 3];
            List<int[]> trace = new ArrayList<>();
            int finalD = -1;
            search:
            for (int d = 0; d <= max; d++) {
                trace.add(v.clone());
                for (int k = -d; k <= d; k += 2) {
                    int x;
                    if (k == -d || (k != d && v[max + 1 + k - 1] < v[max + 1 + k + 1])) {
                        x = v[max + 1 + k + 1];
                    } else {
                        x = v[max + 1 + k - 1] + 1;
                    }
                    int y = x - k;
                    while (x < n && y < m && midA.get(x).equals(midB.get(y))) {
                        x++;
                        y++;
                    }
                    v[max + 1 + k] = x;
                    if (x >= n && y >= m) {
                        finalD = d;
                        break search;
                    }
                }
            }
            int x = n;
            int y = m;
            for (int d = finalD; d > 0; d--) {
                // trace[d] (snapshot taken at the start of step d) holds the v-state
                // after step d-1 — the state the step-d move was decided against.
                int[] vp = trace.get(d);
                int k = x - y;
                int prevK;
                boolean down;
                if (k == -d || (k != d && vp[max + 1 + k - 1] < vp[max + 1 + k + 1])) {
                    prevK = k + 1;
                    down = true;
                } else {
                    prevK = k - 1;
                    down = false;
                }
                int prevX = vp[max + 1 + prevK];
                int prevY = prevX - prevK;
                while (x > prevX && y > prevY) {
                    x--;
                    y--;
                    ops.add(new Op(EQUAL, midA.get(x)));
                }
                if (down) {
                    y--;
                    ops.add(new Op(INSERT, midB.get(y)));
                } else {
                    x--;
                    ops.add(new Op(REMOVE, midA.get(x)));
                }
            }
            while (x > 0 && y > 0) {
                x--;
                y--;
                ops.add(new Op(EQUAL, midA.get(x)));
            }
        }
        Collections.reverse(ops);

        List<DiffPart> parts = new ArrayList<>();
        if (prefix > 0) {
            parts.add(DiffPart.equal(stripTerminators(a.subList(0, prefix))));
        }
        int i = 0;
        while (i < ops.size()) {
            if (ops.get(i).kind() == EQUAL) {
                int s = i;
                while (i < ops.size() && ops.get(i).kind() == EQUAL) {
                    i++;
                }
                List<String> lines = new ArrayList<>();
                for (int k = s; k < i; k++) {
                    lines.add(ops.get(k).line());
                }
                parts.add(DiffPart.equal(stripTerminators(lines)));
            } else {
                List<String> removed = new ArrayList<>();
                List<String> added = new ArrayList<>();
                while (i < ops.size() && ops.get(i).kind() != EQUAL) {
                    if (ops.get(i).kind() == REMOVE) {
                        removed.add(ops.get(i).line());
                    } else {
                        added.add(ops.get(i).line());
                    }
                    i++;
                }
                if (!removed.isEmpty()) {
                    parts.add(new DiffPart(false, true, stripTerminators(removed)));
                }
                if (!added.isEmpty()) {
                    parts.add(new DiffPart(true, false, stripTerminators(added)));
                }
            }
        }
        if (suffix > 0) {
            parts.add(DiffPart.equal(stripTerminators(a.subList(a.size() - suffix, a.size()))));
        }
        return parts;
    }

    static List<LineChange> lineChanges(List<DiffPart> parts) {
        List<LineChange> changes = new ArrayList<>();
        int ai = 0;
        int bi = 0;
        int i = 0;
        while (i < parts.size()) {
            DiffPart part = parts.get(i);
            if (!part.added() && !part.removed()) {
                int count = part.lines().size();
                ai += count;
                bi += count;
                i++;
                continue;
            }
            int dels = 0;
            int adds = 0;
            while (i < parts.size() && (parts.get(i).removed() || parts.get(i).added())) {
                if (parts.get(i).removed()) {
                    dels += parts.get(i).lines().size();
                } else {
                    adds += parts.get(i).lines().size();
                }
                i++;
            }
            changes.add(new LineChange(ai, dels, bi, adds));
            ai += dels;
            bi += adds;
        }
        return changes;
    }

    /* Lines with their terminators, so a final line without a newline differs from one with it (jsdiff semantics). */
    private static List<String> splitLines(String content) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                lines.add(content.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < content.length()) {
            lines.add(content.substring(start));
        }
        return lines;
    }

    private static List<String> stripTerminators(List<String> lines) {
        return lines.stream().map(line -> line.endsWith("\n") ? line.substring(0, line.length() - 1) : line).toList();
    }

    private EditDiff() {
    }
}
