package com.digero.maestro.noteedit;

import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Presentation only. Use Maestro's existing registries; never remap pitches.
 * Reflection preserves the optional editor-only build without Maestro classes. */
final class DrumLabels {
    enum Kit { NONE, LOTRO, MIDI }
    private static final Map<String, Boolean> BASIC_DRUM_NAMES = new ConcurrentHashMap<>();
    private static final String[] LOTRO = loadNames("com.digero.maestro.abc.LotroDrumInfo", "getById", "nameMinimal", 36, 72);
    private static final String[] MIDI = loadNames("com.digero.common.midi.MidiDrum", "fromId", "name", 27, 87);
    private DrumLabels() {}

    private static String[] loadNames(String type, String lookup, String nameField, int first, int last) {
        String[] result = new String[128];
        try {
            Class<?> cls = Class.forName(type);
            Method get = cls.getMethod(lookup, int.class);
            var field = cls.getField(nameField);
            for (int pitch=first; pitch<=last; pitch++) {
                Object drum = get.invoke(null, pitch);
                if (drum != null) result[pitch] = (String)field.get(drum);
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Standalone editor without Maestro's registries: numeric drum identifiers
            // are safer than inventing sound names. The full application has both tables.
            for (int pitch=first; pitch<=last; pitch++)
                if (result[pitch] == null) result[pitch] = "Drum " + pitch;
        }
        return result;
    }

    static boolean isBasicDrum(String name) {
        if (name == null || name.isBlank()) return false;
        return BASIC_DRUM_NAMES.computeIfAbsent(name, value -> {
            try {
                Class<?> cls = Class.forName("com.digero.common.abc.LotroInstrument");
                Object instrument = cls.getMethod("findInstrumentName", String.class, cls).invoke(null,value,null);
                return instrument instanceof Enum<?> e && e.name().equals("BASIC_DRUM");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return switch(value.trim().toLowerCase(Locale.ROOT)) {
                    case "basic drum", "basic drums", "drum", "drums", "trommel", "tambour" -> true;
                    default -> false;
                };
            }
        });
    }

    static Kit keyboardKit(NoteDocument doc, NoteDocument.Layer layer) {
        if (doc.kind == NoteDocument.Kind.ABC) return isBasicDrum(layer.instrumentName) ? Kit.LOTRO : Kit.NONE;
        // A raw MIDI track can contain multiple channels. Only a dedicated percussion
        // track has a percussion keyboard; individual channel-10 notes still get names.
        return !layer.notes.isEmpty() && layer.notes.stream().allMatch(n -> n.channel()==9) ? Kit.MIDI : Kit.NONE;
    }

    static Kit noteKit(NoteDocument doc, NoteDocument.Layer layer, EditableNote note) {
        if (doc.kind == NoteDocument.Kind.ABC) return keyboardKit(doc,layer);
        return note.channel()==9 ? Kit.MIDI : Kit.NONE;
    }

    static boolean hasDrums(NoteDocument doc) {
        if(doc==null) return false;
        return doc.layers.stream().anyMatch(layer -> doc.kind==NoteDocument.Kind.ABC
            ? isBasicDrum(layer.instrumentName) : layer.notes.stream().anyMatch(n->n.channel()==9));
    }

    static String name(Kit kit, int soundingPitch, int abcPitchShift) {
        int id = kit==Kit.LOTRO ? soundingPitch-abcPitchShift : soundingPitch;
        if (id<0 || id>=128 || kit==Kit.NONE) return null;
        return (kit==Kit.LOTRO ? LOTRO : MIDI)[id];
    }
}
