package dev.jpi.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link EditDiff}, the pure text-matching core of the edit tool.
 * Expected strings are hand-written worked examples; pi's edit-diff.ts is the
 * behavioral reference for the tricky ones (fuzzy matching, patch format).
 */
class EditDiffTest {

    @Test
    void detectsTheDominantLineEndingAndRoundTripsNormalization() {
        assertEquals("\r\n", EditDiff.detectLineEnding("a\r\nb\r\n"));
        assertEquals("\n", EditDiff.detectLineEnding("a\nb\r\n"));   // lone \n first
        assertEquals("\n", EditDiff.detectLineEnding("no newline at all"));

        assertEquals("a\nb\nc\n", EditDiff.normalizeToLF("a\r\nb\rc\n"));
        assertEquals("a\r\nb\r\nc\r\n", EditDiff.restoreLineEndings("a\nb\nc\n", "\r\n"));
    }

    @Test
    void normalizeForFuzzyMatchStripsTrailingWsAndUnicodizesToAscii() {
        assertEquals("'quoted' trailing\nnext -line\n",
                EditDiff.normalizeForFuzzyMatch("\u2018quoted\u2019 trailing \t\nnext \u2013line\u00a0 \n"));
    }

    @Test
    void fuzzyFindTextPrefersExactMatch() {
        EditDiff.FuzzyMatchResult r = EditDiff.fuzzyFindText("kept  spacing", "kept  spacing");

        assertEquals(true, r.found());
        assertEquals(false, r.usedFuzzyMatch());
        assertEquals(0, r.index());
        assertEquals("kept  spacing", r.contentForReplacement());
    }

    @Test
    void fuzzyFindTextFallsBackToNormalizedMatch() {
        String content = "first\nline with \u201Cquoted\u201D ws \nlast";
        EditDiff.FuzzyMatchResult r = EditDiff.fuzzyFindText(content, "line with \"quoted\" ws");

        assertEquals(true, r.found());
        assertEquals(true, r.usedFuzzyMatch());
        assertEquals("first\nline with \"quoted\" ws\nlast", r.contentForReplacement());
        assertEquals(6, r.index());
        assertEquals("line with \"quoted\" ws".length(), r.matchLength());
    }

    @Test
    void fuzzyFindTextMiss() {
        EditDiff.FuzzyMatchResult r = EditDiff.fuzzyFindText("abc", "xyz");

        assertEquals(false, r.found());
    }

    @Test
    void appliesASingleExactEdit() {
        EditDiff.AppliedEdits r = EditDiff.applyEditsToNormalizedContent(
                "alpha\nbeta\ngamma\n", List.of(new EditDiff.Edit("beta", "BETA")), "f.txt");

        assertEquals("alpha\nbeta\ngamma\n", r.baseContent());
        assertEquals("alpha\nBETA\ngamma\n", r.newContent());
    }

    @Test
    void appliesDisjointEditsInAnyListedOrder() {
        EditDiff.AppliedEdits r = EditDiff.applyEditsToNormalizedContent(
                "alpha\nbeta\ngamma\n",
                List.of(new EditDiff.Edit("gamma", "G"), new EditDiff.Edit("alpha", "A")), "f.txt");

        assertEquals("A\nbeta\nG\n", r.newContent());
    }

