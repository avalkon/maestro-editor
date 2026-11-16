package com.digero.maestro.abc;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sound.midi.*;

import com.digero.maestro.MaestroMain;
import com.digero.maestro.midi.SequenceInfo;
import com.digero.maestro.midi.AbcNoteEvent;
import com.digero.common.midi.Note;
import com.digero.maestro.noteedit.*;

/**
 * Replace ONLY changed arrangement parts' preview note events with their final
 * piano-roll notes, leaving tempo, soundfont program, controller and pan events
 * untouched. The normal AbcExporter remains the authority for unedited parts.
 */
public final class NoteEditorPreviewOverlay {
    private static final Logger LOG = Logger.getLogger("export.noteEditor");
    private NoteEditorPreviewOverlay() {}

    public static void apply(AbcSong song,SequenceInfo info,AbcExporter exporter,boolean lotroInstruments) {
        Map<Integer,NoteEditorDelta.Part> deltas=song.getNoteEditorDeltas();
        if(deltas.isEmpty() || info.getLastTrackInfos()==null) return;
        File tmp=null;
        try {
            tmp=Files.createTempFile("maestro-final-preview-", ".abc").toFile();
            // Must use GENERATED ABC, not edited ABC: the same deltas are rebased below.
            song.exportGeneratedAbc(tmp,MaestroMain.APP_NAME);
            // The generated exporter correctly omits parts without source tracks.
            // Supply temporary, rest-only baselines ONLY inside this editor overlay,
            // so user-authored parts can be rendered without altering upstream export.
            NoteDocument doc=AbcDocumentIO.readText(song.addMissingEditorParts(Files.readString(tmp.toPath())));
            // First process only deletions/replacements of generated material.
            // Added notes become independent preview tracks below, instead of
            // being hidden inside the existing part's generated track.
            Map<Integer,NoteEditorDelta.Part> removals=new LinkedHashMap<>();
            for (var entry:deltas.entrySet())
                removals.put(entry.getKey(),new NoteEditorDelta.Part(entry.getValue().removed(),List.of()));
            NoteEditorDelta.Applied applied=NoteEditorDelta.apply(doc,removals);
            if(applied.unmatchedRemovals()!=0)
                LOG.warning("Piano roll preview: "+applied.unmatchedRemovals()+" original notes no longer match generated part(s)");
            Sequence sequence=info.getSequence();
            Set<Integer> presented=new HashSet<>();
            for(AbcExporter.ExportTrackInfo trackInfo: info.getLastTrackInfos()) {
                presented.add(trackInfo.part.getPartNumber());
                NoteEditorDelta.Part change=deltas.get(trackInfo.part.getPartNumber());
                if(change==null || change.empty())continue;
                NoteDocument.Layer layer=null;
                for(NoteDocument.Layer candidate:doc.layers)if(candidate.partNumber==trackInfo.part.getPartNumber()) {
                    layer=candidate;break;
                }
                if(layer==null || trackInfo.trackNumber>=sequence.getTracks().length)continue;
                Track track=sequence.getTracks()[trackInfo.trackNumber];
                long firstMidi=Long.MAX_VALUE;
                for(int i=0;i<track.size();i++) {
                    MidiEvent event=track.get(i);
                    if(event.getMessage() instanceof ShortMessage m && m.getCommand()==ShortMessage.NOTE_ON && m.getData2()>0)
                        firstMidi=Math.min(firstMidi,event.getTick());
                }
                long firstAbc=layer.baselineNotes.stream().mapToLong(EditableNote::start).min().orElse(0);
                double ratio=sequence.getResolution()/(double)doc.ppq;
                long origin=firstMidi==Long.MAX_VALUE? exporter.getExportStartTick():
                            firstMidi - Math.round(firstAbc*ratio);
                int pitchAdjustment=lotroInstruments?-layer.abcPitchShift:0;
                List<EditableNote> playable = playableNotes(layer);
                replaceNotes(track,playable,trackInfo.channel,pitchAdjustment,origin,ratio);
                if(trackInfo.noteEvents!=null) {
                    try {
                        trackInfo.noteEvents.clear();
                        trackInfo.noteEvents.addAll(toGraphEvents(playable,layer.abcPitchShift,
                            origin,ratio,exporter));
                    } catch(UnsupportedOperationException immutable) {
                        LOG.warning("Cannot update immutable ABC preview graph for part " +
                            trackInfo.part.getPartNumber());
                    }
                }
            }
            // A piano-roll track is owned by the part where its notes were
            // entered. It may be assigned to additional/other parts. Every
            // destination gets its own separate, visible MIDI preview track.
            Map<Integer,Set<Integer>> routing=song.getAuthoredTrackRouting();
            for (var entry:deltas.entrySet()) {
                int sourceNumber=entry.getKey();
                if(entry.getValue().added().isEmpty()) continue;
                for(int destination:AuthoredTrackRouting.targets(sourceNumber,routing)) {
                    AbcPart part=null;
                    NoteDocument.Layer layer=null;
                    for(AbcPart candidate:song.getParts())if(candidate.getPartNumber()==destination){
                        part=candidate;break;
                    }
                    if(part==null)continue;
                    for(NoteDocument.Layer candidate:doc.layers)if(candidate.partNumber==destination){
                        layer=candidate;break;
                    }
                    if(layer==null) continue;
                    List<EditableNote> additions=new ArrayList<>();
                    for(NoteEditorDelta.Value value:entry.getValue().added()) {
                        EditableNote note=EditableNote.create(value.pitch(),value.velocity(),0,value.start(),value.end());
                        if(AbcDocumentIO.isPlayableForExport(layer,note)) additions.add(note);
                    }
                    if(!additions.isEmpty())
                        appendAuthoredTrack(info,exporter,part,layer,sourceNumber,additions,lotroInstruments);
                }
            }
            if (song.isCompositionMode())
                extendCompositionTimeline(info.getSequence(),song,exporter);
        } catch(Exception ex) {
            // An invalid editor override must be diagnosed, not silently crash Maestro.
            LOG.log(Level.WARNING,"Could not synchronize edited ABC preview notes; generated preview retained",ex);
        } finally { if(tmp!=null)tmp.delete(); }
    }
    private static List<EditableNote> playableNotes(NoteDocument.Layer layer) {
        return layer.notes.stream().filter(note ->
            AbcDocumentIO.isPlayableForExport(layer,note)).toList();
    }

