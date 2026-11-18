import com.digero.common.midi.*;
import com.digero.maestro.abc.*;
import com.digero.maestro.midi.SequenceInfo;
import javax.sound.midi.*;
import java.io.File;
import java.util.*;

/** Actual MIDI clock regression: seek while playing the imported MSX's authored lute alone. */
public class SoloTempoSeekRegression extends AuthoredTuneTimingRegression {
    static class Transport extends SequencerWrapper {
        Transport() throws MidiUnavailableException {super();}
        @Override public Receiver createReceiver(){return new Receiver(){public void send(MidiMessage m,long t){}public void close(){}};}
        Sequencer raw(){return sequencer;}
    }
    static TreeMap<Long,Integer> tempos(Sequence sequence){
        TreeMap<Long,Integer> r=new TreeMap<>();
        for(int i=0;i<sequence.getTracks()[0].size();i++){
            var event=sequence.getTracks()[0].get(i);
            int mpq=MidiUtils.getTempoMPQ(event.getMessage());
            if(mpq>0)r.put(event.getTick(),mpq);
        }return r;
    }
    static boolean seek(Transport tr, TreeMap<Long,Integer> map, long start, long target,long end)throws Exception{
        tr.stop();tr.setTickPosition(start);tr.start();Thread.sleep(150);
        tr.setTickPosition(target);
        long deadline=System.nanoTime()+15_000_000_000L;int mismatch=0,last=0;boolean reached=false;
        while(System.nanoTime()<deadline){long tick=tr.getTickPosition();int actual=Math.round(tr.raw().getTempoInMPQ());int expected=map.floorEntry(tick).getValue();
            if(actual!=last){System.out.printf("tick=%d actual=%d expected=%d%n",tick,actual,expected);last=actual;}
            if(Math.abs(actual-expected)>2000)mismatch++;
            if(tick>=end){reached=true;break;}Thread.sleep(20);
        }
        tr.stop();System.out.println("seek "+start+" -> "+target+", reached="+reached+", mismatches="+mismatch);return reached&&mismatch<5;
    }
    public static void main(String[] a)throws Exception{
        System.setProperty("java.awt.headless","true");com.digero.common.i18n.LocaleManager.init();com.digero.common.i18n.UIText.init();
        AbcSong song=load(new File(a[0]),new File(a[1]));var lute=song.getParts().stream().filter(p->p.getPartNumber()==1).findFirst().orElseThrow();song.setOriginalSourceEnabled(1,false);
        var preview=SequenceInfo.fromAbcParts(song,true,true);Sequence seq=preview.getSequence();var map=tempos(seq);System.out.println("res="+seq.getResolution()+" tempos="+map);
        var selected=preview.getLastTrackInfos().stream().filter(t->t.part.getPartNumber()==1).toList();check(selected.size()==1&&selected.getFirst() instanceof AuthoredPreviewTrackInfo,"Not authored only");int track=selected.getFirst().trackNumber;
        try {Transport tr=new Transport();try{
            tr.setSequence(seq);tr.setTrackSolo(track,true);System.out.println("solo="+track+", conductor active="+tr.isTrackActive(0));
            var transitions=new ArrayList<Long>();int previous=0;for(var entry:map.entrySet()){if(previous!=0&&entry.getValue()!=previous)transitions.add(entry.getKey());previous=entry.getValue();}
            System.out.println("transitions="+transitions);int r=seq.getResolution();
            // First and last changing tempo boundaries, including backwards running seek.
            long first=transitions.getFirst(), last=transitions.getLast();long second=transitions.get(1);
            boolean ok=seek(tr,map,0,Math.max(first+1,second-r),second+r);
            ok &=seek(tr,map,last+2L*r,Math.max(second+1,last-2L*r),last+r);
            ok &=seek(tr,map,last+2L*r,Math.max(first+1,second-r),second+r);
            for(int n=1;n<seq.getTracks().length;n++)check(tr.isTrackActive(n)==(n==track),"Solo leaked to track "+n);
            tr.setTrackSolo(track,false);check(!tr.getTrackSolo(0),"Conductor solo leaked after unsolo");
            check(ok,"Tempo mismatch during playback seek");
            // A source MIDI track containing notes must never be made a conductor.
            Sequence musical=new Sequence(Sequence.PPQ,480);
            Track zero=musical.createTrack();zero.add(MidiFactory.createTempoEvent(500000,0));
            ShortMessage n=new ShortMessage(ShortMessage.NOTE_ON,0,60,90);zero.add(new MidiEvent(n,10));musical.createTrack();
            tr.setSequence(musical);tr.setTrackSolo(1,true);check(!tr.getTrackSolo(0)&&!tr.isTrackActive(0),"Musical track 0 was enabled");
            tr.clearAllSoloMute();check(!tr.getTrackSolo(0)&&!tr.getTrackSolo(1),"Clear solo failed");
            System.out.println("PASS solo seek across fixed and ramp tempo boundaries");
        } finally {tr.discard();}}catch(Throwable e){e.printStackTrace();System.exit(1);}System.exit(0);
    }
}
