package com.digero.maestro.abc;

import java.util.List;
import com.digero.maestro.midi.AbcNoteEvent;

/** A physically separate preview MIDI track derived from one editor note layer.
 * It is routed to a Song Part but does not consume an imported source track. */
public final class AuthoredPreviewTrackInfo extends AbcExporter.ExportTrackInfo {
    public final int sourcePartNumber;
    public AuthoredPreviewTrackInfo(int trackNumber,AbcPart destination,int sourcePartNumber,
            List<AbcNoteEvent> events,int channel,int program,long endTick) {
        super(trackNumber,destination,events,channel,program,endTick,events.size(),
              0,0,0,0,0,null);
        this.sourcePartNumber=sourcePartNumber;
    }
    @Override public String getName() {
        return "Piano Roll Track " + sourcePartNumber + " → " + part.getTitle();
    }
}
