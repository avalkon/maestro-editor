package com.digero.maestro.noteedit;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Modeless editor. ProjectBridge is present only when launched from Maestro. */
public final class NoteEditorApp {
    private NoteEditorApp() {}
    @FunctionalInterface public interface ProjectBridge {
        /** Store the in-memory note layer in the .msx via Maestro's Save workflow. */
        void save(NoteDocument.Kind kind, byte[] bytes) throws Exception;
        /** Live change notification; standalone and older integrations can ignore it. */
        default void changed(NoteDocument.Kind kind, NoteDocument document) {}
    }
    public static void main(String[] args) {
        SwingUtilities.invokeLater(()->openWindow(args.length>0?new File(args[0]):null,
                args.length>1?new File(args[1]):null));
    }
    public static JFrame openWindow(){return openWindow(null,null,null);}
    public static JFrame openWindow(File sourceMidi,File exportedAbc){return openWindow(sourceMidi,exportedAbc,null);}
    public static JFrame openWindow(File sourceMidi,File exportedAbc,ProjectBridge bridge){
        JFrame frame=new JFrame("Maestro — Note Editing Mode");
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JTabbedPane tabs=new JTabbedPane();
        EditorTab midi=new EditorTab(NoteDocument.Kind.MIDI,frame,bridge);
        EditorTab abc=new EditorTab(NoteDocument.Kind.ABC,frame,bridge);
        tabs.addTab("Original MIDI",midi);
        tabs.addTab("LOTRO / ABC Parts",abc);
        frame.setContentPane(tabs);
        tabs.addChangeListener(e->{if(tabs.getSelectedComponent()==midi)abc.stopPlayback();else midi.stopPlayback();});
        frame.addWindowListener(new WindowAdapter(){
            @Override public void windowClosing(WindowEvent e){
                if(midi.hasUnsaved()||abc.hasUnsaved()){
                    int yes=JOptionPane.showConfirmDialog(frame,"There are unsaved editor changes. Discard them and close?",
                            "Unsaved editor notes",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE);
                    if(yes!=JOptionPane.YES_OPTION)return;
                }
                frame.dispose();
            }
            @Override public void windowClosed(WindowEvent e){midi.stopPlayback();abc.stopPlayback();}
        });
        frame.setSize(1160,770);frame.setLocationRelativeTo(null);frame.setVisible(true);
        if(sourceMidi!=null&&is(sourceMidi,"mid","midi","kar"))midi.load(sourceMidi);
        if(exportedAbc!=null&&is(exportedAbc,"abc")){
            abc.load(exportedAbc);
            if(sourceMidi!=null&&sourceMidi.getParentFile()!=null)
                abc.setPreferredSaveDirectory(sourceMidi.getParentFile());
        } else if(sourceMidi!=null&&is(sourceMidi,"abc"))abc.load(sourceMidi);
        return frame;
    }
    private static boolean is(File file,String...suffixes){
        if(!file.isFile())return false;
        String ext=file.getName().toLowerCase(Locale.ROOT);
        for(String s:suffixes)if(ext.endsWith("."+s))return true;
        return false;
    }
    /** In embedded mode, Maestro owns transport position, playback, volume and panning.
     *  EditorPlayback is retained only for the standalone window and note audition. */
    public interface TransportBridge {
        void playOrPause();
        void stop();
        void seek(long editorTick,int editorPpq);
        long position(int editorPpq);
        boolean isRunning();
    }
    public static final class EditorTab extends JPanel {
        private final NoteDocument.Kind kind;
        private final JFrame parent;
        private final ProjectBridge bridge;
        private final PianoRollPanel roll=new PianoRollPanel();
        private final JComboBox<String> layers=new JComboBox<>();
        private final JLabel layerLabel = new JLabel("Track / part: ");
        private JButton openButton;
        private JToolBar fileToolbar;
        private TransportBridge sharedTransport;
        private Runnable onScopeChanged=()->{};
        private java.util.function.IntSupplier masterVolume=()->127, masterPan=()->64;
        private java.util.function.BooleanSupplier followSupplier=()->false;
        private final java.util.Map<Integer,boolean[]> partPlaybackState = new java.util.HashMap<>();
        private boolean externalPartSelection;
        private boolean embeddedMode;
        private boolean initialFitPending;

