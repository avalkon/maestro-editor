package com.digero.maestro.noteedit;

import javax.swing.*;
import java.awt.*;
import java.awt.font.GlyphVector;
import java.awt.event.*;
import java.util.*;
import java.util.List;

/** Multi-part piano roll with marquee selection, one-axis drag editing and two-axis zoom. */
public final class PianoRollPanel extends JComponent {
    private static final int TOP=24, KEY_WIDTH=64, DEFAULT_DRUM_KEY_WIDTH=120;
    private static final String[] PITCH_NAMES={"C","C#","D","Eb","E","F","F#","G","Ab","A","Bb","B"};
    private static final int MIN_KEY_LABEL_SIZE=9, MAX_KEY_LABEL_SIZE=12;
    private static final Color RULER_BACKGROUND=new Color(32,36,47);
    private static final Color NOTE_LABEL_COLOR=new Color(15,26,43);
    private static String pitchName(int pitch){return PITCH_NAMES[pitch%12]+(pitch/12-1);}
    private record NoteLabel(GlyphVector glyph, Rectangle ink) {}
    /** Choose the largest readable label that fits the note's interior. */
    private static NoteLabel fittingNoteLabel(Graphics2D g,String text,int width,int height){
        if(text==null || width<1 || height<1)return null;
        Font base=g.getFont();
        for(int size=MAX_KEY_LABEL_SIZE;size>=MIN_KEY_LABEL_SIZE;size--){
            GlyphVector glyph=base.deriveFont((float)size).createGlyphVector(g.getFontRenderContext(),text);
            Rectangle ink=glyph.getVisualBounds().getBounds();
            if(ink.width<=width && ink.height<=height)return new NoteLabel(glyph,ink);
        }
        return null;
    }
    private int rowHeight=18, quarterWidth=85;
    private NoteDocument document;
    private JComponent stickyKeyboard;
    private JViewport floatingViewport;
    private DrumLabels.Kit keyboardKit=DrumLabels.Kit.NONE;
    private int keyboardWidth=KEY_WIDTH, drumKeyboardWidth=DEFAULT_DRUM_KEY_WIDTH;
    /** Match the wider percussion gutter to the toolbar separator after layout. */
    void setDrumKeyboardWidth(int width){
        if(width<=0 || drumKeyboardWidth==width)return;
        drumKeyboardWidth=width;refreshKeyboard();
    }
    void refreshKeyboard(){
        keyboardKit=document==null || layerIndex<0 || layerIndex>=document.layers.size()
            ? DrumLabels.Kit.NONE : DrumLabels.keyboardKit(document,document.layer(layerIndex));
        // Keep the gutter the same width when switching parts in a drum-containing document.
        int width=DrumLabels.hasDrums(document)?drumKeyboardWidth:KEY_WIDTH;
        boolean resized=keyboardWidth!=width;
        keyboardWidth=width;
        if(stickyKeyboard!=null){
            if(resized){
                stickyKeyboard.revalidate();
                revalidate();
                if(floatingViewport!=null) floatingViewport.revalidate();
                positionFloatingKeyboard();
            }
            stickyKeyboard.repaint();
        }
        repaint();
    }
    private String drumKeyName(int pitch){
        return DrumLabels.name(keyboardKit,pitch,document.layer(layerIndex).abcPitchShift);
    }
    private String noteLabel(NoteDocument.Layer layer,EditableNote note,int pitch){
        DrumLabels.Kit kit=DrumLabels.noteKit(document,layer,note);
        if(kit==DrumLabels.Kit.NONE)return pitchName(pitch);
        String name=DrumLabels.name(kit,pitch,layer.abcPitchShift);
        return name!=null?name:(kit==DrumLabels.Kit.LOTRO?"Unmapped drum":"Drum "+pitch);
    }
    private int layerIndex, snapDivisor=16;
    private boolean showAllParts, showOnlyAudible;
    private final java.util.Map<Integer,boolean[]> partStates=new java.util.HashMap<>();
    public void setPartMuteSolo(int partNumber,boolean muted,boolean soloed){
        partStates.put(partNumber,new boolean[]{muted,soloed});
        audibilityChanged();
    }
    /** Replace the complete snapshot so deleted/renumbered solos cannot hide parts. */
    public void setPartMuteSoloStates(Map<Integer,boolean[]> states){
        partStates.clear();
        states.forEach((number,flags)->partStates.put(number,flags.clone()));
        audibilityChanged();
    }
    public boolean isPartAudible(int partNumber){
        boolean[] state=partStates.getOrDefault(partNumber,new boolean[2]);
        // Match AbcPart.isActive() and the shared preview sequencer: solo wins.
        if(state[1])return true;
        return !state[0] && partStates.values().stream().noneMatch(a->a[1]);
    }
    private boolean audibleFilterActive(){
        return document!=null && document.kind==NoteDocument.Kind.ABC && showAllParts && showOnlyAudible;
    }
    public void setShowOnlyAudible(boolean enabled){
        showOnlyAudible=enabled;
        audibilityChanged();
    }
    public boolean isShowingOnlyAudible(){return showOnlyAudible;}
    private void audibilityChanged(){
        if(audibleFilterActive()){
            Set<Integer> visible=new HashSet<>(visibleLayers());
            selected.removeIf(s->!visible.contains(s.layer()));
        }
        repaint();
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
    private String highlightKey=HighlightKey.AUTO;
    private int[] manualScale;
    public void setHighlightKey(String key) {
        highlightKey=HighlightKey.normalize(key);
        manualScale=HighlightKey.AUTO.equals(highlightKey)?null:HighlightKey.scale(highlightKey);
        repaint();
    }
    public String getHighlightKey(){return highlightKey;}
    public String getEffectiveHighlightKey(){return manualScale==null?detectedKey:highlightKey;}
    private Point anchor, pointer;
    private Rectangle marquee;
    private int previewPitch;
    private long previewTick, playheadTick, dragGridIndex;
    private boolean dragging, resizing, seeking, selectingBox;
    // Store the pointer in viewport coordinates: component coordinates change
    // as the viewport scrolls, even when the physical mouse stays stationary.
    private static final int EDGE_ZONE=32, EDGE_MAX_STEP=28;
    private final javax.swing.Timer edgeTimer=new javax.swing.Timer(25,e->edgeScroll());
    private Point edgePointer;
    private JComponent rulerDrag;
    private Window edgeWindow;
    private final WindowAdapter edgeWindowListener=new WindowAdapter(){
        @Override public void windowLostFocus(WindowEvent e){cancelDragInteraction();}
    };

    boolean isDragInteractionActive(){return anchor!=null || rulerDrag!=null;}
    private JViewport dragViewport(){
        return (JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,this);
    }
    private void stopEdgeScroll(){
        edgeTimer.stop();edgePointer=null;
        if(edgeWindow!=null){edgeWindow.removeWindowFocusListener(edgeWindowListener);edgeWindow=null;}
    }
    void cancelDragInteraction(){
        stopEdgeScroll();rulerDrag=null;anchor=null;pointer=null;marquee=null;
        dragging=false;resizing=false;seeking=false;selectingBox=false;
        previewTick=0;previewPitch=0;repaint();
    }
    private void trackEdgePointer(MouseEvent e,JViewport viewport){
        if(viewport==null)return;
        edgePointer=SwingUtilities.convertPoint(e.getComponent(),e.getPoint(),viewport);
        if(!edgeTimer.isRunning() && isShowing()){
            edgeWindow=SwingUtilities.getWindowAncestor(this);
            if(edgeWindow!=null)edgeWindow.addWindowFocusListener(edgeWindowListener);
            edgeTimer.start();
        }
    }
    private static int edgeStep(int point,int start,int end){
        if(end<=start)return 0;
        int zone=Math.min(EDGE_ZONE,Math.max(1,(end-start)/2));
        int depth=point<start+zone?point-start-zone:point>end-zone?point-end+zone:0;
        if(depth==0)return 0;
        int speed=Math.min(EDGE_MAX_STEP,Math.max(1,(int)Math.round(EDGE_MAX_STEP*Math.abs((double)depth)/zone)));
        return depth<0?-speed:speed;
    }
    private static int clamp(int value,int min,int max){return Math.max(min,Math.min(max,value));}
    private Point gridDragPoint(JViewport viewport,Point local){
        Point origin=viewport.getViewPosition();
        int left=floatingViewport==null?Math.max(0,keyboardWidth-origin.x):keyboardWidth;
        int right=Math.max(left,viewport.getWidth()-1);
        int top=Math.max(0,TOP-origin.y),bottom=Math.max(top,viewport.getHeight()-1);
        return new Point(origin.x+clamp(local.x,left,right),origin.y+clamp(local.y,top,bottom));
    }
    private void seekDragAt(int px,JComponent ruler,boolean force){
        if(document==null)return;
        long target=snapped(tick(px));
        if(force || target!=playheadTick){setPlayheadTick(target);onSeek.run();}
        if(ruler!=null)ruler.repaint();
    }
    private void edgeScroll(){
        if(edgePointer==null || !isDragInteractionActive() || document==null || !isShowing()){
            cancelDragInteraction();return;
        }
        JViewport viewport=dragViewport();
        if(viewport==null){cancelDragInteraction();return;}
        Point old=viewport.getViewPosition();
        boolean horizontalOnly=rulerDrag!=null || seeking || resizing;
        int left=rulerDrag!=null?0:(floatingViewport==null?Math.max(0,keyboardWidth-old.x):keyboardWidth);
        int dx=edgeStep(edgePointer.x,left,viewport.getWidth()-1);
        int dy=horizontalOnly?0:edgeStep(edgePointer.y,Math.max(0,TOP-old.y),viewport.getHeight()-1);
        Dimension size=viewport.getViewSize(), extent=viewport.getExtentSize();
        Point next=new Point(clamp(old.x+dx,0,Math.max(0,size.width-extent.width)),
            clamp(old.y+dy,0,Math.max(0,size.height-extent.height)));
        if(old.equals(next))return;
        viewport.setViewPosition(next);
        if(rulerDrag!=null){
            seekDragAt(viewport.getViewPosition().x+clamp(edgePointer.x,0,viewport.getWidth()-1),rulerDrag,false);
        }else{
            updateDrag(gridDragPoint(viewport,edgePointer));
        }
    }
    private boolean noteClickEnabled;
    private int noteClickModifiers;

    /** Exact modifier combination; normal double-click note creation is unaffected. */
    public void setNoteCreationMouseGesture(boolean enabled, int modifiers) {
        int allowed = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK |
                InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK;
        if ((modifiers & ~allowed) != 0 || (enabled && modifiers == 0))
            throw new IllegalArgumentException("A modifier is required for note click");
        noteClickEnabled = enabled;
        noteClickModifiers = modifiers;
        String extra = enabled ? " or modifier+click" : "";
        setToolTipText("Drag to move/resize; double-click" + extra +
                " to add; right-click a note to delete; Shift extends box selections.");
    }
    private boolean isNoteCreationClick(MouseEvent e) {
        int allowed = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK |
                InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK;
        return noteClickEnabled && noteClickModifiers != 0 &&
                (e.getModifiersEx() & allowed) == noteClickModifiers &&
                SwingUtilities.isLeftMouseButton(e) && e.getX() >= editableLeft() && e.getY() >= TOP;
    }

    public PianoRollPanel() {
        setOpaque(true);
        setFocusable(true);
        // Swing's synthetic autoscroll events lack our floating-keyboard geometry.
        setAutoscrolls(false);
        addHierarchyListener(e->{
            if((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED)!=0 && !isShowing())cancelDragInteraction();
        });
        setToolTipText("Drag to move/resize; double-click to add; right-click a note to delete; Shift extends box selections.");
        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) deleteAt(e.getPoint());
                else pressed(e);
            }
            @Override public void mouseDragged(MouseEvent e) { dragged(e); }
            @Override public void mouseReleased(MouseEvent e) { if(SwingUtilities.isLeftMouseButton(e))released(); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && e.getY() >= TOP)
                    add(e); // Always preserve the existing double-click gesture.
                else if (e.getClickCount() == 1 && isNoteCreationClick(e))
                    add(e);
            }
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
        bind(KeyEvent.VK_HOME,0,"begin",()->{setPlayheadTick(0);revealSongStart();onSeek.run();});
        bind(KeyEvent.VK_ESCAPE,0,"clear",this::clearSelection);
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
    public void clearSelection(){ cancelDragInteraction();selected.clear(); repaint(); }
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
            if(audibleFilterActive() && !visibleLayers().contains(target)){
                onClipboardMessage.accept("Cannot paste into a hidden part; disable Show only audible or use single-part view");
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
            revalidate();repaint();onZoom.run(); v.setViewPosition(new Point(v.getViewPosition().x,0)); }
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
    /** Install a keyboard overlay within the scrolling canvas. Its x coordinate
     * follows the viewport, but its y coordinate remains in pitch-grid space.
     * The ruler has no row-header corner and can seek over the keyboard area. */
    void installFloatingKeyboard(JViewport viewport){
        floatingViewport=viewport;
        // Blitting would copy the previously painted overlay into the note grid.
        viewport.setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        JComponent keys=createStickyKeyboard();
        add(keys);
        keys.addMouseListener(new MouseAdapter(){
            @Override public void mousePressed(MouseEvent e){requestFocusInWindow();}
        });
        keys.addMouseWheelListener(e->{
            zoom((MouseWheelEvent)SwingUtilities.convertMouseEvent(keys,e,this));
            e.consume();
        });
        viewport.addChangeListener(e->positionFloatingKeyboard());
        positionFloatingKeyboard();
    }
    int getKeyboardWidth(){return keyboardWidth;}
    /** Return to the horizontal origin without disturbing the vertical zoom/scroll. */
    void revealSongStart(){
        if(floatingViewport!=null){
            Point p=floatingViewport.getViewPosition();
            floatingViewport.setViewPosition(new Point(0,p.y));
        }
    }
    private int editableLeft(){
        return keyboardWidth+(floatingViewport==null?0:floatingViewport.getViewPosition().x);
    }
    private void positionFloatingKeyboard(){
        if(floatingViewport==null || stickyKeyboard==null)return;
        Rectangle bounds=new Rectangle(floatingViewport.getViewPosition().x,0,keyboardWidth,getHeight());
        if(!bounds.equals(stickyKeyboard.getBounds())){
            stickyKeyboard.setBounds(bounds);
            // Repaint both the old overlay area and the newly covered area.
            floatingViewport.repaint();
        }
    }
    @Override public void doLayout(){positionFloatingKeyboard();}
    /** Keyboard renderer, also usable independently in rendering tests. */
    public JComponent createStickyKeyboard(){
        stickyKeyboard=new JComponent(){
            @Override public Dimension getPreferredSize(){
                return new Dimension(keyboardWidth, PianoRollPanel.this.getPreferredSize().height);
            }
            @Override public String getToolTipText(MouseEvent e){
                if(keyboardKit==DrumLabels.Kit.NONE || e.getY()<TOP)return null;
                int p=pitch(e.getY());String name=drumKeyName(p);
                return (name==null?"Unmapped drum":name)+" ("+pitchName(p)+", MIDI "+p+")";
            }
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();
                try{
                    Rectangle clip=g.getClipBounds();
                    g.setColor(new Color(27,30,38));g.fillRect(clip.x,clip.y,clip.width,clip.height);
                    int first=Math.min(127,Math.max(0,127-(clip.y-TOP)/rowHeight+2));
                    int last=Math.max(0,127-(clip.y+clip.height-TOP)/rowHeight-2);
                    // Measure actual ink, not the font's nominal line height. The
                    // latter includes leading and descender space unused by pitch names.
                    Font baseFont=g.getFont();
                    Font labelFont=baseFont.deriveFont((float)MIN_KEY_LABEL_SIZE);
                    boolean labelAll=false;
                    for(int size=MAX_KEY_LABEL_SIZE;size>=MIN_KEY_LABEL_SIZE;size--){
                        Font candidate=baseFont.deriveFont((float)size);
                        // Contains every character used in the MIDI pitch labels.
                        Rectangle ink=candidate.createGlyphVector(g.getFontRenderContext(),
                                "CDEFGAB#b-0123456789").getVisualBounds().getBounds();
                        if(ink.height<=rowHeight-1){labelFont=candidate;labelAll=true;break;}
                    }
                    g.setFont(labelFont);
                    for(int pitch=first;pitch>=last;pitch--){
                        int yy=y(pitch);
                        boolean black=keyboardKit==DrumLabels.Kit.NONE?Set.of(1,3,6,8,10).contains(pitch%12):pitch%2==1;
                        g.setColor(black?new Color(49,54,66):new Color(73,79,91));
                        g.fillRect(0,yy,getWidth(),rowHeight);
                        g.setColor(new Color(28,31,38));g.drawLine(0,yy,getWidth(),yy);
                        if(keyboardKit!=DrumLabels.Kit.NONE){
                            String name=drumKeyName(pitch);
                            if(name==null)continue;
                            g.setFont(baseFont);
                            NoteLabel label=fittingNoteLabel(g,name,getWidth()-10,rowHeight-1);
                            if(label!=null){
                                Rectangle ink=label.ink();
                                g.setColor(new Color(222,228,238));
                                g.drawGlyphVector(label.glyph(),5-ink.x,yy+1+(rowHeight-1-ink.height)/2-ink.y);
                            }
                            g.setFont(labelFont);
                            continue;
                        }
                        // At extreme zoom-out retain octave landmarks; otherwise
                        // show every pitch as soon as a legible font fits the row.
                        if(pitch%12==0 || labelAll){
                            String name=pitchName(pitch);
                            GlyphVector glyph=labelFont.createGlyphVector(g.getFontRenderContext(),name);
                            Rectangle ink=glyph.getVisualBounds().getBounds();
                            int baseline=yy+1+(rowHeight-1-ink.height)/2-ink.y;
                            g.setColor(new Color(222,228,238));
                            g.drawGlyphVector(glyph,5,baseline);
                        }
                    }
                } finally {g.dispose();}
            }
        };
        stickyKeyboard.setOpaque(true);
        stickyKeyboard.setToolTipText("");
        return stickyKeyboard;
    }
    /** Ruler is a JScrollPane column header: its playhead remains visible while scrolling vertically. */
    public JComponent createStickyRuler(){
        JComponent ruler=new JComponent(){
            @Override public Dimension getPreferredSize(){return new Dimension(PianoRollPanel.this.getPreferredSize().width,26);}
            @Override protected void paintComponent(Graphics graphics){
                Graphics2D g=(Graphics2D)graphics.create();
                try{
                    Rectangle view=g.getClipBounds();
                    g.setColor(RULER_BACKGROUND);g.fillRect(view.x,0,view.width,getHeight());
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
            @Override public void mousePressed(MouseEvent e){
                if(document==null || !SwingUtilities.isLeftMouseButton(e))return;
                cancelDragInteraction();rulerDrag=ruler;seekDragAt(e.getX(),ruler,true);
            }
            @Override public void mouseDragged(MouseEvent e){
                if(rulerDrag!=ruler || document==null)return;
                JViewport header=(JViewport)SwingUtilities.getAncestorOfClass(JViewport.class,ruler);
                trackEdgePointer(e,header);
                int px=header==null?e.getX():header.getViewPosition().x+clamp(edgePointer.x,0,header.getWidth()-1);
                seekDragAt(px,ruler,true);
            }
            @Override public void mouseReleased(MouseEvent e){
                if(rulerDrag==ruler && SwingUtilities.isLeftMouseButton(e))cancelDragInteraction();
            }
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
        for(int i:viewScopeLayers())for(EditableNote n:document.layer(i).notes)
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
    private boolean inScale(int note){for(int p:manualScale==null?detectedScale:manualScale)if(note%12==p)return true;return false;}
    public void setOnSeek(Runnable cb){onSeek=cb;}
    public void setOnTogglePlayback(Runnable cb){onTogglePlayback=cb;}
    public void setDocument(NoteDocument doc,int index){cancelDragInteraction();document=doc;layerIndex=index;selected.clear();refreshKeyboard();detectKey();revalidate();repaint();}
    private int minimumMeasureCount;
    /** New ABC compositions begin with a usable, empty timeline. */
    public void setMinimumMeasureCount(int bars) {
        minimumMeasureCount = Math.max(0,Math.min(2000,bars));
        revalidate(); repaint();
    }
    public void setShowAllParts(boolean enabled){cancelDragInteraction();showAllParts=enabled;selected.clear();detectKey();revalidate();repaint();}
    public boolean isShowingAllParts(){return showAllParts;}
    public void setPlayheadTick(long tick) {long normalized=Math.max(0,tick);if(playheadTick!=normalized){playheadTick=normalized;repaint();}}
    public long getPlayheadTick(){return playheadTick;}
    public int playheadPixel(){return x(playheadTick);}
    public void setSnapDivisor(int divisor){if(divisor<1)throw new IllegalArgumentException("divisor");snapDivisor=divisor;repaint();}
    /** A divisor is a fraction of the CURRENT MEASURE, not a quarter-note beat. */
    public int getSnapDivisor(){return snapDivisor;}
    private long measure(){return document.measureTicks(layerIndex);}
    private long rawGridTick(long index){return Math.round(index * (double)measure()/snapDivisor);}
    private long gridTick(long index){return Math.max(0,rawGridTick(index));}
    private long snapped(long t){return gridTick(Math.round(t*snapDivisor/(double)measure()));}
    /** Clicking anywhere inside a grid cell creates its note at the LEFT edge,
     * rather than switching to the next cell at the halfway point. Use the
     * unrounded mouse coordinate to avoid a half-tick boundary error. */
    private long notePlacementIndex(int pixelX) {
        double tickPosition = Math.max(0, (pixelX - keyboardWidth) / scale());
        return (long)Math.floor(tickPosition * snapDivisor / measure() + 1e-9);
    }
    /** Translate by grid cells, not a repeatedly rounded cell duration. Keep
     * the original relative offsets of a multiselection or an off-grid note. */
    private long gridDragDelta(double ticks) {
        long cells=Math.round(ticks*snapDivisor/measure());
        return rawGridTick(dragGridIndex+cells)-rawGridTick(dragGridIndex);
    }
    private double scale(){return quarterWidth/(double)document.ppq;}
    private int x(long tick){return keyboardWidth+(int)Math.round(tick*scale());}
    private long tick(int px){return Math.max(0,Math.round((px-keyboardWidth)/scale()));}
    private int y(int pitch){return TOP+(127-pitch)*rowHeight;}
    private int pitch(int py){return Math.max(0,Math.min(127,127-(py-TOP)/rowHeight));}
    private List<Integer> viewScopeLayers(){
        if(document==null || document.layers.isEmpty())return List.of();
        if(!showAllParts)return List.of(Math.max(0,Math.min(layerIndex, document.layers.size()-1)));
        List<Integer> ids=new ArrayList<>();for(int i=0;i<document.layers.size();i++)ids.add(i);
        return ids;
    }
    private List<Integer> visibleLayers(){
        List<Integer> ids=viewScopeLayers();
        if(audibleFilterActive())ids.removeIf(i->!isPartAudible(document.layer(i).partNumber));
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
        // A common song timeline avoids clamping the horizontal viewport when
        // switching from a long part to a shorter (or empty) part.
        long max=0;
        if(document!=null)for(int i=0;i<document.layers.size();i++){
            for(EditableNote n:document.layer(i).notes)max=Math.max(max,n.end());
            max=Math.max(max,(long)minimumMeasureCount * document.measureTicks(i));
        }
        double w=document==null?1:scale();
        return new Dimension((int)Math.min(200000,Math.max(1200,keyboardWidth+(max+(document==null?0:document.ppq*8L))*w)),TOP+128*rowHeight);
    }
    public void scrollToMiddleC(){scrollRectToVisible(new Rectangle(0,y(72)-rowHeight*12,600,rowHeight*24));}
    @Override public String getToolTipText(MouseEvent e){
        Hit h=hit(e.getPoint());
        if(h==null)return getToolTipText();
        NoteDocument.Layer layer=document.layer(h.layer);
        boolean playable=document.kind!=NoteDocument.Kind.ABC ||
            LotroPatch.isPlayableSoundingPitch(layer.instrumentName,h.note.pitch(),layer.abcPitchShift);
        DrumLabels.Kit kit=DrumLabels.noteKit(document,layer,h.note);
        String label=kit==DrumLabels.Kit.NONE?"Pitch "+pitchName(h.note.pitch()):"Drum "+noteLabel(layer,h.note,h.note.pitch())+" ("+pitchName(h.note.pitch())+")";
        return layer.name+" | "+label+" (MIDI "+h.note.pitch()+") | Tick "+h.note.start()+"-"+h.note.end()+
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
                g.setColor(keyboardKit!=DrumLabels.Kit.NONE
                    ? (drumKeyName(p)==null?new Color(26,29,37):p%2==0?new Color(54,62,74):new Color(40,48,60))
                    : inScale(p)?(black?new Color(40,48,60):new Color(54,62,74)):(black?new Color(26,29,37):new Color(32,36,44)));g.fillRect(keyboardWidth,yy,getWidth()-keyboardWidth,rowHeight);
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
                    // Label the sounding pitch, including the pitch shown during a drag.
                    // Measure the full name, including accidentals and octave, rather
                    // than relying on a fixed MIDI-number width threshold.
                    NoteLabel label=fittingNoteLabel(g,noteLabel(noteLayer,n,p),width-10,rowHeight-5);
                    if(label!=null){
                        Rectangle ink=label.ink();
                        int baseline=yy+3+(rowHeight-5-ink.height)/2-ink.y;
                        g.setColor(NOTE_LABEL_COLOR);
                        g.drawGlyphVector(label.glyph(),xx+5-ink.x,baseline);
                    }
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
            // The keyboard child paints over the grid; the ruler is independent.
        }finally{g.dispose();}
    }
    private Hit hit(Point p){
        if(document==null||p.y<TOP||p.x<editableLeft())return null;
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
        if(document==null||!SwingUtilities.isLeftMouseButton(e)||(e.getY()>=TOP&&e.getX()<editableLeft()))return;
        cancelDragInteraction();requestFocusInWindow();anchor=e.getPoint();pointer=e.getPoint();dragging=false;selectingBox=false;
        seeking=e.getY()<TOP || (Math.abs(e.getX()-x(playheadTick))<=4 && e.isAltDown()
                && !(isNoteCreationClick(e) && hit(e.getPoint()) == null));
        if(seeking){setPlayheadTick(snapped(tick(e.getX())));onSeek.run();return;}
        Hit h=hit(e.getPoint());previewTick=0;previewPitch=0;resizing=false;
        if(h==null){if(!e.isShiftDown())selected.clear();marquee=new Rectangle(e.getX(),e.getY(),0,0);repaint();return;}
        Selection key=new Selection(h.layer,h.note.id());
        if(e.isShiftDown()) { if(!selected.add(key)) selected.remove(key); }
        else if(!selected.contains(key)){selected.clear();selected.add(key);}
        resizing=x(h.note.end())-e.getX()<8&&x(h.note.end())-e.getX()>=-3;
        dragGridIndex=Math.round((resizing?h.note.end():h.note.start())*snapDivisor/(double)measure());
        onAudition.accept(h.note,document.layer(h.layer));
        repaint();
    }
    private void dragged(MouseEvent e){
        if(document==null||anchor==null)return;
        JViewport viewport=dragViewport();
        trackEdgePointer(e,viewport);
        updateDrag(viewport==null?e.getPoint():gridDragPoint(viewport,edgePointer));
    }
    private void updateDrag(Point point){
        if(document==null || anchor==null)return;
        pointer=point;
        if(seeking){seekDragAt(point.x,null,false);return;}
        if(marquee!=null){
            selectingBox=true;
            // The original anchor is in song coordinates, not the current viewport.
            // Do not clip away previously swept notes as the keyboard scrolls over them.
            int x=Math.max(keyboardWidth,Math.min(anchor.x,point.x)),y=Math.min(anchor.y,point.y);
            marquee=new Rectangle(x,y,Math.max(0,Math.max(anchor.x,point.x)-x),Math.abs(point.y-anchor.y));repaint();return;
        }
        if(selected.isEmpty())return;
        int dx=point.x-anchor.x,dy=point.y-anchor.y;
        if(Math.abs(dx)<3&&Math.abs(dy)<3&&!dragging)return;
        dragging=true;
        previewTick=gridDragDelta(dx/scale());
        previewPitch=resizing?0:-Math.round(dy/(float)rowHeight);
        repaint();
    }
    private void released(){
        stopEdgeScroll();
        if(document==null || anchor==null){cancelDragInteraction();return;}
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
        cancelDragInteraction();
    }
    private void add(MouseEvent e){
        if(document==null || e.getX()<editableLeft() || e.getY()<TOP || hit(e.getPoint())!=null)return;
        if(audibleFilterActive() && !visibleLayers().contains(layerIndex)){
            onClipboardMessage.accept("Selected part is hidden; disable Show only audible or use single-part view to add notes");
            return;
        }
        long cell=notePlacementIndex(e.getX());
        long start=gridTick(cell),end=Math.max(start+1,gridTick(cell+1));
        int channel=notes().isEmpty()?0:notes().getFirst().channel();
        EditableNote note=EditableNote.create(pitch(e.getY()),90,channel,start,end);
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
        int newX=(int)Math.round(keyboardWidth+(Math.max(keyboardWidth,px)-keyboardWidth)*quarterWidth/(double)oldWidth);
        if(px<keyboardWidth)newX=px;
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
    private void notifyEdit(){refreshKeyboard();detectKey();onEdit.run();revalidate();repaint();}
}
