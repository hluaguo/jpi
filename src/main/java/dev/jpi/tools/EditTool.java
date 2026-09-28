package dev.jpi.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.jpi.agent.AgentTool;
import dev.jpi.agent.AgentToolResult;
import dev.jpi.ai.Content;
import dev.jpi.util.CancellationToken;

/**
 * Edits a file by exact-text replacement, ported from pi's edit.ts.
 *
 * <p><em>Why exact text and not line ranges:</em> the model must re-read what it
 * is about to change, so the oldText anchor both proves the read and makes
 * concurrent drift fail loudly instead of editing the wrong region. All
 * matching/validation/preservation semantics live in {@link EditDiff}; this
 * class is only the file plumbing around it.
 */
public final class EditTool implements AgentTool {

    @Override
    public String name() {
        return "edit";
    }

    @Override
    public String description() {
        return "Edit a single file using exact text replacement. Every edits[].oldText must match a unique, "
                + "non-overlapping region of the original file. If two changes affect the same block or nearby "
                + "lines, merge them into one edit instead of emitting overlapping edits. Do not include large "
                + "unchanged regions just to connect distant changes.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> oldText = Map.of(
                "type", "string",
                "description", "Exact text for one targeted replacement. It must be unique in the original file "
                        + "and must not overlap with any other edits[].oldText in the same call.");
        Map<String, Object> newText = Map.of(
                "type", "string",
                "description", "Replacement text for this targeted edit.");
        Map<String, Object> replaceEdit = Map.of(
                "type", "object",
                "properties", Map.of("oldText", oldText, "newText", newText),
                "required", List.of("oldText", "newText"));
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "path", Map.of("type", "string",
                                "description", "Path to the file to edit (relative or absolute)"),
                        "edits", Map.of(
                                "type", "array",
                                "items", replaceEdit,
                                "description", "One or more targeted replacements. Each edit is matched against "
                                        + "the original file, not incrementally. Do not include overlapping or "
                                        + "nested edits. If two changes touch the same block or nearby lines, "
                                        + "merge them into one edit instead.")),
                "required", List.of("path", "edits"));
    }

    @Override
    @SuppressWarnings("unchecked")
    public AgentToolResult execute(String toolCallId, Map<String, Object> args,
                                   CancellationToken signal,
                                   java.util.function.Consumer<Map<String, Object>> onUpdate) {
        Path path = Path.of((String) args.get("path"));
        Object rawEdits = args.get("edits");
        if (!(rawEdits instanceof List<?> editList) || editList.isEmpty()) {
            throw new IllegalArgumentException(
                    "Edit tool input is invalid. edits must contain at least one replacement.");
        }
        List<EditDiff.Edit> edits = new ArrayList<>();
        for (Object edit : editList) {
            Map<String, Object> map = (Map<String, Object>) edit;
            edits.add(new EditDiff.Edit((String) map.get("oldText"), (String) map.get("newText")));
        }

        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Could not edit file: " + path + ". Path is not a file.");
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            EditDiff.StripBom bom = EditDiff.stripBom(content);
            String ending = EditDiff.detectLineEnding(bom.text());
            String normalized = EditDiff.normalizeToLF(bom.text());

            EditDiff.AppliedEdits applied =
                    EditDiff.applyEditsToNormalizedContent(normalized, edits, path.toString());
            EditDiff.DiffString diff =
                    EditDiff.generateDiffString(applied.baseContent(), applied.newContent());

            String finalContent = bom.bom() + EditDiff.restoreLineEndings(applied.newContent(), ending);
            Files.writeString(path, finalContent, StandardCharsets.UTF_8);

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("diff", diff.diff());
            details.put("patch", EditDiff.generateUnifiedPatch(
                    path.toString(), applied.baseContent(), applied.newContent()));
            if (diff.firstChangedLine() != null) {
                details.put("firstChangedLine", diff.firstChangedLine());
            }
            return new AgentToolResult(
                    List.of(new Content.Text("Successfully replaced " + edits.size()
                            + " block(s) in " + path + ".")),
                    details, null, false);
        } catch (IllegalArgumentException e) {
            throw e;                                  // EditDiff wording surfaces verbatim
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not edit file: " + path + ". " + e.getMessage(), e);
        }
    }
}
