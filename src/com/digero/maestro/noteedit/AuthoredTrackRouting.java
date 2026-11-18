package com.digero.maestro.noteedit;

import java.util.*;

/**
 * Routing for separately visible piano-roll tracks. Each source part's added
 * notes form one virtual track. By default that track feeds its source part;
 * explicit destinations can route it to any existing Song Part instead.
 *
 * Routing is applied at ABC export and preview time. It never changes imported
 * MIDI tracks or consumes a physical source-track index.
 */
public final class AuthoredTrackRouting {
    private AuthoredTrackRouting() {}

    public static Set<Integer> targets(int sourcePart,
                                       Map<Integer,? extends Set<Integer>> assignments) {
        Set<Integer> configured = assignments.get(sourcePart);
        return configured == null ? Set.of(sourcePart) : configured;
    }

    /** Validate and freeze routing. An explicit empty assignment is permitted:
     * the authored notes remain in the MSX/editor but play in no part. */
    public static Map<Integer,Set<Integer>> copyOf(Map<Integer,? extends Set<Integer>> routes) {
        Map<Integer,Set<Integer>> copy = new LinkedHashMap<>();
        for (var entry : routes.entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 1)
                throw new IllegalArgumentException("Invalid authored source part number");
            Set<Integer> destinations = new LinkedHashSet<>();
            for (int target : entry.getValue()) {
                if (target < 1) throw new IllegalArgumentException("Invalid destination part number");
                destinations.add(target);
            }
            copy.put(entry.getKey(),Collections.unmodifiableSet(destinations));
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * Retain deletions in the original part, but route added/replaced notes to
     * every selected destination. Avoids duplicate additions if a track is
     * unassigned from its original part. Values are concert/sounding pitch and
     * the target instrument's normal range filter applies later.
     */
    public static Map<Integer,NoteEditorDelta.Part> forExport(
        Map<Integer,NoteEditorDelta.Part> deltas,
        Map<Integer,? extends Set<Integer>> routes,
        Set<Integer> existingParts) {
        Set<Integer> suppressed=new LinkedHashSet<>();
        for(var entry:deltas.entrySet())
            if(!entry.getValue().fullCopy() || targets(entry.getKey(),routes).contains(entry.getKey()))
                suppressed.add(entry.getKey());
        return forExport(deltas,routes,existingParts,suppressed);
    }

    /** Suppression of the imported original is independent of the authored
     * destinations. This permits original only, authored only, both, or neither. */
    public static Map<Integer,NoteEditorDelta.Part> forExport(
        Map<Integer,NoteEditorDelta.Part> deltas,
        Map<Integer,? extends Set<Integer>> routes,
        Set<Integer> existingParts,
        Set<Integer> suppressedOriginalSources) {
        Map<Integer,List<NoteEditorDelta.Value>> removals = new LinkedHashMap<>();
        Map<Integer,List<NoteEditorDelta.Value>> additions = new LinkedHashMap<>();
        Set<Integer> replacedSources = new LinkedHashSet<>();
        for (var entry : deltas.entrySet()) {
            int source = entry.getKey();
            Set<Integer> destinations=targets(source,routes);
            if (existingParts.contains(source) &&
                    (!entry.getValue().fullCopy() || suppressedOriginalSources.contains(source))) {
                removals.computeIfAbsent(source,k->new ArrayList<>()).addAll(entry.getValue().removed());
                if (entry.getValue().fullCopy()) replacedSources.add(source);
            }
            for (int target : destinations) {
                if (existingParts.contains(target))
                    additions.computeIfAbsent(target,k->new ArrayList<>()).addAll(entry.getValue().added());
            }
        }
        Map<Integer,NoteEditorDelta.Part> result = new LinkedHashMap<>();
        Set<Integer> keys = new LinkedHashSet<>(removals.keySet());
        keys.addAll(additions.keySet());
        for (int key : keys) {
            var part = new NoteEditorDelta.Part(removals.getOrDefault(key,List.of()),
                            additions.getOrDefault(key,List.of()),replacedSources.contains(key));
            if (!part.empty()) result.put(key,part);
        }
        return Collections.unmodifiableMap(result);
    }
}
