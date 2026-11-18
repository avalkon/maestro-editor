package com.digero.maestro.view;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.IntPredicate;
import javax.sound.midi.Sequence;
import javax.swing.*;

import com.digero.common.midi.LotroSequencerWrapper;
import com.digero.common.midi.Note;
import com.digero.common.midi.SequencerWrapper;
import com.digero.common.util.IDiscardable;
import com.digero.common.view.ColorTable;
import com.digero.maestro.abc.AbcPart;
import com.digero.maestro.midi.NoteEvent;
import com.digero.maestro.noteedit.AbcDocumentIO;
import com.digero.maestro.noteedit.NoteEditorDelta;

/**
 * An authored source track displayed beside ordinary imported source tracks.
 * Its checkbox routes the source to the currently selected Song Part. The row
 * is shown for EVERY part (including parts where it is unchecked), rather than
 * for just those destinations with an active preview MIDI track.
 *
 * The graph is a view of the project-owned note deltas. Unchecked rows do not
 * create silent playback tracks, consume MIDI channels, or change source-track
 * indices; the existing preview/export routing layer remains authoritative.
 */
public final class AuthoredTrackPanel extends JPanel implements ArrangementViewItem, IDiscardable {
    private final NoteGraph graph;
    private final JCheckBox routeBox;
    private final JPanel assignmentStripe;
    private final int sourcePartNumber;
    private final int selectedPartNumber;
    private final AbcPart destinationPart;
    private boolean assignedAnywhere;
    private final Set<Integer> localDestinations;
    private boolean abcPreviewMode;