    private static void appendAuthoredTrack(SequenceInfo info,AbcExporter exporter,AbcPart part,
                                            NoteDocument.Layer layer,int sourcePartNumber,
                                            List<EditableNote> notes,boolean lotro) throws Exception {
        Sequence seq=info.getSequence();
        // An entirely blank composition produces a Sequence with no tracks.
        // Add metadata/tempo before adding authored note tracks.
        if(seq.getTracks().length==0) {
            Track meta=seq.createTrack();
            int bpm=Math.max(1,part.getAbcSong().getTempoBPM());
            int microsecondsPerQuarter=Math.round(60_000_000f/bpm);
            MetaMessage tempo=new MetaMessage();
            tempo.setMessage(0x51,new byte[]{(byte)(microsecondsPerQuarter>>16),
                (byte)(microsecondsPerQuarter>>8),(byte)microsecondsPerQuarter},3);
            meta.add(new MidiEvent(tempo,0));
        }
        // Tracks routed to the same destination use the same voice. Sharing
        // that part's channel avoids consuming scarce MIDI channels merely
        // because its notes are displayed in separate authored lanes.
        int channel=-1;
        Set<Integer> occupied=new HashSet<>();
        for (AbcExporter.ExportTrackInfo previous:info.getLastTrackInfos()) {
            if (previous.channel==null)continue;
            occupied.add(previous.channel);
            if (previous.part.getPartNumber()==part.getPartNumber())
                channel=previous.channel;
        }
        if (channel<0) {
            for (int c=0;c<16;c++) if(c!=9 && !occupied.contains(c)) {
                channel=c;break;
            }
        }
        if (channel<0) {
            // The MIDI channel limit is shared with Maestro's ordinary ABC
            // preview; allocating an extra port is a separate future change.
            LOG.warning("More than 15 instrument channels; reusing channel for part " +
                part.getPartNumber());
            channel=info.getLastTrackInfos().size()%15;
            if(channel>=9)channel++;
        }
        Track track=seq.createTrack();
        int index=seq.getTracks().length-1;
        int program=part.getInstrument().midi.id();
        track.add(new MidiEvent(new ShortMessage(ShortMessage.PROGRAM_CHANGE,channel,program,0),0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE,channel,7,127),0));
        track.add(new MidiEvent(new ShortMessage(ShortMessage.CONTROL_CHANGE,channel,10,64),0));
        double ratio=seq.getResolution()/(double)AbcDocumentIO.PPQ;
        long origin=Math.max(0,exporter.getExportStartTick());
        int shift=part.getInstrument().octaveDelta*12;
        java.util.List<AbcNoteEvent> events=new ArrayList<>();
        long finish=origin;
        for(EditableNote note:notes) {
            int written=note.pitch()-shift;
            int playPitch=lotro?written:note.pitch();
            if(playPitch<0 || playPitch>127)continue;
            long start=Math.max(0,origin+Math.round(note.start()*ratio));
            long end=Math.max(start+1,origin+Math.round(note.end()*ratio));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,channel,playPitch,note.velocity()),start));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,channel,playPitch,0),end));
            Note modelPitch=Note.fromId(written);
            if(modelPitch!=null)
                events.add(new AbcNoteEvent(modelPitch,note.velocity(),start,end,
                    exporter.getTimingInfo(),null));
            finish=Math.max(finish,end);
        }
        events.sort(Comparator.comparingLong(AbcNoteEvent::getStartTick));
        info.getLastTrackInfos().add(new AuthoredPreviewTrackInfo(
            index,part.origPart,sourcePartNumber,events,channel,program,finish));
    }

    /** A newly composed score keeps its declared length even when the last
     *  note ends before the final bar. This is a composition-only behavior:
     *  imported projects retain their original length and empty-track rules. */
    private static void extendCompositionTimeline(Sequence sequence, AbcSong song,
                                                    AbcExporter exporter) throws InvalidMidiDataException {
        if (sequence.getTracks().length == 0) return;
        int measures = song.getInitialCompositionMeasures();
        var meter = song.getTimeSignature();
        long ticks = Math.max(1, Math.round(sequence.getResolution() * 4.0
            * meter.numerator / meter.denominator * measures));
        long end = Math.max(sequence.getTickLength(),
            Math.max(0, exporter.getExportStartTick()) + ticks);
        // The first track owns the end marker, not a new silent instrument track.
        MetaMessage eot = new MetaMessage();
        eot.setMessage(0x2F,new byte[0],0);
        sequence.getTracks()[0].add(new MidiEvent(eot,end));
    }

    private static List<AbcNoteEvent> toGraphEvents(List<EditableNote> notes,int shift,
            long origin,double ratio,AbcExporter exporter) {
        List<AbcNoteEvent> events=new ArrayList<>();
        for(EditableNote note:notes) {
            int written=note.pitch()-shift;
            Note pitch=Note.fromId(written);
            if(pitch==null)continue;
            long start=Math.max(0,origin+Math.round(note.start()*ratio));
            long end=Math.max(start+1,origin+Math.round(note.end()*ratio));
            events.add(new AbcNoteEvent(pitch,note.velocity(),start,end,
                exporter.getTimingInfo(),null));
        }
        events.sort(Comparator.comparingLong(AbcNoteEvent::getStartTick));
        return events;
    }

    private static void replaceNotes(Track track,List<EditableNote> notes,int channel,int octave,long origin,double ratio)
            throws InvalidMidiDataException {
        for(int i=track.size()-1;i>=0;i--) {
            MidiEvent event=track.get(i);
            MidiMessage message=event.getMessage();
            if(message instanceof ShortMessage sm &&
                (sm.getCommand()==ShortMessage.NOTE_ON || sm.getCommand()==ShortMessage.NOTE_OFF))track.remove(event);
        }
        for(EditableNote note:notes) {
            int pitch=note.pitch()+octave;
            if(pitch<0 || pitch>127)continue;
            long start=Math.max(0,origin+Math.round(note.start()*ratio));
            long end=Math.max(start+1,origin+Math.round(note.end()*ratio));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_ON,channel,pitch,note.velocity()),start));
            track.add(new MidiEvent(new ShortMessage(ShortMessage.NOTE_OFF,channel,pitch,0),end));
        }
    }
}
