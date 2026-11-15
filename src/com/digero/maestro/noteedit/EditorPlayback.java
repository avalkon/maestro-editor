package com.digero.maestro.noteedit;

import javax.sound.midi.*;
import java.util.*;

/** Playback of the editor's CURRENT notes, never of stale source-file bytes. */
public final class EditorPlayback implements AutoCloseable {
    private Sequencer sequencer;
    private Synthesizer synthesizer;
    private Receiver receiver;
    private boolean sequenceHasLotroParts;
    private javax.swing.Timer auditionStop;
    private int auditionPitch=-1;

    public static Sequence sequenceFor(NoteDocument doc, int partIndex) throws Exception {
        if (doc == null) throw new IllegalArgumentException("No document loaded");
        if (doc.kind == NoteDocument.Kind.MIDI) return MidiDocumentIO.toSequence(doc, partIndex);
        Sequence result = new Sequence(Sequence.PPQ, doc.ppq);
        Track tempo = result.createTrack();
        int chosen = partIndex < 0 ? 0 : partIndex;
        int bpm = doc.layers.isEmpty() ? 120 : doc.layers.get(chosen).abcTempo;
        int micros = 60_000_000 / Math.max(1, bpm);
        byte[] data = {(byte)(micros >> 16), (byte)(micros >> 8), (byte) micros};
        tempo.add(new MidiEvent(new MetaMessage(0x51, data, 3), 0));
        if (partIndex < 0 && doc.layers.size() > 15)
            throw new IllegalArgumentException("Standard MIDI preview supports up to 15 simultaneous LOTRO parts (channel 10 is reserved). Use current part playback for larger ensembles.");
        for (int i = 0; i < doc.layers.size(); i++) {
            if (partIndex >= 0 && i != partIndex) continue;
            int channel = partIndex < 0 ? (i < 9 ? i : i + 1) : 0;
            Track track = result.createTrack();
            track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE,
                    channel, doc.layers.get(i).lotroProgram, 0), 0));
            for (EditableNote note : doc.layers.get(i).notes) {
                // LOTRO's soundfont already transposes the instrument's octave.
                // The editor shows the sounding register, so undo that offset
                // before feeding LOTRO's MIDI program.
                int midiPitch = note.pitch() - doc.layers.get(i).abcPitchShift;
                if (midiPitch < 0 || midiPitch > 127)
                    throw new IllegalArgumentException("Note outside " + doc.layers.get(i).instrumentName + " ABC register");
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,
                        channel, midiPitch, note.velocity()), note.start()));
                track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,
                        channel, midiPitch, 0), note.end()));
            }
        }
        return result;
    }

    public void play(Sequence sequence, long startTick) throws Exception {
        play(sequence, startTick, false);
    }
    public void play(Sequence sequence, long startTick, boolean lotro) throws Exception {
        close();
        sequenceHasLotroParts = lotro;
        try {
            // Explicit wiring makes synth choice predictable and avoids sending
            // events to the system's external MIDI output by accident.
            // When run inside Maestro, this opens its actual LOTRO SF2 synth.
            synthesizer = LotroPatch.openSynthesizer(sequenceHasLotroParts);
            receiver = synthesizer.getReceiver();
            sequencer = MidiSystem.getSequencer(false);
            sequencer.open();
            sequencer.getTransmitter().setReceiver(receiver);
            sequencer.setSequence(sequence);
            sequencer.setTickPosition(Math.max(0, Math.min(startTick, sequence.getTickLength())));
            sequencer.start();
        } catch (Exception ex) {
            close();
            throw ex;
        }
    }
    /** Sound one edited note immediately, using the same LOTRO soundbank as playback. */
    public void audition(EditableNote note,int program,boolean lotro){
        audition(note,program,lotro,127,50,500);
    }
    public void audition(EditableNote note,int program,boolean lotro,int masterVolume,int masterStereo,int durationMs){
        audition(note,program,lotro,masterVolume,masterStereo,durationMs,0);
    }
    public void audition(EditableNote note,int program,boolean lotro,int masterVolume,int masterStereo,int durationMs,int abcPitchShift){
        if(note==null || isRunning())return;
        int midiPitch = note.pitch() - (lotro ? abcPitchShift : 0);
        if(midiPitch<0 || midiPitch>127)return;
        try {
            if(synthesizer==null || !synthesizer.isOpen() || sequenceHasLotroParts!=lotro){
                close();synthesizer=LotroPatch.openSynthesizer(lotro);
                receiver=synthesizer.getReceiver();sequenceHasLotroParts=lotro;
            }
            if(auditionStop!=null)auditionStop.stop();
            if(auditionPitch>=0)receiver.send(new ShortMessage(ShortMessage.NOTE_OFF,0,auditionPitch,0),-1);
            auditionPitch=-1;
            ShortMessage change=new ShortMessage(ShortMessage.PROGRAM_CHANGE,0,program,0);
            receiver.send(change,-1);
            receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE,0,7,
                Math.max(0,Math.min(127,masterVolume))),-1);
            receiver.send(new ShortMessage(ShortMessage.CONTROL_CHANGE,0,10,
                Math.max(0,Math.min(127,(int)Math.round(masterStereo*127.0/100.0)))),-1);
            receiver.send(new ShortMessage(ShortMessage.NOTE_ON,0,midiPitch,note.velocity()),-1);
            auditionPitch=midiPitch;
            auditionStop=new javax.swing.Timer(Math.max(100,Math.min(1500,durationMs)),e->{
                try{if(receiver!=null)receiver.send(new ShortMessage(ShortMessage.NOTE_OFF,0,midiPitch,0),-1);}
                catch(Exception ignored){}finally{auditionPitch=-1;}
            });
            auditionStop.setRepeats(false);auditionStop.start();
        } catch(Exception ignored){ /* Missing audio device must never prevent editing. */ }
    }
    /** Apply Maestro Song Parts mute/solo without deleting any notes. */
    public void setTrackMuteSolo(java.util.Map<Integer,boolean[]> partStates,NoteDocument doc,int selectedLayer) {
        if(!isOpen() || doc==null || doc.kind!=NoteDocument.Kind.ABC)return;
        boolean anySolo=partStates.values().stream().anyMatch(flags->flags[1]);
        for(int i=0;i<doc.layers.size();i++) {
            if(selectedLayer>=0 && i!=selectedLayer)continue;
            int track=selectedLayer>=0?1:i+1; // track 0 carries the tempo map
            if(track>=sequencer.getSequence().getTracks().length)continue;
            boolean[] flags=partStates.getOrDefault(doc.layers.get(i).partNumber,new boolean[2]);
            sequencer.setTrackMute(track,flags[0] || (anySolo && !flags[1]));
            sequencer.setTrackSolo(track,false);
        }
    }
    public void seek(long tick) {
        if (isOpen()) sequencer.setTickPosition(Math.max(0, Math.min(tick, sequencer.getTickLength())));
    }
    public void pauseOrResume() {
        if (sequencer == null || !sequencer.isOpen()) return;
        if (sequencer.isRunning()) sequencer.stop(); else sequencer.start();
    }
    public void stop() { close(); }
    public boolean isOpen() { return sequencer != null && sequencer.isOpen(); }
    public boolean isRunning() { return isOpen() && sequencer.isRunning(); }
    public long position() { return isOpen() ? sequencer.getTickPosition() : 0; }
    public long tickLength() { return isOpen() ? sequencer.getTickLength() : 0; }
    @Override public void close() {
        if(auditionStop!=null){auditionStop.stop();auditionStop=null;}
        if(auditionPitch>=0 && receiver!=null)try{
            receiver.send(new ShortMessage(ShortMessage.NOTE_OFF,0,auditionPitch,0),-1);
        }catch(Exception ignored){}finally{auditionPitch=-1;}
        if (sequencer != null) {
            if (sequencer.isOpen()) { sequencer.stop(); sequencer.close(); }
            sequencer = null;
        }
        if (receiver != null) { receiver.close(); receiver = null; }
        if (synthesizer != null) {
            if (synthesizer.isOpen()) synthesizer.close();
            synthesizer = null;
        }
    }
}