    public AuthoredTrackPanel(int sourcePartNumber, AbcPart sourcePart, AbcPart selectedPart,
                              List<NoteEditorDelta.Value> notes, Set<Integer> destinations,
                              SequencerWrapper sequencer, boolean showVelocities,
                              BiConsumer<Integer,Boolean> routeAction) {
        super(new BorderLayout(4, 0));
        this.sourcePartNumber = sourcePartNumber;
        selectedPartNumber = selectedPart.getPartNumber();
        destinationPart = selectedPart;
        localDestinations = new java.util.LinkedHashSet<>(destinations);
        boolean assignedHere = localDestinations.contains(selectedPartNumber);
        assignedAnywhere = !localDestinations.isEmpty();

        AbcPart pitchPart = sourcePart != null ? sourcePart : selectedPart;
        List<NoteEvent> events = graphEvents(notes, pitchPart, sequencer);
        graph = new NoteGraph(sequencer, selectedPart.getSequenceInfo(),
                Note.MIN_PLAYABLE.id - 12, Note.MAX_PLAYABLE.id + 12) {
            @Override protected List<NoteEvent> getEvents() { return events; }
            // NoteGraph's default requires an imported TrackInfo. Authored
            // lanes have none; follow their routed MIDI preview track instead.
            @Override protected boolean isShowingNotesOn() {
                return isAuthoredPlaybackActive(sequencer);
            }
        };
        graph.setPreferredSize(new Dimension(48, 48));
        graph.setOctaveLinesVisible(!pitchPart.getInstrument().isPercussion);
        graph.setShowingNoteVelocity(showVelocities);

        assignmentStripe = new JPanel();
        assignmentStripe.setPreferredSize(new Dimension(7, 48));
        add(assignmentStripe, BorderLayout.WEST);

        String instrument = pitchPart.getInstrument().friendlyName;
        routeBox = new JCheckBox("<html><b>Authored X:" + sourcePartNumber +
                "</b><br>" + instrument + "</html>");
        routeBox.setSelected(assignedHere);
        routeBox.setOpaque(false);
        routeBox.setFocusable(false);
        routeBox.setToolTipText("Route notes authored in X:" + sourcePartNumber +
                " (" + instrument + ") to the selected Song Part X:" + selectedPartNumber +
                ". Track is " + (assignedAnywhere ? "assigned to one or more parts" : "unassigned") + ".");
        routeBox.addActionListener(e -> {
            if (routeBox.isSelected()) localDestinations.add(selectedPartNumber);
            else localDestinations.remove(selectedPartNumber);
            assignedAnywhere = !localDestinations.isEmpty();
            updateAppearance(routeBox.isSelected(), assignedAnywhere);
            routeAction.accept(selectedPartNumber, routeBox.isSelected());
        });
        add(routeBox, BorderLayout.CENTER);
        setPreferredSize(new Dimension(245, 48));
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                ColorTable.PANEL_BORDER_HORIZ.get()));
        updateAppearance(assignedHere, assignedAnywhere);
    }

    /** Translate editor PPQ into the active preview sequencer's PPQ. This
     * also works when no destination is currently routed, because display
     * events are independent of the actual playback MIDI tracks. */
    static List<NoteEvent> graphEvents(List<NoteEditorDelta.Value> notes, AbcPart origin,
                                       SequencerWrapper sequencer) {
        Sequence sequence = sequencer.getSequence();
        long offset = sequencer instanceof LotroSequencerWrapper lotro ? lotro.getStartTick() : 0L;
        double microsPerTick = 60_000_000.0 / (origin.getAbcSong().getTempoBPM() * (double) AbcDocumentIO.PPQ);
        long startMicros = sequencer.tickToMicros(offset);
        int octaveShift = origin.getInstrument().octaveDelta * 12;
        List<NoteEvent> events = new ArrayList<>();
        for (NoteEditorDelta.Value value : notes) {
            Note pitch = Note.fromId(value.pitch() - octaveShift);
            if (pitch == null) continue;
            long start = sequence == null ? value.start() : Math.max(0,
                sequencer.microsToTick(startMicros + Math.round(value.start() * microsPerTick)));
            long end = Math.max(start + 1, sequence == null ? value.end() :
                sequencer.microsToTick(startMicros + Math.round(value.end() * microsPerTick)));
            events.add(new NoteEvent(pitch, value.velocity(), start, end, sequencer));
        }
        events.sort(Comparator.comparingLong(NoteEvent::getStartTick));
        return events;
    }

    private boolean isAudibleInAnyDestination() {
        // Part routing is independent of mute/solo. A source routed only to
        // muted (or solo-excluded) parts must look disabled, even if its route
        // checkbox is selected. Another active destination keeps it enabled.
        for (AbcPart part : destinationPart.getAbcSong().getParts())
            if (localDestinations.contains(part.getPartNumber()) && part.isActive())
                return true;
        return false;
    }

    private void updateAppearance(boolean assignedHere, boolean anyAssigned) {
        boolean audible = anyAssigned && isAudibleInAnyDestination();
        Color bg = assignedHere && audible ? ColorTable.GRAPH_BACKGROUND_ENABLED.get()
                : ColorTable.GRAPH_BACKGROUND_OFF.get();
        setOpaque(true);
        setBackground(bg);
        graph.setBackground(bg);
        // An authored lane remains assigned/playable even when it is routed
        // to a DIFFERENT Song Part. Only fully unassigned lanes are grey.
        graph.setNoteColor(audible ? ColorTable.NOTE_ENABLED : ColorTable.NOTE_OFF);
        routeBox.setForeground(assignedHere && audible ? ColorTable.PANEL_TEXT_ENABLED.get()
                : ColorTable.PANEL_TEXT_DISABLED.get());
        // Identical assignment semantics to the gutter on imported tracks:
        // selected part, another part, or no part at all.
        assignmentStripe.setBackground(assignedHere ? ColorTable.PANEL_HIGHLIGHT.get()
                : anyAssigned ? ColorTable.PANEL_HIGHLIGHT_OTHER_PART.get() : bg);
        graph.repaint();
        repaint();
    }

    private boolean isAuthoredPlaybackActive(SequencerWrapper sequencer) {
        if (!abcPreviewMode || !sequencer.isRunning())
            return false;
        return hasActiveAuthoredPreviewTrack(destinationPart.getAbcSong(), sourcePartNumber,
                sequencer::isTrackActive);
    }

    /** Authored source rows are displayed for every selected Song Part, but their
     * playback is NOT scoped to that part's routing checkbox. Check the actual
     * preview tracks in every destination, just like imported-track highlighting.
     * The sequencer determines whether mute/solo makes each track audible. */
    static boolean hasActiveAuthoredPreviewTrack(com.digero.maestro.abc.AbcSong song,
                                                  int sourcePartNumber, IntPredicate isTrackActive) {
        for (AbcPart part : song.getParts()) {
            for (var track : part.getAuthoredPreviewTracks()) {
                if (track.sourcePartNumber == sourcePartNumber
                        && isTrackActive.test(track.trackNumber))
                    return true;
            }
        }
        return false;
    }

    /** Imported-source checkboxes can reselect the retained original, which
     * removes this row's route without recreating the whole arrangement. */
    public void refreshRoutingFromProject() {
        var configured = com.digero.maestro.noteedit.AuthoredTrackRouting.targets(
            sourcePartNumber,destinationPart.getAbcSong().getAuthoredTrackRouting());
        localDestinations.clear();
        localDestinations.addAll(configured);
        assignedAnywhere = !localDestinations.isEmpty();
        routeBox.setSelected(localDestinations.contains(selectedPartNumber));
        updateAppearance(routeBox.isSelected(),assignedAnywhere);
    }

    /** Mute/solo toggles don't recreate the arrangement; repaint these rows
     * when the active destination set changes. */
    public void refreshPlaybackAppearance() {
        updateAppearance(routeBox.isSelected(), assignedAnywhere);
    }

    public int getSourcePartNumber() { return sourcePartNumber; }
    public int getSelectedPartNumber() { return selectedPartNumber; }
    public boolean isRoutedToSelectedPart() { return routeBox.isSelected(); }
    public boolean isAssignedAnywhere() { return assignedAnywhere; }
    public JCheckBox getRoutingCheckbox() { return routeBox; }
    public NoteGraph getAuthoredNoteGraph() { return graph; }
    @Override public JPanel getNoteGraph() { return graph; }
    @Override public boolean isVerticalZoomForbidden() { return false; }
    @Override public boolean isAbcPreviewMode() { return abcPreviewMode; }
    @Override public void setAbcPreviewMode(boolean enabled) {
        abcPreviewMode = enabled;
        setVisible(enabled);
        graph.setVisible(enabled);
    }
    @Override public void discard() { graph.discard(); }
    public void setShowVelocities(boolean enabled) { graph.setShowingNoteVelocity(enabled); }
}
