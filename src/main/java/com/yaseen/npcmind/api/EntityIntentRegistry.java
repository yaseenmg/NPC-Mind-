package com.yaseen.npcmind.api;

import com.yaseen.npcmind.tool.ToolContract;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Internal storage for host-defined, entity-agnostic command aliases. */
final class EntityIntentRegistry {
    record Intent(String[] phrases, NPCMindAPI.EntityCommandRoute route) {}

    private static final List<Intent> INTENTS = new CopyOnWriteArrayList<>();

    private EntityIntentRegistry() {}

    static void register(String[] phrases, String toolName, String rawArguments, String reason) {
        if (phrases == null || phrases.length == 0) {
            throw new IllegalArgumentException("At least one entity command phrase is required");
        }
        String cleanName = toolName == null ? "" : toolName.trim();
        EntityAITool tool = EntityToolRegistry.get(cleanName);
        if (tool == null) throw new IllegalArgumentException("Unknown entity AI tool: " + cleanName);
        String arguments = rawArguments == null || rawArguments.isBlank() ? "{}" : rawArguments;
        ToolContract.Validation validation = ToolContract.validateSchema(tool.schema(), arguments);
        if (!validation.ok()) {
            throw new IllegalArgumentException("Invalid entity intent arguments for " + cleanName + ": " + validation.reason());
        }
        List<String> normalized = new ArrayList<>();
        for (String phrase : phrases) {
            String alias = normalize(phrase);
            if (!alias.isBlank() && !normalized.contains(alias)) normalized.add(alias);
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("No usable entity command phrases provided");
        synchronized (INTENTS) {
            for (Intent existing : INTENTS) {
                for (String alias : normalized) {
                    for (String registered : existing.phrases()) {
                        if (registered.equals(alias)) {
                            throw new IllegalArgumentException("Entity command phrase already registered: " + alias);
                        }
                    }
                }
            }
            INTENTS.add(new Intent(normalized.toArray(String[]::new),
                    new NPCMindAPI.EntityCommandRoute(cleanName, validation.normalized().toString(),
                            reason == null || reason.isBlank() ? "host entity intent" : reason)));
        }
    }

    static NPCMindAPI.EntityCommandRoute resolve(String command) {
        String normalized = normalize(command);
        if (normalized.isBlank()) return null;
        String padded = " " + normalized + " ";
        return INTENTS.stream()
                .filter(intent -> matches(intent, padded))
                .flatMap(intent -> java.util.Arrays.stream(intent.phrases())
                        .filter(phrase -> padded.contains(" " + phrase + " "))
                        .map(phrase -> new Match(intent.route(), phrase)))
                .max(Comparator.comparingInt((Match match) -> wordCount(match.phrase()))
                        .thenComparingInt(match -> match.phrase().length()))
                .map(Match::route).orElse(null);
    }

    private static boolean matches(Intent intent, String padded) {
        for (String phrase : intent.phrases()) if (padded.contains(" " + phrase + " ")) return true;
        return false;
    }

    private static int wordCount(String phrase) {
        return phrase.isBlank() ? 0 : phrase.split(" ").length;
    }

    private record Match(NPCMindAPI.EntityCommandRoute route, String phrase) {}

    /** Normalize Latin and Arabic aliases consistently (case, punctuation, and Arabic diacritics). */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        // Mirror ToolSelectionRouter.normalize so host-registered Arabic aliases match the
        // same way the built-in router matches them (tatweel, harakat, hamza forms, alef
        // maqsura, Arabic-Indic digits and bidi control marks).
        String text = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace('\u0640', ' ')
                .replaceAll("[\\u064B-\\u065F\\u0670]", "")
                .replace('\u0623', '\u0627').replace('\u0625', '\u0627').replace('\u0622', '\u0627').replace('\u0649', '\u064a')
                .replace('\u0660', '0').replace('\u0661', '1').replace('\u0662', '2').replace('\u0663', '3')
                .replace('\u0664', '4').replace('\u0665', '5').replace('\u0666', '6').replace('\u0667', '7')
                .replace('\u0668', '8').replace('\u0669', '9')
                .replaceAll("[\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]", "")
                .replaceAll("[\\p{P}\\p{S}]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return text;
    }
}
