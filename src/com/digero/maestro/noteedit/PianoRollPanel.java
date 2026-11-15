package com.digero.maestro.noteedit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/** Multi-part piano roll with marquee selection, one-axis drag editing and two-axis zoom. */
public final class PianoRollPanel extends JComponent {
    private static final int TOP=24, KEY_WIDTH=64;
    private int rowHeight=18, quarterWidth=85;
    private NoteDocument document;
    private int layerIndex, snapDivisor=16;
    private boolean showAllParts;
    private final java.util.Map<Integer,boolean[]> partStates=new java.util.HashMap<>();
    public void setPartMuteSolo(int partNumber,boolean muted,boolean soloed){
        partStates.put(partNumber,new boolean[]{muted,soloed});repaint();
    }
    public boolean isPartAudible(int partNumber){
        boolean[] state=partStates.getOrDefault(partNumber,new boolean[2]);
        if(state[0])return false;
        return partStates.values().stream().noneMatch(a->a[1]) || state[1];
    }
    private record Selection(int layer, UUID id) {}
    private record Hit(int layer, EditableNote note) {}
    private final Set<Selection> selected = new LinkedHashSet<>();
    private Runnable onEdit=()->{}, onSeek=()->{}, onTogglePlayback=()->{}, onZoom=()->{};
    private java.util.function.Consumer<String> onClipboardMessage=message->{};
    private final SpaceKeyLatch spaceKeyLatch = new SpaceKeyLatch();
    private java.util.function.BiConsumer<EditableNote,NoteDocument.Layer> onAudition=(n,l)->{};
    private int[] detectedScale = {0,2,4,5,7,9,11};
    private String detectedKey="C major";
    private Point anchor, pointer;
    private Rectangle marquee;
    private int previewPitch;
    private long previewTick, playheadTick;
    private boolean dragging, resizing, seeking, selectingBox;

