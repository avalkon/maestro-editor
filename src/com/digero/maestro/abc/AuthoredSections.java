package com.digero.maestro.abc;

import com.digero.common.abc.Dynamics;
import com.digero.maestro.noteedit.NoteEditorDelta;
import java.util.*;
import java.util.function.DoubleUnaryOperator;
import org.w3c.dom.*;

/** Immutable, non-destructive section settings for a single authored route.
 * Bars use the same source timeline as the imported-track Section Editor. */
public final class AuthoredSections {
    private AuthoredSections() {}
    public record Route(int source, int destination) {
        public Route {if(source<1 || destination<1)throw new IllegalArgumentException("Invalid route");}
    }
    public record Effect(float start, float end, int octave, int volume, int fade,
                         boolean silence, boolean reset, int low, int high, int doubles) {
        public Effect {
            if(!Float.isFinite(start)||!Float.isFinite(end)||start<0||end<start||end>200000 ||
                octave < -10 || octave > 10 || volume < -127 || volume > 127 ||
                low<0 || high>127 || low>high || doubles<0 || doubles>15)
                throw new IllegalArgumentException("Invalid authored section settings");
        }
        static Effect from(PartSection p) {
            int mask=0; for(int i=0;i<4;i++)if(p.doubling[i])mask|=1<<i;
            return new Effect(p.startBar,p.endBar,p.octaveStep,p.volumeStep,p.fade,p.silence,p.resetVelocities,
                p.fromPitch.id,p.toPitch.id,mask);
        }
        PartSection asPartSection() {
            PartSection p=new PartSection();p.startBar=start;p.endBar=end;p.octaveStep=octave;p.volumeStep=volume;p.fade=fade;
            p.silence=silence;p.resetVelocities=reset;p.fromPitch=com.digero.common.midi.Note.fromId(low);
            p.toPitch=com.digero.common.midi.Note.fromId(high);for(int i=0;i<4;i++)p.doubling[i]=(doubles&(1<<i))!=0;return p;
        }
    }
    public record Settings(List<Effect> sections, Effect remainder) {
        public Settings {
            if(sections.size()>150)throw new IllegalArgumentException("Too many sections");
            sections=sections.stream().sorted(Comparator.comparingDouble(Effect::start)).toList();
            float last=0; for(Effect e:sections){if(e.start()<last || e.end()<=e.start())throw new IllegalArgumentException("Overlapping or empty sections");last=e.end();}
        }
        public boolean empty(){return sections.isEmpty()&&remainder==null;}
        public TreeMap<Float,PartSection> sectionTree(){TreeMap<Float,PartSection> out=new TreeMap<>();for(Effect e:sections)out.put(e.start(),e.asPartSection());return out;}
        public PartSection remainderSection(){return remainder==null?null:remainder.asPartSection();}
        public static Settings from(TreeMap<Float,PartSection> tree,PartSection remainder){return new Settings(tree.values().stream().map(Effect::from).toList(),remainder==null?null:Effect.from(remainder));}
    }
    public static final Settings EMPTY=new Settings(List.of(),null);