    @Test
    void notFoundSurfacesPiErrorWording() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditDiff.applyEditsToNormalizedContent("a\nb\n", List.of(new EditDiff.Edit("z", "y")), "f.txt"));

        assertEquals("Could not find the exact text in f.txt. The old text must match exactly including all whitespace and newlines.",
                e.getMessage());
    }

    @Test
    void duplicateMatchSurfacesPiErrorWording() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditDiff.applyEditsToNormalizedContent("x\nbeta\nx\nbeta\n",
                        List.of(new EditDiff.Edit("beta", "B")), "f.txt"));

        assertEquals("Found 2 occurrences of the text in f.txt. The text must be unique. Please provide more context to make it unique.",
                e.getMessage());
    }

    @Test
    void emptyOldTextIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditDiff.applyEditsToNormalizedContent("a\n", List.of(new EditDiff.Edit("", "x")), "f.txt"));

        assertEquals("oldText must not be empty in f.txt.", e.getMessage());
    }

    @Test
    void identicalReplacementIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditDiff.applyEditsToNormalizedContent("a\nb\n", List.of(new EditDiff.Edit("b", "b")), "f.txt"));

        assertEquals("No changes made to f.txt. The replacement produced identical content. This might indicate an issue with special characters or the text not existing as expected.",
                e.getMessage());
    }

    @Test
    void overlappingEditsAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditDiff.applyEditsToNormalizedContent("abcdef\n",
                        List.of(new EditDiff.Edit("abc", "x"), new EditDiff.Edit("cdef", "y")), "f.txt"));

        assertEquals("edits[0] and edits[1] overlap in f.txt. Merge them into one edit or target disjoint regions.",
                e.getMessage());
    }

    @Test
    void fuzzyEditSucceedsAndPreservesUnchangedLinesByteForByte() {
        // curly quotes + trailing whitespace in the file; ASCII oldText only matches after normalization
        EditDiff.AppliedEdits r = EditDiff.applyEditsToNormalizedContent(
                "keep me \n\u201Cquoted\u201D\ntail \n",
                List.of(new EditDiff.Edit("\"quoted\"", "[ok]")), "f.txt");

        // touched line rewritten normalized; untouched lines keep their original bytes
        assertEquals("keep me \n[ok]\ntail \n", r.newContent());
    }

    @Test
    void stripsAUTF8Bom() {
        assertEquals(new EditDiff.StripBom("", "a"), EditDiff.stripBom("a"));
        assertEquals(new EditDiff.StripBom("\uFEFF", "a"), EditDiff.stripBom("\uFEFFa"));
    }

    @Test
    void unifiedPatchForAReplacement() {
        String old = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n";
        String updated = old.replace("four", "FOUR");

        assertEquals("""
                --- f.txt
                +++ f.txt
                @@ -1,8 +1,8 @@
                 one
                 two
                 three
                -four
                +FOUR
                 five
                 six
                 seven
                 eight
                """,
                EditDiff.generateUnifiedPatch("f.txt", old, updated));
    }

    @Test
    void unifiedPatchForAnInsertion() {
        assertEquals("""
                --- f.txt
                +++ f.txt
                @@ -1,3 +1,4 @@
                 a
                +X
                 b
                 c
                """,
                EditDiff.generateUnifiedPatch("f.txt", "a\nb\nc\n", "a\nX\nb\nc\n"));
    }

    @Test
    void displayDiffNumbersLinesAndMarksesChanges() {
        String old = "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\n";
        String updated = old.replace("four", "FOUR");

        EditDiff.DiffString r = EditDiff.generateDiffString(old, updated);

        assertEquals("""
                 1 one
                 2 two
                 3 three
                -4 four
                +4 FOUR
                 5 five
                 6 six
                 7 seven
                 8 eight""",
                r.diff());
        assertEquals(4, r.firstChangedLine());
    }

    @Test
    void displayDiffForABlockReplacedByAnUnrelatedShorterOne() {
        // no common lines at all: the Myers backtrack must stay in bounds
        EditDiff.DiffString r = EditDiff.generateDiffString(
                "a\nb\nc\nd\ne\nf\n", "zzz\n");

        assertEquals("""
                -1 a
                -2 b
                -3 c
                -4 d
                -5 e
                -6 f
                +1 zzz""",
                r.diff());
        assertEquals(1, r.firstChangedLine());
    }

    @Test
    void displayDiffForABlockReplacedByAnUnrelatedLongerOne() {
        EditDiff.DiffString r = EditDiff.generateDiffString(
                "zzz\n", "p\nq\nr\ns\nt\nu\nv\n");

        assertEquals("""
                -1 zzz
                +1 p
                +2 q
                +3 r
                +4 s
                +5 t
                +6 u
                +7 v""",
                r.diff());
        assertEquals(1, r.firstChangedLine());
    }

    @Test
    void displayDiffCollapsesLongUnchangedRuns() {
        StringBuilder old = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            old.append("L").append(i).append('\n');
        }
        EditDiff.DiffString r = EditDiff.generateDiffString(old.toString(), old.toString().replace("L12", "X"));

        assertEquals("""
                    ...
                  8 L8
                  9 L9
                 10 L10
                 11 L11
                -12 L12
                +12 X""",
                r.diff());
        assertEquals(12, r.firstChangedLine());
    }
}