    public PianoRollPanel() {
        setOpaque(true);
        setFocusable(true);
        setToolTipText("Drag to move/resize; double-click to add; right-click a note to delete; Shift extends box selections.");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) deleteAt(e.getPoint());
                else pressed(e);
            }
            @Override public void mouseDragged(MouseEvent e) { dragged(e); }
            @Override public void mouseReleased(MouseEvent e) { released(); }
            @Override public void mouseClicked(MouseEvent e) { if(e.getClickCount()==2 && SwingUtilities.isLeftMouseButton(e) && e.getY()>=TOP) add(e); }
            @Override public void mouseWheelMoved(MouseWheelEvent e) { zoom(e); }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        bind(KeyEvent.VK_DELETE,0,"delete",this::removeSelected);
        bind(KeyEvent.VK_BACK_SPACE,0,"delete2",this::removeSelected);
        bind(KeyEvent.VK_C,InputEvent.CTRL_DOWN_MASK,"copy",this::copySelection);
        bind(KeyEvent.VK_X,InputEvent.CTRL_DOWN_MASK,"cut",this::cutSelection);
        bind(KeyEvent.VK_V,InputEvent.CTRL_DOWN_MASK,"paste",this::pasteClipboard);
        bind(KeyEvent.VK_A,InputEvent.CTRL_DOWN_MASK,"selectAll",this::selectAllVisibleNotes);
        bind(KeyEvent.VK_Z,InputEvent.CTRL_DOWN_MASK,"undo",()->{if(document!=null){document.undo(); notifyEdit();}});
        bind(KeyEvent.VK_Y,InputEvent.CTRL_DOWN_MASK,"redo",()->{if(document!=null){document.redo(); notifyEdit();}});
        // Embedded Maestro owns the space shortcut. A guarded local binding is
        // retained only for the standalone editor, avoiding double toggles from
        // the application's window-wide shortcut on the same physical press.
        getActionMap().put("play",new AbstractAction(){
            @Override public void actionPerformed(ActionEvent e){
                if(spaceKeyLatch.press())onTogglePlayback.run();
            }
        });
        getActionMap().put("playRelease",new AbstractAction(){
            @Override public void actionPerformed(ActionEvent e){spaceKeyLatch.release();}
        });
        setSpaceShortcutEnabled(true);
        addFocusListener(new FocusAdapter(){
            @Override public void focusLost(FocusEvent e){spaceKeyLatch.release();}
        });
        bind(KeyEvent.VK_HOME,0,"begin",()->{setPlayheadTick(0);onSeek.run();});
        bind(KeyEvent.VK_ESCAPE,0,"clear",()->{selected.clear();repaint();});
    }
    private void bind(int key,int modifiers,String action,Runnable run) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key,modifiers),action);
        getActionMap().put(action,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){run.run();}});
    }
    /** Disable this component's Space binding while embedded in Maestro, so
     *  ProjectFrame is the single owner of the shortcut in that window. */
    public void setSpaceShortcutEnabled(boolean enabled){
        InputMap keys=getInputMap(WHEN_FOCUSED);
        KeyStroke pressed=KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,0,false);
        KeyStroke released=KeyStroke.getKeyStroke(KeyEvent.VK_SPACE,0,true);
        keys.remove(pressed);
        keys.remove(released);
        spaceKeyLatch.release();
        // Embedded Maestro's configurable shortcuts replace local fixed
        // bindings; otherwise remapping Ctrl+Z/Home/Delete would leave the old
        // piano-roll shortcut active when the roll has keyboard focus.
        int ctrl=InputEvent.CTRL_DOWN_MASK;
        KeyStroke[] editorial = {
            KeyStroke.getKeyStroke(KeyEvent.VK_DELETE,0),
            KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE,0),
            KeyStroke.getKeyStroke(KeyEvent.VK_Z,ctrl),
            KeyStroke.getKeyStroke(KeyEvent.VK_Y,ctrl),
            KeyStroke.getKeyStroke(KeyEvent.VK_HOME,0),
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),
            KeyStroke.getKeyStroke(KeyEvent.VK_C,ctrl),
            KeyStroke.getKeyStroke(KeyEvent.VK_X,ctrl),
            KeyStroke.getKeyStroke(KeyEvent.VK_V,ctrl),
            KeyStroke.getKeyStroke(KeyEvent.VK_A,ctrl)
        };
        if (!enabled) for(KeyStroke stroke:editorial) keys.remove(stroke);
        else {
            keys.put(editorial[0],"delete");
            keys.put(editorial[1],"delete2");
            keys.put(editorial[2],"undo");
            keys.put(editorial[3],"redo");
            keys.put(editorial[4],"begin");
            keys.put(editorial[5],"clear");
            keys.put(editorial[6],"copy");
            keys.put(editorial[7],"cut");
            keys.put(editorial[8],"paste");
            keys.put(editorial[9],"selectAll");
        }
        if(enabled){
            keys.put(pressed,"play");
            keys.put(released,"playRelease");
        }
    }
    public void clearSelection(){ selected.clear(); repaint(); }
    public void setOnClipboardMessage(java.util.function.Consumer<String> cb){
        onClipboardMessage=java.util.Objects.requireNonNull(cb);
    }

    /** Select every note currently visible: one part, or all parts if enabled. */
    public void selectAllVisibleNotes(){
        selected.clear();
        if(document!=null) for(int layer:visibleLayers())
            for(EditableNote note:document.layer(layer).notes)
                selected.add(new Selection(layer,note.id()));
        repaint();
        onClipboardMessage.accept(selected.size()+" note(s) selected");
    }

    /** Copy all selected parts together, retaining note timing relative to the earliest note. */
    public boolean copySelection(){
        if(document==null || selected.isEmpty())return false;
        long earliest=Long.MAX_VALUE;
        for(Selection s:selected)for(EditableNote note:document.layer(s.layer()).notes)
            if(note.id().equals(s.id()))earliest=Math.min(earliest,note.start());
        if(earliest==Long.MAX_VALUE)return false;
        List<NoteClipboard.Entry> copy=new ArrayList<>();
        for(int layer=0;layer<document.layers.size();layer++){
            NoteDocument.Layer part=document.layer(layer);
            for(EditableNote note:part.notes)
                if(selected.contains(new Selection(layer,note.id())))
                    copy.add(new NoteClipboard.Entry(layer,part.partNumber,note.pitch(),note.velocity(),
                            note.channel(),note.start()-earliest,note.end()-note.start()));
        }
        NoteClipboard.put(new NoteClipboard.Contents(document.kind,document.ppq,copy));
        onClipboardMessage.accept(copy.size()+" note(s) copied");
        return true;
    }

    /** Cut is one undoable document edit, and does nothing when copying fails. */
    public void cutSelection(){
        if(copySelection())removeSelected();
    }

    /** Paste the first selected note at the snapped playhead, preserving relative offsets.
     * Single-part clips target the currently selected part; multi-part clips keep
     * their original MIDI track or ABC X: part number. All notes paste atomically. */
    public boolean pasteClipboard(){
        if(document==null)return false;
        NoteClipboard.Contents clip=NoteClipboard.get();
        if(clip==null){onClipboardMessage.accept("Clipboard does not contain Maestro notes");return false;}
        Set<Integer> originalLayers=new HashSet<>();
        for(NoteClipboard.Entry e:clip.notes())originalLayers.add(e.layer());
        Map<Integer,Integer> mappedLayers=new HashMap<>();
        for(NoteClipboard.Entry e:clip.notes()){
            if(mappedLayers.containsKey(e.layer()))continue;
            int target=-1;
            if(originalLayers.size()==1)target=layerIndex;
            else if(clip.kind()==NoteDocument.Kind.ABC && document.kind==NoteDocument.Kind.ABC
                    && e.partNumber()>0) {
                for(int i=0;i<document.layers.size();i++)
                    if(document.layer(i).partNumber==e.partNumber()) {target=i;break;}
            } else if(clip.kind()==document.kind && e.layer()<document.layers.size())target=e.layer();
            if(target<0){
                onClipboardMessage.accept("Cannot paste: destination is missing one or more copied parts");
                return false;
            }
            mappedLayers.put(e.layer(),target);
        }
        long base=snapped(playheadTick);
        Map<Integer,List<EditableNote>> inserted=new HashMap<>();
        for(NoteClipboard.Entry e:clip.notes()){
            long offset=Math.round(e.startOffset()*(double)document.ppq/clip.ppq());
            long length=Math.max(1,Math.round(e.length()*(double)document.ppq/clip.ppq()));
            if(offset<0 || length<1 || offset>Long.MAX_VALUE-base ||
                    length>Long.MAX_VALUE-(base+offset)){
                onClipboardMessage.accept("Cannot paste: note timing exceeds the supported range");
                return false;
            }
            long start=base+offset;
            inserted.computeIfAbsent(mappedLayers.get(e.layer()),i->new ArrayList<>()).add(
                    EditableNote.create(e.pitch(),e.velocity(),e.channel(),start,start+length));
        }
        Map<Integer,java.util.function.Consumer<List<EditableNote>>> changes=new HashMap<>();
        for(var group:inserted.entrySet())changes.put(group.getKey(),list->list.addAll(group.getValue()));
        document.editLayers(changes);
        selected.clear();
        for(var group:inserted.entrySet())for(EditableNote note:group.getValue())
            selected.add(new Selection(group.getKey(),note.id()));
        notifyEdit();
        onClipboardMessage.accept(clip.notes().size()+" note(s) pasted at playhead");
        return true;
    }
    public void setOnEdit(Runnable cb){onEdit=cb;}
    public void setOnZoom(Runnable cb){onZoom=cb;}
    public void setOnAudition(java.util.function.BiConsumer<EditableNote,NoteDocument.Layer> cb){onAudition=cb;}
    public String getDetectedKey(){return detectedKey;}
    public void fitFullPitchRange(){
        JViewport v=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
        if(v!=null){ rowHeight=Math.max(3,Math.min(18,(v.getHeight()-2)/128));
            revalidate();repaint(); v.setViewPosition(new Point(v.getViewPosition().x,0)); }
    }
    public void setSelectedVelocity(int velocity){
        if(document==null||selected.isEmpty())return;
        int value=Math.max(1,Math.min(127,velocity));
        Map<Integer,java.util.function.Consumer<List<EditableNote>>> changes=new HashMap<>();
        for(Selection key:selected)changes.putIfAbsent(key.layer(),list->{
            for(int i=0;i<list.size();i++){EditableNote n=list.get(i);
                if(selected.contains(new Selection(key.layer(),n.id())))
                    list.set(i,new EditableNote(n.id(),n.pitch(),value,n.channel(),n.start(),n.end()));
            }
        });
        document.editLayers(changes);notifyEdit();
    }
    /** Fixed horizontal key labels in the scroll pane row header. They scroll vertically
     *  together with the notes, but never disappear during horizontal panning. */
    public JComponent createStickyKeyboard(){
        return new JComponent(){
            @Override public Dimension getPreferredSize(){
                return new Dimension(KEY_WIDTH, PianoRollPanel.this.getPreferredSize().height);
            }
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();
                try{
                    Rectangle clip=g.getClipBounds();
                    g.setColor(new Color(27,30,38));g.fillRect(clip.x,clip.y,clip.width,clip.height);
                    int first=Math.min(127,Math.max(0,127-(clip.y-TOP)/rowHeight+2));
                    int last=Math.max(0,127-(clip.y+clip.height-TOP)/rowHeight-2);
                    for(int pitch=first;pitch>=last;pitch--){
                        int yy=y(pitch);
                        boolean black=Set.of(1,3,6,8,10).contains(pitch%12);
                        g.setColor(black?new Color(49,54,66):new Color(73,79,91));
                        g.fillRect(0,yy,KEY_WIDTH,rowHeight);
                        g.setColor(new Color(28,31,38));g.drawLine(0,yy,KEY_WIDTH,yy);
                        // At the default full-range zoom individual rows are too short
                        // for readable text. Label octave C rows, and show more when zoomed in.
                        boolean label=pitch%12==0 || rowHeight>=16;
                        if(label){
                            int fontSize=rowHeight>=12?Math.min(12,rowHeight-2):9;
                            String[] names={"C","C#","D","Eb","E","F","F#","G","Ab","A","Bb","B"};
                            String name=names[pitch%12]+(pitch/12-1);
                            g.setFont(g.getFont().deriveFont((float)fontSize));
                            g.setColor(new Color(222,228,238));
                            int baseline=yy+Math.max(fontSize,Math.min(rowHeight-1,(rowHeight+fontSize)/2));
                            g.drawString(name,5,baseline);
                        }
                    }
                } finally {g.dispose();}
            }
        };
    }
    /** Ruler is a JScrollPane column header: its playhead remains visible while scrolling vertically. */
    public JComponent createStickyRuler(){
        JComponent ruler=new JComponent(){
            @Override public Dimension getPreferredSize(){return new Dimension(PianoRollPanel.this.getPreferredSize().width,26);}
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();
                try{
                    Rectangle view=g.getClipBounds();
                    g.setColor(new Color(32,36,47));g.fillRect(view.x,0,view.width,getHeight());
                    if(document==null)return;
                    long measure=document.measureTicks(layerIndex);
                    long first=Math.max(0,tick(view.x)/measure);
                    long last=Math.max(first,tick(view.x+view.width)/measure+2);
                    for(long bar=first;bar<=last;bar++){
                        int xx=x(bar*measure);
                        g.setColor(new Color(120,133,153));g.drawLine(xx,16,xx,25);
                        g.setColor(Color.WHITE);g.drawString(Long.toString(bar+1),xx+4,13);
                    }
                    int cursor=x(playheadTick);
                    g.setColor(new Color(255,208,77));
                    g.fillPolygon(new int[]{cursor-7,cursor+7,cursor},new int[]{4,4,17},3);
                    g.drawLine(cursor,17,cursor,25);
                } finally{g.dispose();}
            }
        };
        MouseAdapter mouse=new MouseAdapter(){
            private void seek(MouseEvent e){if(document!=null){setPlayheadTick(snapped(tick(e.getX())));onSeek.run();ruler.repaint();}}
            @Override public void mousePressed(MouseEvent e){seek(e);}
            @Override public void mouseDragged(MouseEvent e){seek(e);}
        };
        ruler.addMouseListener(mouse);ruler.addMouseMotionListener(mouse);
        addPropertyChangeListener("preferredSize",e->ruler.revalidate());
        javax.swing.Timer timer=new javax.swing.Timer(100,e->{if(ruler.isShowing())ruler.repaint();});
        ruler.addHierarchyListener(e->{
            if((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0){
                if(ruler.isShowing())timer.start();else timer.stop();
            }
        });
        return ruler;
    }
    private void detectKey(){
        if(document==null)return;
        long[] weight=new long[12];
        for(int i:visibleLayers())for(EditableNote n:document.layer(i).notes)
            weight[n.pitch()%12]+=Math.max(1,n.end()-n.start());
        int[] major={0,2,4,5,7,9,11},minor={0,2,3,5,7,8,10};
        String[] names={"C","C#","D","Eb","E","F","F#","G","Ab","A","Bb","B"};
        long best=Long.MIN_VALUE;
        for(int root=0;root<12;root++)for(int mode=0;mode<2;mode++){
            int[] intervals=mode==0?major:minor;long score=0;
            for(int pitch=0;pitch<12;pitch++){
                boolean in=false;for(int degree:intervals)if((degree+root)%12==pitch){in=true;break;}
                score+=weight[pitch]*(in?2:-3);
            }
            if(score>best){best=score;detectedScale=new int[7];
                for(int j=0;j<7;j++)detectedScale[j]=(intervals[j]+root)%12;
                detectedKey=names[root]+(mode==0?" major":" minor");}
        }
    }
    private boolean inScale(int note){for(int p:detectedScale)if(note%12==p)return true;return false;}
    public void setOnSeek(Runnable cb){onSeek=cb;}
    public void setOnTogglePlayback(Runnable cb){onTogglePlayback=cb;}
    public void setDocument(NoteDocument doc,int index){document=doc;layerIndex=index;selected.clear();detectKey();revalidate();repaint();}
    public void setShowAllParts(boolean enabled){showAllParts=enabled;selected.clear();detectKey();revalidate();repaint();}
    public boolean isShowingAllParts(){return showAllParts;}
    public void setPlayheadTick(long tick) {long normalized=Math.max(0,tick);if(playheadTick!=normalized){playheadTick=normalized;repaint();}}
    public long getPlayheadTick(){return playheadTick;}
    public int playheadPixel(){return x(playheadTick);}
    public void setSnapDivisor(int divisor){if(divisor<1)throw new IllegalArgumentException("divisor");snapDivisor=divisor;repaint();}
    /** A divisor is a fraction of the CURRENT MEASURE, not a quarter-note beat. */
    public int getSnapDivisor(){return snapDivisor;}
    private long measure(){return document.measureTicks(layerIndex);}
    private long gridTick(long index){return Math.max(0,Math.round(index * (double)measure()/snapDivisor));}
    private long snapped(long t){return gridTick(Math.round(t*snapDivisor/(double)measure()));}
    private long step(){return Math.max(1,Math.round(measure()/(double)snapDivisor));}
    private double scale(){return quarterWidth/(double)document.ppq;}
    private int x(long tick){return KEY_WIDTH+(int)Math.round(tick*scale());}
    private long tick(int px){return Math.max(0,Math.round((px-KEY_WIDTH)/scale()));}
    private int y(int pitch){return TOP+(127-pitch)*rowHeight;}
    private int pitch(int py){return Math.max(0,Math.min(127,127-(py-TOP)/rowHeight));}
    private List<Integer> visibleLayers(){
        if(document==null)return List.of();
        if(!showAllParts)return List.of(Math.max(0,Math.min(layerIndex, document.layers.size()-1)));
        List<Integer> ids=new ArrayList<>();for(int i=0;i<document.layers.size();i++)ids.add(i);
        return ids;
    }
    private List<EditableNote> notes(){return document==null?List.of():document.layer(layerIndex).notes;}
    private Color colorFor(int layer){
        String instrument=document.layer(layer).instrumentName;
        int hash=(instrument.isBlank()?document.layer(layer).name:instrument).toLowerCase(Locale.ROOT).hashCode();
        float hue=((hash & 0x7fffffff)%360)/360f;
        return Color.getHSBColor(hue,0.59f,0.89f);
    }
    public Color getPartColor(int layer){return document==null?Color.GRAY:colorFor(layer);}
    @Override public Dimension getPreferredSize(){
        long max=0;for(int i:visibleLayers())for(EditableNote n:document.layer(i).notes)max=Math.max(max,n.end());
        double w=document==null?1:scale();
        return new Dimension((int)Math.min(200000,Math.max(1200,KEY_WIDTH+(max+(document==null?0:document.ppq*8L))*w)),TOP+128*rowHeight);
    }
    public void scrollToMiddleC(){scrollRectToVisible(new Rectangle(0,y(72)-rowHeight*12,600,rowHeight*24));}
    @Override public String getToolTipText(MouseEvent e){
        Hit h=hit(e.getPoint());
        if(h==null)return getToolTipText();
        NoteDocument.Layer layer=document.layer(h.layer);
        boolean playable=document.kind!=NoteDocument.Kind.ABC ||
            LotroPatch.isPlayableSoundingPitch(layer.instrumentName,h.note.pitch(),layer.abcPitchShift);
        return layer.name+" | Pitch "+h.note.pitch()+" | Tick "+h.note.start()+"-"+h.note.end()+
               (playable?"":" | OUTSIDE LOTRO PLAYABLE RANGE");
    }
    @Override protected void paintComponent(Graphics g0){
        Graphics2D g=(Graphics2D)g0.create();
        try{
            Rectangle view=g.getClipBounds();g.setColor(new Color(27,30,38));g.fillRect(view.x,view.y,view.width,view.height);
            if(document==null || document.layers.isEmpty()){g.setColor(Color.WHITE);g.drawString("Open a MIDI or ABC file to edit notes",30,35);return;}
            int first=Math.min(127,Math.max(0,127-(view.y-TOP)/rowHeight+2));
            int last=Math.max(0,127-(view.y+view.height-TOP)/rowHeight-2);
            for(int p=first;p>=last;p--){
                int yy=y(p);boolean black=Set.of(1,3,6,8,10).contains(p%12);
                g.setColor(inScale(p)?(black?new Color(40,48,60):new Color(54,62,74)):(black?new Color(26,29,37):new Color(32,36,44)));g.fillRect(KEY_WIDTH,yy,getWidth()-KEY_WIDTH,rowHeight);
                g.setColor(new Color(23,26,33));g.drawLine(0,yy,getWidth(),yy);
            }
            long left=tick(view.x),right=tick(view.x+view.width);
            long measure=measure();
            long begin=(long)Math.floor(left*snapDivisor/(double)measure);
            long finish=(long)Math.ceil(right*snapDivisor/(double)measure)+1;
            for(long index=begin;index<=finish;index++){
                long t=gridTick(index);boolean bar=t%measure==0,beat=t%document.ppq==0;
                g.setColor(bar?new Color(127,137,156):beat?new Color(94,102,116):new Color(65,72,84));
                int xx=x(t);g.drawLine(xx,Math.max(view.y,TOP),xx,view.y+view.height);
                // Measure numbers belong exclusively in the sticky ruler.
                if(index==Long.MAX_VALUE)break;
            }
            for(int i:visibleLayers()) {
                Color base=isPartAudible(document.layer(i).partNumber)?colorFor(i):
                    new Color(90,95,108);
                for(EditableNote n:document.layer(i).notes){
                    Selection key=new Selection(i,n.id());boolean active=selected.contains(key);
                    int delta=dragging && active?previewPitch:0;
                    long dt=dragging && active && !resizing?previewTick:0;
                    long endChange=dragging && active && resizing?previewTick:0;
                    int p=Math.max(0,Math.min(127,n.pitch()+delta));
                    int xx=x(Math.max(0,n.start()+dt));
                    int rightX=x(Math.max(n.start()+dt+1,n.end()+dt+endChange));
                    int yy=y(p),width=Math.max(3,rightX-xx);
                    if(xx>view.x+view.width||rightX<view.x||yy>view.y+view.height||yy+rowHeight<view.y)continue;
                    // Invalid LOTRO notes must not look like normal playable notes.
                    // Drum/cowbell timbres and fiddle FX remain instrument-specific.
                    NoteDocument.Layer noteLayer=document.layer(i);
                    boolean playable=document.kind!=NoteDocument.Kind.ABC ||
                        LotroPatch.isPlayableSoundingPitch(noteLayer.instrumentName,p,noteLayer.abcPitchShift);
                    g.setColor(playable?base:new Color(181,79,83));
                    g.fillRoundRect(xx,yy+2,width,rowHeight-3,4,4);
                    g.setColor(active?new Color(255,229,117):
                        playable?base.darker().darker():new Color(255,153,158));
                    g.setStroke(active?new BasicStroke(2):new BasicStroke(1));
                    g.drawRoundRect(xx,yy+2,width-1,rowHeight-4,4,4);
                    if(width>28 && rowHeight>12){g.setColor(new Color(15,26,43));g.drawString(Integer.toString(n.pitch()),xx+5,yy+Math.min(14,rowHeight-3));}
                }
            }
            int playX=x(playheadTick);
            if(playX>=view.x&&playX<=view.x+view.width){
                g.setColor(new Color(255,208,77));g.setStroke(new BasicStroke(2));
                g.drawLine(playX,TOP,playX,Math.min(getHeight(),view.y+view.height));
                // The triangle is drawn only in the sticky ruler; no duplicate here.
            }
            if(marquee!=null){g.setColor(new Color(142,189,255,60));g.fill(marquee);
                g.setColor(new Color(168,207,255));g.setStroke(new BasicStroke(1.5f));g.draw(marquee);}
            // The pinned keyboard and pinned timeline draw their own corner.
        }finally{g.dispose();}
    }
    private Hit hit(Point p){
        if(document==null||p.y<TOP)return null;
        List<Integer> ids=visibleLayers();
        for(int index=ids.size()-1;index>=0;index--){
            int i=ids.get(index);List<EditableNote> all=document.layer(i).notes;
            for(int n=all.size()-1;n>=0;n--){EditableNote note=all.get(n);
                if(p.y>=y(note.pitch())&&p.y<y(note.pitch())+rowHeight&&p.x>=x(note.start())&&p.x<=x(note.end()))return new Hit(i,note);
            }
        }
        return null;
    }
    private void pressed(MouseEvent e){
        if(document==null||!SwingUtilities.isLeftMouseButton(e))return;
        requestFocusInWindow();anchor=e.getPoint();pointer=e.getPoint();dragging=false;selectingBox=false;
        seeking=e.getY()<TOP || Math.abs(e.getX()-x(playheadTick))<=4 && e.isAltDown();
        if(seeking){setPlayheadTick(snapped(tick(e.getX())));onSeek.run();return;}
        Hit h=hit(e.getPoint());previewTick=0;previewPitch=0;resizing=false;
        if(h==null){if(!e.isShiftDown())selected.clear();marquee=new Rectangle(e.getX(),e.getY(),0,0);repaint();return;}
        Selection key=new Selection(h.layer,h.note.id());
        if(e.isShiftDown()) { if(!selected.add(key)) selected.remove(key); }
        else if(!selected.contains(key)){selected.clear();selected.add(key);}
        resizing=x(h.note.end())-e.getX()<8&&x(h.note.end())-e.getX()>=-3;
        onAudition.accept(h.note,document.layer(h.layer));
        repaint();
    }
    private void dragged(MouseEvent e){
        if(document==null||anchor==null)return;
        pointer=e.getPoint();
        if(seeking){setPlayheadTick(snapped(tick(e.getX())));onSeek.run();return;}
        if(marquee!=null){
            selectingBox=true;
            int x=Math.min(anchor.x,e.getX()),y=Math.min(anchor.y,e.getY());
            marquee=new Rectangle(x,y,Math.abs(e.getX()-anchor.x),Math.abs(e.getY()-anchor.y));repaint();return;
        }
        if(selected.isEmpty())return;
        int dx=e.getX()-anchor.x,dy=e.getY()-anchor.y;
        if(Math.abs(dx)<3&&Math.abs(dy)<3&&!dragging)return;
        dragging=true;
        previewTick=Math.round((dx/scale())/step())*step();
        previewPitch=resizing?0:-Math.round(dy/(float)rowHeight);
        repaint();
    }
    private void released(){
        if(document==null){anchor=null;return;}
        if(marquee!=null){
            if(selectingBox && marquee.width>1 && marquee.height>1){
                for(int layer:visibleLayers())for(EditableNote n:document.layer(layer).notes){
                    Rectangle bounds=new Rectangle(x(n.start()),y(n.pitch()),Math.max(3,x(n.end())-x(n.start())),rowHeight);
                    if(marquee.intersects(bounds))selected.add(new Selection(layer,n.id()));
                }
            }
            marquee=null;
        } else if(dragging&&(previewTick!=0||previewPitch!=0)){
            final long dt=previewTick;final int dp=previewPitch;final boolean resize=resizing;
            Map<Integer,java.util.function.Consumer<List<EditableNote>>> changes=new HashMap<>();
            for(Selection key:selected) changes.putIfAbsent(key.layer(), list->{
                for(int j=0;j<list.size();j++){EditableNote n=list.get(j);
                    if(!selected.contains(new Selection(key.layer(),n.id())))continue;
                    list.set(j,resize?n.resize(n.end()+dt):n.move(dp,dt));
                }
            });
            document.editLayers(changes);notifyEdit();
            if(!selected.isEmpty()){Selection first=selected.iterator().next();
                for(EditableNote n:document.layer(first.layer()).notes)
                    if(n.id().equals(first.id())){onAudition.accept(n,document.layer(first.layer()));break;}}
        }
        anchor=null;pointer=null;dragging=false;selectingBox=false;seeking=false;
        previewTick=0;previewPitch=0;repaint();
    }
    private void add(MouseEvent e){
        if(document==null || hit(e.getPoint())!=null)return;
        long start=snapped(tick(e.getX())),length=step();
        int channel=notes().isEmpty()?0:notes().getFirst().channel();
        EditableNote note=EditableNote.create(pitch(e.getY()),90,channel,start,start+length);
        document.edit(layerIndex,list->list.add(note));
        selected.clear();selected.add(new Selection(layerIndex,note.id()));
        // notifyEdit may stop the old playback/synth. Sound the new note AFTER it.
        notifyEdit();onAudition.accept(note,document.layer(layerIndex));
    }
    /** Right-click removes only the note under the pointer as one undoable edit. */
    public boolean deleteAt(Point point){
        if(document==null)return false;
        Hit target=hit(point);
        if(target==null)return false;
        document.edit(target.layer(), notes->notes.removeIf(n->n.id().equals(target.note().id())));
        selected.remove(new Selection(target.layer(),target.note().id()));
        notifyEdit();
        return true;
    }
    public int selectionSize(){return selected.size();}
    public void removeSelected(){
        if(document==null||selected.isEmpty())return;
        Map<Integer,java.util.function.Consumer<List<EditableNote>>> changes=new HashMap<>();
        for(Selection key:selected)changes.putIfAbsent(key.layer(),list->list.removeIf(n->selected.contains(new Selection(key.layer(),n.id()))));
        document.editLayers(changes);selected.clear();notifyEdit();
    }
    private void zoom(MouseWheelEvent e){
        if(!e.isControlDown()) {
            JScrollPane pane=(JScrollPane)SwingUtilities.getAncestorOfClass(JScrollPane.class,this);
            if(pane!=null){
                JScrollBar bar=pane.getVerticalScrollBar();
                bar.setValue(bar.getValue()+(int)Math.round(e.getPreciseWheelRotation()*rowHeight*3));
                e.consume();
            }
            return;
        }
        e.consume();
        double factor=e.getPreciseWheelRotation()<0?1.17:1/1.17;
        int oldWidth=quarterWidth,oldHeight=rowHeight;
        quarterWidth=Math.max(20,Math.min(450,(int)Math.round(quarterWidth*factor)));
        rowHeight=Math.max(8,Math.min(70,(int)Math.round(rowHeight*factor)));
        if(oldWidth==quarterWidth&&oldHeight==rowHeight)return;
        JViewport viewport=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
        Point origin=viewport==null?null:viewport.getViewPosition();
        int px=e.getX(),py=e.getY();
        int newX=(int)Math.round(KEY_WIDTH+(px-KEY_WIDTH)*quarterWidth/(double)oldWidth);
        int newY=(int)Math.round(TOP+(py-TOP)*rowHeight/(double)oldHeight);
        revalidate();repaint();onZoom.run();
        if(viewport!=null&&origin!=null){
            int vx=Math.max(0,origin.x+newX-px),vy=Math.max(0,origin.y+newY-py);
            SwingUtilities.invokeLater(()->{
                int maxX=Math.max(0,getWidth()-viewport.getWidth()),maxY=Math.max(0,getHeight()-viewport.getHeight());
                viewport.setViewPosition(new Point(Math.min(vx,maxX),Math.min(vy,maxY)));
            });
        }
    }
    private void notifyEdit(){detectKey();onEdit.run();revalidate();repaint();}
}
