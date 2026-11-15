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
            NoteDocument doc=AbcDocumentIO.read(tmp);
            NoteEditorDelta.Applied applied=NoteEditorDelta.apply(doc,deltas);
            if(applied.unmatchedRemovals()!=0)
                LOG.warning("Piano roll preview: "+applied.unmatchedRemovals()+" original notes no longer match generated part(s)");
            Sequence sequence=info.getSequence();
            for(AbcExporter.ExportTrackInfo trackInfo: info.getLastTrackInfos()) {
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
                // Piano-roll ABC notes use sounding pitches. Maestro's normal
                // preview uses the written ABC pitch with LOTRO samples (which
                // transpose internally), or sounding pitch with a standard SF.
                int pitchAdjustment=lotroInstruments?-layer.abcPitchShift:0;
                replaceNotes(track,layer.notes,trackInfo.channel,pitchAdjustment,origin,ratio);
            }
        } catch(Exception ex) {
            // An invalid editor override must be diagnosed, not silently crash Maestro.
            LOG.log(Level.WARNING,"Could not synchronize edited ABC preview notes; generated preview retained",ex);
        } finally { if(tmp!=null)tmp.delete(); }
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