        private final JComboBox<String> highlightKey = new JComboBox<>(HighlightKey.options().toArray(String[]::new));
        private boolean syncingHighlightKey;
        private java.util.function.Consumer<String> highlightKeyChanged = key -> {};
        public void setHighlightKey(String key) {
            syncingHighlightKey=true;
            try { highlightKey.setSelectedItem(HighlightKey.normalize(key)); roll.setHighlightKey(key); refreshed(); }
            finally { syncingHighlightKey=false; }
        }
        public void setOnHighlightKeyChanged(java.util.function.Consumer<String> listener) {highlightKeyChanged=listener;}
        private final JLabel status=new JLabel("Open a file to begin");
        private final JScrollPane scroller=new JScrollPane(roll);
        private final EditorPlayback player=new EditorPlayback();
        private final javax.swing.Timer playbackTimer=new javax.swing.Timer(100,e->playbackTick());
        private final JButton playButton=new JButton("Play");
        private final JButton stopButton=new JButton("Stop");
        private final JComboBox<String> scope=new JComboBox<>(new String[]{"Current part", "All parts"});
        private final JCheckBox allParts=new JCheckBox("Display all parts");
        private final JCheckBox onlyAudible=new JCheckBox("Show only audible");
        private NoteDocument doc;
        private File lastDirectory;
        public EditorTab(NoteDocument.Kind kind,JFrame parent,ProjectBridge bridge){
            super(new BorderLayout());this.kind=kind;this.parent=parent;this.bridge=bridge;
            JPanel controls=new JPanel(new BorderLayout());
            JToolBar files=new JToolBar();files.setFloatable(false);
            fileToolbar=files;
            openButton=button("Open...",this::chooseOpen);
            JButton save=button("Export As...",this::saveAs);
            files.add(openButton);files.add(save);
            if(bridge!=null) {
                JButton saveProject=button("Store edits in project",this::saveToProject);
                saveProject.setToolTipText("Embed the edited MIDI/ABC snapshot in the active Maestro project");
                files.add(saveProject);
            }
            files.addSeparator();files.add(layerLabel);files.add(layers);
            controls.add(files,BorderLayout.SOUTH);
            JToolBar editing=new JToolBar();editing.setFloatable(false);
            // Compact, single-line toolbar. Maestro's File menu owns Export/Store
            // in embedded mode, and the main transport owns Play and Stop.

            editing.add(new JLabel("Snap: "));
            JComboBox<String> snap=new JComboBox<>(new String[]{"1/1", "1/2", "1/3", "1/4", "1/6", "1/8", "1/12", "1/16", "1/24", "1/32", "1/48", "1/64"});
            snap.setSelectedItem("1/16");
            snap.setToolTipText("Fraction of the current measure (1/24 and 1/48 are triplet subdivisions in 4/4)");
            snap.addActionListener(e->{
                String option=(String)snap.getSelectedItem();
                if(option != null)roll.setSnapDivisor(Integer.parseInt(option.substring(2)));
            });
            snap.setMaximumSize(new Dimension(83,25));
            roll.setSnapDivisor(16);editing.add(snap);
            highlightKey.setToolTipText("Auto detects the scale; a manual key only changes piano roll shading.");
            highlightKey.setMaximumSize(new Dimension(110,25));
            highlightKey.addActionListener(e->{
                String key=(String)highlightKey.getSelectedItem(); roll.setHighlightKey(key); refreshed();
                if(!syncingHighlightKey)highlightKeyChanged.accept(key);
            });
            editing.addSeparator();
            editing.add(new JLabel("Velocity: "));
            JSpinner velocity = new JSpinner(new SpinnerNumberModel(90,1,127,1));
            velocity.addChangeListener(e->{if(doc!=null){roll.setSelectedVelocity((Integer)velocity.getValue());}});
            velocity.setMaximumSize(new Dimension(67,25));
            editing.add(velocity);
            editing.addSeparator();
            editing.add(allParts);
            editing.addSeparator();
            editing.add(new JLabel("Play: "));
            scope.setMaximumSize(new Dimension(123,25));
            editing.add(scope);
            scope.addActionListener(e->onScopeChanged.run());
            editing.addSeparator();
            editing.add(button("Undo",()->{if(doc!=null && doc.canUndo()){doc.undo();refreshed();notifyProjectChange();}}));
            editing.add(button("Redo",()->{if(doc!=null && doc.canRedo()){doc.redo();refreshed();notifyProjectChange();}}));
            editing.add(button("Delete",roll::removeSelected));
            playButton.addActionListener(e->playOrPause());
            stopButton.addActionListener(e->stopPlayback());stopButton.setEnabled(false);
            if(bridge==null){
                editing.addSeparator();editing.add(playButton);editing.add(stopButton);
            }
            editing.add(Box.createHorizontalGlue());
            editing.addSeparator();
            onlyAudible.setEnabled(kind==NoteDocument.Kind.ABC && bridge!=null);
            onlyAudible.setToolTipText(kind==NoteDocument.Kind.ABC && bridge!=null
                ? "In Display all parts, hide parts excluded by Song Parts solo/mute. Single-part view is unaffected."
                : "Available in the project ABC piano roll, where Song Parts solo/mute is defined.");
            onlyAudible.addActionListener(e->{roll.setShowOnlyAudible(onlyAudible.isSelected());refreshed();});
            editing.add(onlyAudible);
            editing.addSeparator();
            editing.add(new JLabel("Highlight key: "));
            editing.add(highlightKey);
            controls.add(editing,BorderLayout.NORTH);
            add(controls,BorderLayout.NORTH);
            allParts.addActionListener(e->{roll.setShowAllParts(allParts.isSelected());refreshed();});
            layers.addActionListener(e->{if(doc!=null&&layers.getSelectedIndex()>=0){
                stopLocalPlayback();roll.setDocument(doc,layers.getSelectedIndex());
                refreshed();
            }});
            layers.setRenderer(new DefaultListCellRenderer(){
                @Override public Component getListCellRendererComponent(JList<?> list,Object value,int index,boolean selected,boolean focus){
                    super.getListCellRendererComponent(list,value,index,selected,focus);
                    if(doc!=null && index>=0 && index<doc.layers.size()){
                        setText("●  "+value);
                        if(!selected)setForeground(roll.getPartColor(index));
                    }
                    return this;
                }
            });
            roll.setOnClipboardMessage(status::setText);
            roll.setOnEdit(()->{
                // Keep Maestro's shared transport/position during an embedded edit.
                stopLocalPlayback();refreshed();
                notifyProjectChange();
            });
            roll.setOnAudition((note, part)->{
                boolean[] flags=partPlaybackState.get(part.partNumber);
                boolean soloed=partPlaybackState.values().stream().anyMatch(a->a[1]);
                if(flags!=null && (flags[0] || (soloed && !flags[1])))return;
                long noteMillis=Math.round(2.0*(note.end()-note.start())*60_000.0/
                    (Math.max(1,doc==null?960:doc.ppq)*Math.max(1,part.abcTempo)));
                player.audition(note,part.lotroProgram,kind==NoteDocument.Kind.ABC,
                    masterVolume.getAsInt(),masterPan.getAsInt(),
                    (int)Math.min(1100,Math.max(350,noteMillis)),part.abcPitchShift);
            });
            roll.setOnSeek(()->{
                if(sharedTransport!=null && doc!=null)sharedTransport.seek(roll.getPlayheadTick(),doc.ppq);
                else if(player.isOpen())player.seek(roll.getPlayheadTick());
            });
            roll.setOnTogglePlayback(this::playOrPause);
            scroller.setColumnHeaderView(roll.createStickyRuler());
            scroller.setRowHeaderView(roll.createStickyKeyboard());
            roll.setOnZoom(()->{
                initialFitPending=false; // Never overwrite a user-initiated zoom.
                if(scroller.getColumnHeader()!=null){
                    scroller.getColumnHeader().getView().revalidate();scroller.getColumnHeader().revalidate();
                }
                if(scroller.getRowHeader()!=null){
                    scroller.getRowHeader().getView().revalidate();scroller.getRowHeader().revalidate();
                    scroller.getRowHeader().repaint();
                }
            });
            add(scroller,BorderLayout.CENTER);
            // A document may be loaded into a hidden tab/card before layout.
            // Fit once, when the real viewport is available, not on each selection.
            addHierarchyListener(e->{
                if((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0 && isShowing())fitView();
            });
            scroller.getViewport().addComponentListener(new java.awt.event.ComponentAdapter(){
                @Override public void componentResized(java.awt.event.ComponentEvent e){fitView();}
            });
            JPanel bottom=new JPanel(new BorderLayout());
            bottom.add(status,BorderLayout.CENTER);
            bottom.add(new JLabel("Drag header = scrub  |  Ctrl+wheel = XY zoom  |  Box-select on empty grid  |  Shift = add  |  Space = play  "),BorderLayout.EAST);
            add(bottom,BorderLayout.SOUTH);
            if(kind==NoteDocument.Kind.ABC)status.setText("ABC supports organic L: denominators; edited notes are re-quantized to 960 PPQ.");
        }
        private void notifyProjectChange(){
            if(bridge!=null && doc!=null)bridge.changed(kind,doc);
        }
        private JButton button(String text,Runnable action){JButton b=new JButton(text);b.addActionListener(e->action.run());return b;}
        public void setMinimumMeasures(int count){roll.setMinimumMeasureCount(count);}
        /** Configure the optional note-creation mouse gesture in both embedded editors. */
        public void setNoteCreationMouseGesture(boolean enabled, int modifiers) {
            roll.setNoteCreationMouseGesture(enabled, modifiers);
        }
        /** Initialize the pitch range once per loaded document; cached views retain zoom. */
        public void fitView(){
            if(!initialFitPending)return;
            SwingUtilities.invokeLater(()->{
                if(!initialFitPending || !isShowing() || scroller.getViewport().getHeight()<=2)return;
                initialFitPending=false;
                roll.fitFullPitchRange();
            });
        }
        /** Used only by the embedded view; standalone retains its own player. */
        public void setSharedTransport(TransportBridge bridge){
            // The parent Maestro window handles Space globally. Do not also
            // dispatch it from the focused piano roll (pressed vs typed events
            // previously caused immediate play/pause oscillation).
            roll.setSpaceShortcutEnabled(bridge==null);
            this.sharedTransport=bridge;
            if(bridge!=null){stopLocalPlayback();playbackTimer.start();playbackTick();}
        }
        public void setMasterAudioControls(java.util.function.IntSupplier volume,java.util.function.IntSupplier pan){
            this.masterVolume=java.util.Objects.requireNonNull(volume);
            this.masterPan=java.util.Objects.requireNonNull(pan);
        }
        public boolean hasSharedTransport(){return sharedTransport!=null;}
        public boolean playsAllParts(){return scope.getSelectedIndex()==1;}
        public void setOnScopeChanged(Runnable cb){onScopeChanged=java.util.Objects.requireNonNull(cb);}
        /** Drop the in-window polling without changing Maestro's playing state. */
        public void suspendEmbeddedView(){
            if(sharedTransport!=null)playbackTimer.stop();else stopLocalPlayback();
        }
        public void resumeEmbeddedView(){
            if(sharedTransport!=null){playbackTimer.start();playbackTick();}
        }
        public void undoFromShortcut() {
            if (doc != null && doc.canUndo()) { doc.undo(); refreshed(); notifyProjectChange(); }
        }
        public void redoFromShortcut() {
            if (doc != null && doc.canRedo()) { doc.redo(); refreshed(); notifyProjectChange(); }
        }
        public void deleteFromShortcut() { roll.removeSelected(); }
        public void clearSelectionFromShortcut() { roll.clearSelection(); }
        public void selectAllFromShortcut() { roll.selectAllVisibleNotes(); }
        public void copyFromShortcut() { roll.copySelection(); }
        public void cutFromShortcut() { roll.cutSelection(); }
        public void pasteFromShortcut() { roll.pasteClipboard(); }
        public void exportAsFromMenu(){saveAs();}
        public void stageToProjectFromMenu(){stageChanges();}
        public void useEmbeddedMode(){embeddedMode=true;fileToolbar.setVisible(false);}

        public boolean hasUnsaved(){return doc!=null&&doc.isDirty();}
        public void useSongPartsSelection(){
            if(kind==NoteDocument.Kind.ABC){
                externalPartSelection=true;layerLabel.setVisible(false);layers.setVisible(false);
                fileToolbar.setVisible(false); // File menu and Song Parts supply these controls
            }
        }
        public void setFollowSupplier(java.util.function.BooleanSupplier supplier){
            followSupplier=java.util.Objects.requireNonNull(supplier);
        }
        /** JComboBox matches String items by equals(), not index. Two parts may
         * have IDENTICAL names/instruments, so each ABC item must contain its
         * unique X: number or selecting the second part silently picks the first. */
        private String layerDisplayName(NoteDocument.Layer layer) {
            return kind==NoteDocument.Kind.ABC
                ? "X:"+layer.partNumber+"  "+layer.name : layer.name;
        }

        /** Select a resulting ABC part by its X: number, NOT a source MIDI track. */
        public void selectPartNumber(int partNumber){
            if(doc==null)return;
            for(int i=0;i<doc.layers.size();i++)if(doc.layer(i).partNumber==partNumber){
                if(layers.getSelectedIndex()!=i)layers.setSelectedIndex(i);
                return;
            }
        }
        /** A new Song Part can be selected before the async ABC baseline rebuild.
         * Insert its empty X:-identified layer immediately; never keep editing
         * the previously selected instrument just because this part is missing. */
        public void ensureAbcPart(CompositionAbc.PartSpec spec,
                java.util.Map<Integer,NoteEditorDelta.Part> persistedDeltas) {
            if (kind!=NoteDocument.Kind.ABC || doc==null) return;
            for (NoteDocument.Layer layer:doc.layers)
                if (layer.partNumber==spec.number()) {
                    selectPartNumber(spec.number());
                    return;
                }
            try {
                NoteDocument empty=AbcDocumentIO.readText(
                    CompositionAbc.forEditor("%abc-2.1\n", java.util.List.of(spec)));
                if (empty.layers.size()!=1 || empty.layer(0).partNumber!=spec.number())
                    throw new java.io.IOException("Cannot create part X:"+spec.number());
                NoteEditorDelta.apply(empty,persistedDeltas);
                doc.layers.add(empty.layer(0));
                layers.addItem(layerDisplayName(empty.layer(0)));
                selectPartNumber(spec.number());
            } catch (java.io.IOException failed) {
                throw new IllegalStateException("Cannot select new part X:"+spec.number(),failed);
            }
        }

        public java.util.Map<Integer,NoteEditorDelta.Part> getNoteDeltasPreserving(
                java.util.Map<Integer,NoteEditorDelta.Part> persisted) {
            return doc==null || kind!=NoteDocument.Kind.ABC ? persisted :
                NoteEditorDelta.capturePreservingMissing(doc,persisted);
        }

        public void setPartMuteSolo(int partNumber,boolean muted,boolean soloed){
            partPlaybackState.put(partNumber,new boolean[]{muted,soloed});
            roll.setPartMuteSolo(partNumber,muted,soloed);
            applyPartPlaybackState();
            roll.repaint();
        }
        /** Synchronize the full Song Parts snapshot in one operation. */
        public void setPartMuteSoloStates(java.util.Map<Integer,boolean[]> states){
            partPlaybackState.clear();
            states.forEach((number,flags)->partPlaybackState.put(number,flags.clone()));
            roll.setPartMuteSoloStates(partPlaybackState);
            applyPartPlaybackState();
            refreshed();
        }
        private void applyPartPlaybackState(){
            if(doc==null||kind!=NoteDocument.Kind.ABC)return;
            int part=scope.getSelectedIndex()==1?-1:layers.getSelectedIndex();
            player.setTrackMuteSolo(partPlaybackState,doc,part);
        }
        /** Load a freshly CONVERTED arrangement while rebasing the existing manual note deltas. */
        public void reloadGeneratedAbc(String generated,java.util.Map<Integer,NoteEditorDelta.Part> deltas)
                throws java.io.IOException {
            if(kind!=NoteDocument.Kind.ABC)return;
            int previous=-1;
            if(doc!=null && layers.getSelectedIndex()>=0)previous=doc.layer(layers.getSelectedIndex()).partNumber;
            NoteDocument updated=AbcDocumentIO.readText(generated);
            // Rebuilding an identical generated baseline must NOT discard undo history.
            // Maestro regenerates a preview after editor actions as well as after source changes.
            // A regenerated baseline can be identical while the MSX's note
            // overrides have changed (for example after reopening, rerouting,
            // or editing a second part). Compare final deltas as well: skipping
            // solely because the baseline matches leaves a stale/blank editor.
            if(sameBaseline(doc,updated) && NoteEditorDelta.capturePreservingMissing(doc,deltas).equals(deltas))return;
            NoteEditorDelta.Applied applied=NoteEditorDelta.apply(updated,deltas);
            // A refresh may update headers, instruments, or add empty X: layers.
            // When every existing source part has the same baseline and final
            // notes, this is NOT a new editing session: keep its undo/redo.
            // The old history is keyed by X:, so a different layer ordering is OK.
            if (sameEditingContent(doc, updated)) updated.preserveHistoryFrom(doc);
            updated.markSaved();
            stopLocalPlayback();
            this.doc=updated;
            layers.removeAllItems();
            for(NoteDocument.Layer layer:doc.layers)layers.addItem(layerDisplayName(layer));
            if(previous>0)selectPartNumber(previous);
            if(layers.getSelectedIndex()<0 && layers.getItemCount()>0)layers.setSelectedIndex(0);
            if(layers.getSelectedIndex()>=0)roll.setDocument(doc,layers.getSelectedIndex());
            if(applied.unmatchedRemovals()>0)
                status.setText("Note rebasing: "+applied.unmatchedRemovals()+" prior note(s) changed in the generated output; review overrides");
            else refreshed();
        }
        private static boolean sameBaseline(NoteDocument current,NoteDocument generated){
            if(current==null || current.layers.size()!=generated.layers.size())return false;
            for(int i=0;i<current.layers.size();i++) {
                NoteDocument.Layer a=current.layer(i), b=generated.layer(i);
                if(a.partNumber!=b.partNumber || a.measureTicks!=b.measureTicks ||
                   a.lotroProgram!=b.lotroProgram || a.abcTempo!=b.abcTempo ||
                   !a.name.equals(b.name) || a.baselineNotes.size()!=b.baselineNotes.size())return false;
                for(int j=0;j<a.baselineNotes.size();j++)
                    if(!NoteEditorDelta.Value.of(a.baselineNotes.get(j)).equals(
                        NoteEditorDelta.Value.of(b.baselineNotes.get(j))))return false;
            }
            return true;
        }
        private static boolean sameEditingContent(NoteDocument previous, NoteDocument updated) {
            if (previous == null || previous.kind != updated.kind) return false;
            java.util.Map<Integer,NoteDocument.Layer> current = new java.util.HashMap<>();
            java.util.Map<Integer,NoteDocument.Layer> next = new java.util.HashMap<>();
            for (NoteDocument.Layer layer: previous.layers)
                if (current.put(layer.partNumber,layer) != null) return false;
            for (NoteDocument.Layer layer: updated.layers)
                if (next.put(layer.partNumber,layer) != null) return false;
            for (var entry: current.entrySet()) {
                NoteDocument.Layer old = entry.getValue(), now = next.get(entry.getKey());
                if (now == null || old.measureTicks != now.measureTicks ||
                    old.abcPitchShift != now.abcPitchShift ||
                    !sameNotes(old.baselineNotes, now.baselineNotes) ||
                    !sameNotes(old.notes, now.notes)) return false;
            }
            return true;
        }
        private static boolean sameNotes(java.util.List<EditableNote> a,
                                         java.util.List<EditableNote> b) {
            if (a.size() != b.size()) return false;
            java.util.Map<NoteEditorDelta.Value,Integer> counts = new java.util.HashMap<>();
            for (EditableNote n:a) counts.merge(NoteEditorDelta.Value.of(n),1,Integer::sum);
            for (EditableNote n:b) counts.merge(NoteEditorDelta.Value.of(n),-1,Integer::sum);
            return counts.values().stream().allMatch(count -> count == 0);
        }
        public java.util.Map<Integer,NoteEditorDelta.Part> getNoteDeltas(){
            return doc==null||kind!=NoteDocument.Kind.ABC?java.util.Map.of():NoteEditorDelta.capture(doc);
        }
        public int currentPartNumber(){
            return doc==null||layers.getSelectedIndex()<0?-1:doc.layer(layers.getSelectedIndex()).partNumber;
        }

        private void setPreferredSaveDirectory(File dir){if(dir!=null&&dir.isDirectory())lastDirectory=dir;}
        private void chooseOpen(){
            if(hasUnsaved()&&JOptionPane.showConfirmDialog(parent,"Unsaved edits will be discarded. Continue?",
                    "Unsaved edits",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;
            JFileChooser chooser=new JFileChooser(lastDirectory);
            if(kind==NoteDocument.Kind.MIDI)chooser.setFileFilter(new FileNameExtensionFilter("MIDI files","mid","midi","kar"));
            else chooser.setFileFilter(new FileNameExtensionFilter("ABC scores","abc"));
            if(chooser.showOpenDialog(parent)==JFileChooser.APPROVE_OPTION)load(chooser.getSelectedFile());
        }
        public void load(File file){
            stopLocalPlayback();if(file==null||!file.isFile())return;
            try{
                NoteDocument incoming=kind==NoteDocument.Kind.MIDI?MidiDocumentIO.read(file):AbcDocumentIO.read(file);
                if(incoming.layers.isEmpty())throw new IllegalArgumentException("File has no tracks/parts");
                doc=incoming;layers.removeAllItems();
                for(NoteDocument.Layer layer:doc.layers)layers.addItem(layerDisplayName(layer));
                layers.setSelectedIndex(0);roll.setDocument(doc,0);roll.setPlayheadTick(0);
                initialFitPending=true;fitView();
                lastDirectory=file.getParentFile();refreshed();
            }catch(Exception ex){JOptionPane.showMessageDialog(parent,ex.getMessage(),"Cannot open file",JOptionPane.ERROR_MESSAGE);}
        }
        private void saveAs(){
            if(doc==null)return;
            JFileChooser chooser=new JFileChooser(lastDirectory);
            String name=doc.sourceFile.getName();int dot=name.lastIndexOf('.');if(dot>=0)name=name.substring(0,dot);
            String ext=kind==NoteDocument.Kind.MIDI?"mid":"abc";
            chooser.setSelectedFile(new File(lastDirectory,name+".edited."+ext));
            if(chooser.showSaveDialog(parent)!=JFileChooser.APPROVE_OPTION)return;
            File out=chooser.getSelectedFile();
            if(!out.getName().toLowerCase(Locale.ROOT).endsWith("."+ext))out=new File(out+"."+ext);
            if(out.equals(doc.sourceFile)){JOptionPane.showMessageDialog(parent,"Choose a different file.");return;}
            if(out.exists()&&JOptionPane.showConfirmDialog(parent,"Overwrite "+out.getName()+"?","Overwrite",
                    JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
            try{
                if(kind==NoteDocument.Kind.MIDI)MidiDocumentIO.write(doc,out);else AbcDocumentIO.write(doc,out);
                lastDirectory=out.getParentFile();doc.markSaved();refreshed();
                JOptionPane.showMessageDialog(parent,"Exported " +out.getAbsolutePath());
            }catch(Exception ex){JOptionPane.showMessageDialog(parent,ex.getMessage(),"Export failed",JOptionPane.ERROR_MESSAGE);}
        }
        public void saveToProject(){
            if(doc==null||bridge==null)return;
            try{
                if (embeddedMode && kind == NoteDocument.Kind.ABC) {
                    // Project persistence is lossless: keep EVERY note (including
                    // out-of-range notes) in the MSX deltas. Only ABC EXPORT
                    // checks LOTRO's instrument range. No ABC serialization here.
                    bridge.changed(kind,doc);
                } else {
                    byte[] content=kind==NoteDocument.Kind.MIDI?MidiDocumentIO.toBytes(doc):
                            AbcDocumentIO.toText(doc).getBytes(StandardCharsets.UTF_8);
                    bridge.save(kind,content);
                    bridge.changed(kind,doc);
                }
                doc.markSaved();refreshed();
                JOptionPane.showMessageDialog(parent,"Staged the edited "+(kind==NoteDocument.Kind.MIDI?"MIDI":"ABC")+
                        " snapshot in the Maestro project. Use Maestro File > Save to write .msx.\n"+
                        "Edited ABC part deltas now feed Maestro's ABC export and live preview.\n"+
                        "Original MIDI edits are still stored as a snapshot, not yet substituted into source conversion.");
            }catch(Exception ex){JOptionPane.showMessageDialog(parent,ex.getMessage(),"MSX save failed",JOptionPane.ERROR_MESSAGE);}
        }
        public void playOrPause(){
            if(doc==null)return;
            if(sharedTransport!=null){sharedTransport.playOrPause();playbackTimer.start();playbackTick();return;}
            if(player.isOpen()){
                player.pauseOrResume();playButton.setText(player.isRunning()?"Pause":"Resume");return;
            }
            try{
                int selected=scope.getSelectedIndex()==1?-1:layers.getSelectedIndex();
                if(selected < -1||selected>=doc.layers.size())return;
                player.play(EditorPlayback.sequenceFor(doc,selected),roll.getPlayheadTick(),kind==NoteDocument.Kind.ABC);
                applyPartPlaybackState();
                playButton.setText("Pause");stopButton.setEnabled(true);playbackTimer.start();playbackTick();
            }catch(Exception ex){stopPlayback();JOptionPane.showMessageDialog(parent,
                    "Could not start playback:\n"+ex.getMessage(),"Playback unavailable",JOptionPane.ERROR_MESSAGE);}
        }
        private void playbackTick(){
            if(sharedTransport!=null){
                if(doc==null)return;
                roll.setPlayheadTick(sharedTransport.position(doc.ppq));
            } else {
                if(!player.isOpen()){playbackTimer.stop();return;}
                roll.setPlayheadTick(player.position());
            }
            boolean running=sharedTransport!=null?sharedTransport.isRunning():player.isRunning();
            if(running && followSupplier.getAsBoolean()){
                int x=roll.playheadPixel(), left=scroller.getHorizontalScrollBar().getValue();
                int width=scroller.getViewport().getWidth();
                if(width>0 && (x>left+(int)(width*0.78) || x<left))
                    scroller.getHorizontalScrollBar().setValue(Math.max(0,x-width/3));
            }
            if(sharedTransport==null){
                if(!player.isRunning()&&player.position()>=player.tickLength()){stopPlayback();return;}
                playButton.setText(player.isRunning()?"Pause":"Resume");
            }
        }
        private void stopLocalPlayback(){
            if(sharedTransport==null)playbackTimer.stop();
            player.stop();playButton.setText("Play");stopButton.setEnabled(false);
        }
        public void stopPlayback(){
            if(sharedTransport!=null){sharedTransport.stop();playbackTick();}
            else {stopLocalPlayback();roll.setPlayheadTick(0);}
        }
        public void stageChanges(){
            if(doc==null || !doc.isDirty() || bridge==null)return;
            try{
                if (embeddedMode && kind == NoteDocument.Kind.ABC) {
                    // Saving MSX must never fail because of an unplayable pitch.
                    // The lossless delta layer is the authority for embedded ABC.
                    bridge.changed(kind,doc);
                } else {
                    byte[] data=kind==NoteDocument.Kind.MIDI?MidiDocumentIO.toBytes(doc):
                        AbcDocumentIO.toText(doc).getBytes(StandardCharsets.UTF_8);
                    bridge.save(kind,data);
                    bridge.changed(kind,doc);
                }
                doc.markSaved();
            }catch(Exception ex){
                java.util.logging.Logger.getLogger(NoteEditorApp.class.getName())
                    .log(java.util.logging.Level.SEVERE, "Unable to stage piano-roll notes", ex);
                // Show the real cause in the status/error report rather than only
                // the outer wrapper, so rare Linux staging failures are actionable.
                throw new IllegalStateException("Unable to stage piano-roll notes: " +
                    ex.getClass().getSimpleName() + ": " + ex.getMessage(), ex);
            }
        }
        private void refreshed(){
            if(doc==null)return;
            int index=Math.max(0,layers.getSelectedIndex());
            status.setText((HighlightKey.AUTO.equals(roll.getHighlightKey())?"Detected key: "+roll.getDetectedKey():"Highlight key: "+roll.getEffectiveHighlightKey()+" (manual; detected "+roll.getDetectedKey()+")")+" • "+(doc.isDirty()?"Modified • ":"")+doc.sourceFile.getName()+" • "+
                    doc.layer(index).notes.size()+" notes • "+doc.layers.size()+" layers • "+roll.selectionSize()+" selected"+
                    (bridge!=null?" • Save edits to MSX":" • Export As writes a separate file"));
            roll.revalidate();roll.repaint();
        }
    }
}