    public static List<NoteEditorDelta.Value> apply(Settings settings,List<NoteEditorDelta.Value> notes,
            DoubleUnaryOperator editorToSourceTick,DoubleUnaryOperator barToSourceTick,int writtenPitchShift,boolean percussion) {
        if(settings.empty())return notes;
        List<NoteEditorDelta.Value> out=new ArrayList<>();
        for(var n:notes){
            double tick=editorToSourceTick.applyAsDouble(n.start());
            Effect effect=settings.remainder();double fraction=0;boolean inside=false;
            for(Effect e:settings.sections()){
                double start=barToSourceTick.applyAsDouble(e.start()),end=barToSourceTick.applyAsDouble(e.end());
                if(tick<start)break;
                if(tick>=start&&tick<end){effect=e;fraction=(tick-start)/(end-start);inside=true;break;}
            }
            if(effect==null){out.add(n);continue;}
            int written=n.pitch()-writtenPitchShift;
            if(effect.silence()||(!percussion&&(written<effect.low()||written>effect.high())))continue;
            int velocity=effect.reset()?Dynamics.DEFAULT.midiVol:n.velocity();
            double factor=inside?Math.max(0,effect.fade()>=0?1-effect.fade()/100.0*fraction:1+effect.fade()/100.0*(1-fraction)):1;
            velocity=Math.max(1,Math.min(127,(int)Math.round((velocity+(inside?effect.volume():0))*factor)));
            int pitch=n.pitch()+(inside&&!percussion?12*effect.octave():0);
            add(out,n,pitch,velocity);
            if(!percussion)for(int i=0;i<4;i++)if((effect.doubles()&(1<<i))!=0)add(out,n,pitch+12*(i<2?i-2:i-1),velocity);
        }
        return List.copyOf(out);
    }
    private static void add(List<NoteEditorDelta.Value> out,NoteEditorDelta.Value n,int pitch,int velocity){if(pitch>=0&&pitch<=127)out.add(new NoteEditorDelta.Value(pitch,velocity,n.start(),n.end()));}

    static void write(Element editor,Map<Route,Settings> all){
        Document doc=editor.getOwnerDocument();
        all.forEach((route,settings)->{
            Element node=(Element)editor.appendChild(doc.createElement("authoredSections"));
            node.setAttribute("source",Integer.toString(route.source()));node.setAttribute("destination",Integer.toString(route.destination()));
            for(Effect effect:settings.sections())writeEffect(node,"section",effect);
            if(settings.remainder()!=null)writeEffect(node,"remainder",settings.remainder());
        });
    }
    private static void writeEffect(Element parent,String tag,Effect e){
        Element n=(Element)parent.appendChild(parent.getOwnerDocument().createElement(tag));
        n.setAttribute("start",Float.toString(e.start()));n.setAttribute("end",Float.toString(e.end()));
        n.setAttribute("octave",Integer.toString(e.octave()));n.setAttribute("volume",Integer.toString(e.volume()));n.setAttribute("fade",Integer.toString(e.fade()));
        n.setAttribute("silence",Boolean.toString(e.silence()));n.setAttribute("reset",Boolean.toString(e.reset()));
        n.setAttribute("low",Integer.toString(e.low()));n.setAttribute("high",Integer.toString(e.high()));n.setAttribute("doubles",Integer.toString(e.doubles()));
    }
    static Map<Route,Settings> read(Element editor){
        Map<Route,Settings> out=new LinkedHashMap<>();NodeList nodes=editor.getElementsByTagName("authoredSections");
        for(int i=0;i<nodes.getLength();i++)try{
            Element n=(Element)nodes.item(i);Route route=new Route(integer(n,"source"),integer(n,"destination"));
            List<Effect> sections=new ArrayList<>();Effect remainder=null;
            for(Node child=n.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element e){
                if(e.getTagName().equals("section"))sections.add(readEffect(e));else if(e.getTagName().equals("remainder"))remainder=readEffect(e);
            }
            Settings settings=new Settings(sections,remainder);if(!settings.empty())out.put(route,settings);
        }catch(IllegalArgumentException ex){java.util.logging.Logger.getLogger("abc").warning("Ignored invalid authored section: "+ex.getMessage());}
        return Map.copyOf(out);
    }
    private static int integer(Element e,String name){return Integer.parseInt(e.getAttribute(name));}
    private static Effect readEffect(Element e){return new Effect(Float.parseFloat(e.getAttribute("start")),Float.parseFloat(e.getAttribute("end")),integer(e,"octave"),integer(e,"volume"),integer(e,"fade"),Boolean.parseBoolean(e.getAttribute("silence")),Boolean.parseBoolean(e.getAttribute("reset")),integer(e,"low"),integer(e,"high"),integer(e,"doubles"));}
}
