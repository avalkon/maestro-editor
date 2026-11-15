package com.digero.maestro.noteedit;

import javax.sound.midi.MidiSystem;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Adapter to upstream's LOTRO instrument registry/synthesizer. Reflection keeps the
 * editor-only JAR buildable without the rest of Maestro on its classpath.
 */
final class LotroPatch {
    private LotroPatch() {}

    static int programFor(String name) {
        if (name == null || name.isBlank()) return 0;
        try {
            Class<?> cls = Class.forName("com.digero.common.abc.LotroInstrument");
            Method find = cls.getMethod("findInstrumentName", String.class, cls);
            Object instrument = find.invoke(null, name, null);
            if (instrument == null) return 0;
            Field midi = cls.getField("midi");
            return (int) midi.get(instrument).getClass().getMethod("id").invoke(midi.get(instrument));
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // The optional standalone JAR has no dependency on Maestro classes.
            // Without the registry we cannot invent an instrument program.
            return 0;
        }
    }

    /** Match Maestro's instrument octaveDelta (not standard-ABC's unrelated +12 offset).
     * Under Maestro, query its authoritative instrument registry; when the standalone
     * editor is launched, use the upstream v4.7.6 instrument names as a fallback.
     */
    static int octaveDeltaFor(String name) {
        if (name == null || name.isBlank()) return 0;
        try {
            Class<?> cls = Class.forName("com.digero.common.abc.LotroInstrument");
            Method find = cls.getMethod("findInstrumentName", String.class, cls);
            Object instrument = find.invoke(null, name, null);
            if (instrument != null) return cls.getField("octaveDelta").getInt(instrument);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Standalone editor: no Maestro classes available.
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "basic theorbo", "theorbo" -> -1;
            case "basic fiddle", "bardic fiddle", "lonely mountain fiddle",
                 "sprightly fiddle", "student's fiddle", "traveller's trusty fiddle",
                 "basic bagpipe", "basic clarinet" -> 1;
            case "basic flute", "basic pibgorn" -> 2;
            case "jaunty hand-knells" -> 3;
            default -> 0;
        };
    }

    /**
     * LOTRO's written ABC note range is C2-C5 (MIDI 36..72) before any
     * instrument octaveDelta. In the piano roll ABC notes use sounding pitch,
     * so convert back before checking the playable written-note envelope.
     * Student's Fiddle additionally has separate FX (C2-D2) and chromatic
     * (G2-C5) regions. Percussion notes may require instrument-specific hits.
     */
    static boolean isPlayableSoundingPitch(String instrument, int soundingPitch, int abcPitchShift) {
        int written = soundingPitch - abcPitchShift;
        if (written < 36 || written > 72) return false;
        if (instrument != null && instrument.trim().equalsIgnoreCase("Student's Fiddle"))
            return written <= 38 || written >= 43;
        return true;
    }

    static Synthesizer openSynthesizer(boolean lotro) throws Exception {
        if (lotro) {
            try {
                Class<?> factory = Class.forName("com.digero.common.midi.SynthesizerFactory");
                return (Synthesizer) factory.getMethod("getLotroSynthesizer").invoke(null);
            } catch (ClassNotFoundException ignored) {
                // The standalone application may use the same soundfont, if supplied.
            } catch (java.lang.reflect.InvocationTargetException ex) {
                Throwable cause = ex.getCause();
                throw new IllegalStateException("Maestro LOTRO synthesizer could not start: " + cause, cause);
            }
        }
        Synthesizer synth = MidiSystem.getSynthesizer();
        synth.open();
        if (lotro) {
            File soundfont = new File("LotroInstruments.sf2");
            if (soundfont.isFile()) {
                Soundbank bank = MidiSystem.getSoundbank(soundfont);
                synth.loadAllInstruments(bank);
            }
        }
        return synth;
    }
}
