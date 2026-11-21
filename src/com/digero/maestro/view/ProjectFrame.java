package com.digero.maestro.view;

import static java.awt.event.InputEvent.ALT_DOWN_MASK;
import static java.awt.event.InputEvent.CTRL_DOWN_MASK;
import static java.awt.event.InputEvent.SHIFT_DOWN_MASK;

import java.awt.*;
import java.awt.dnd.DropTarget;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

import javax.sound.midi.*;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;
import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import javax.swing.filechooser.FileFilter;
import javax.xml.transform.TransformerException;

import com.digero.common.abc.AbcConstants;
import com.digero.common.abc.LotroInstrument;
import com.digero.common.midi.*;
import com.digero.common.util.*;
import com.digero.common.view.ColorSelector;
import com.digero.maestro.abc.DissonanceDetector;
import com.digero.maestro.midi.SequenceDataCache;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.digero.common.abc.StringCleaner;
import com.digero.common.i18n.UIText;
import com.digero.common.icons.IconLoader;
import com.digero.common.midi.SequencerEvent.SequencerProperty;
import com.digero.common.view.AboutDialog;
import com.digero.common.view.AudioExportManager;
import com.digero.common.view.BarNumberLabel;
import com.digero.common.view.ColorTable;
import com.digero.common.view.SongPositionLabel;
import com.digero.maestro.MaestroMain;
import com.digero.maestro.shortcuts.ShortcutManager;
import com.digero.maestro.shortcuts.BarNavigation;
import com.digero.maestro.abc.AbcConversionException;
import com.digero.maestro.abc.AbcExporter;
import com.digero.maestro.abc.AbcPart;
import com.digero.maestro.abc.AbcPartEvent;
import com.digero.maestro.abc.AbcPartEvent.AbcPartProperty;
import com.digero.maestro.abc.AbcSong;
import com.digero.maestro.abc.AbcSongEvent;
import com.digero.maestro.abc.ExportFilenameTemplate;
import com.digero.maestro.abc.PartAutoNumberer;
import com.digero.maestro.abc.PartNameTemplate;
import com.digero.maestro.abc.PolyphonyHistogram;
import com.digero.maestro.abc.QuantizedTimingInfo;
import com.digero.maestro.midi.Chord;
import com.digero.maestro.midi.SequenceInfo;
import com.digero.maestro.util.FileResolver;
import com.digero.maestro.util.ListModelWrapper;
import com.digero.maestro.util.RecentlyOpenedList;
import com.digero.maestro.util.XmlUtil;

import info.clearthought.layout.TableLayout;
import info.clearthought.layout.TableLayoutConstants;
import net.miginfocom.swing.MigLayout;

public class ProjectFrame extends JFrame implements TableLayoutConstants, ICompileConstants {
	private static final Logger log = Logger.getLogger("view");

	// future refactors might be able to make these fields final
	private SongInfoPanel songInfoPanel;
	private PartEditor partEditor;
	private SongPartsListPanel songPartsListPanel;
	private SongPartsPanel songPartsPanel;
	private SongExportSettingsPanel songExportSettingsPanel;

    private boolean uiEnabled = true;
    private boolean sourceChangeEnabled = true;

	private static final int HGAP = 4;
	private static final int VGAP = 4;
	private static final double[] LAYOUT_COLS = new double[] { 180, FILL };
	private static final double[] LAYOUT_ROWS = new double[] { FILL };
	private static final TableLayout tableLayout = new TableLayout(LAYOUT_COLS, LAYOUT_ROWS);

	private final Preferences prefs = Preferences.userNodeForPackage(MaestroMain.class);
    private final ShortcutManager keyboardShortcuts = new ShortcutManager(prefs.node("keyboardShortcuts"));

	private final int defaultStereo = 25;
	private final int defaultVolume = MidiConstants.MAX_VOLUME;

	private AbcSong abcSong;
	private boolean abcSongModified = false;

    private PolyphonyHistogram histogram = null;

	private boolean allowOverwriteSaveFile = false;
	private boolean allowOverwriteExportFile = false;
	private NoteFilterSequencerWrapper sequencer;
	private long firstMidiNoteTick = 0L;
	private VolumeTransceiver volumeTransceiver;
	private LotroSequencerWrapper abcSequencer;
	private VolumeTransceiver abcVolumeTransceiver;
	private PartAutoNumberer partAutoNumberer;
	private final PartNameTemplate partNameTemplate;
	private final ExportFilenameTemplate exportFilenameTemplate;
	private final InstrNameSettings instrNameSettings;
	private SaveAndExportSettings saveSettings;
	private MiscSettings miscSettings;

	private JPanel content;

	private Timer exportLabelHideTimer;
	private JMenu openRecentMenu;
	private JMenuItem saveMenuItem;
	private JMenuItem saveAsMenuItem;
    private JCheckBoxMenuItem noteEditModeMenuItem;
    private JFrame noteEditorFrame;
    private boolean noteEditorOpening;
    private JToggleButton pianoRollModeRadioButton;
    private JPanel rollModeHost;
    private java.awt.CardLayout rollModeCards;
    private com.digero.maestro.noteedit.NoteEditorApp.EditorTab midiRollEditor, abcRollEditor;
    private boolean rollSourceAbc;
    private boolean rollActive;
    private AbcSong rollSong;
    private long rollRefreshGeneration;
    /** Coalesces rapid dragging into a single expensive live ABC preview rebuild. */
    private final javax.swing.Timer rollLivePreviewTimer = new javax.swing.Timer(350,e -> {
        if(abcSong!=null)refreshPreviewSequence(false);
    });
    /** Regenerate the editor's output ABC without waiting for a manual note edit.
     *  Part/track changes are coalesced, but are independent of preview playback. */
    private final javax.swing.Timer rollBaselineRefreshTimer = new javax.swing.Timer(110,e -> refreshGeneratedRollAsync());

	private JMenuItem exportMenuItem;
	private JMenuItem exportAsMenuItem;
	private JMenuItem saveExpandedMidiMenuItem;
	private JMenu exportAudioMenu;
	private JMenuItem exportMp3MenuItem;
	private JMenuItem exportWavMenuItem;
	private JMenuItem chooseMidiFileMenuItem;
	private JMenuItem reloadMidiFileMenuItem;
	private JMenuItem closeProject;
	
	private RecentlyOpenedList recentlyOpenedList;

	private FileFilterDropListener dropListener = null;
	
	private JDialog themeEditorDialog;

	private ArrangementView arrangementView;

	private JButton tuneEditorButton;
	private JCheckBox hideEditsCheckbox;
	static boolean abcPreviewMode = false;
	private JToggleButton abcModeRadioButton;
	private JToggleButton midiModeRadioButton;
	private JButton playButton;
	private JButton stopButton;
	private JSlider volumeSlider;
	private JSlider stereoSlider;
	private SongPositionLabel midiPositionLabel;
	private SongPositionLabel abcPositionLabel;
	private BarNumberLabel midiBarLabel;
	private BarNumberLabel abcBarLabel;

	private JPanel midiPartsAndControls;

	private Icon playIcon;
	private Icon playIconDisabled;
	private Icon pauseIcon;
	private Icon pauseIconDisabled;
	private Icon abcPlayIcon;
	private Icon abcPlayIconDisabled;
	private Icon abcPauseIcon;
	private Icon abcPauseIconDisabled;
	private Icon stopIcon;
	private Icon stopIconDisabled;

	private long abcPreviewStartTick = 0L;
	private boolean echoingPosition = false;

	private MainSequencerListener mainSequencerListener;
	private AbcSequencerListener abcSequencerListener;
	private boolean failedToLoadLotroInstruments = false;
	private JButton sidepanelButton;
	private boolean midiResolved = false;
	
	private AudioExportManager audioExporter;

	private volatile Version latestVer;
	private CompletableFuture<Void> future = null;
	
	private JLabel feedLabel;
	private static volatile String feed = "";
	private static volatile String feedFull = "";
    private PreviewExportWorker previewWorker = null;

	/**
	 * Monotonic id bumped on every preview rebuild request in refreshPreviewSequence.
	 * The preview loaded in abcSequencer is current only when previewAppliedSeq equals
	 * previewRequestSeq. Using an id instead of a boolean is what makes it correct when
	 * two builds overlap: a build carries the id of the request it was built for, so an
	 * older build finishing can never mark a newer edit's state current.
	 * Both fields are touched only on the EDT (refreshPreviewSequence / applyPreview /
	 * PreviewExportWorker.done), so no synchronization is needed.
	 */
	private long previewRequestSeq = 0;
	/** The previewRequestSeq whose build is currently loaded in abcSequencer; -1 = none. */
	private long previewAppliedSeq = -1;

    // these properties have in common that they stop UI
    // listeners in doing all their work:
    private boolean fireTransposeListeners = true;
    private boolean fireMeterListeners = true;
    private boolean fireTempoListeners = true;
    private boolean fireDynaListeners = true;
	private boolean fireTimingListeners = true;
    private JMenuItem openItem;

	public ProjectFrame() {
        super(MaestroMain.APP_NAME + " " + MaestroMain.APP_VERSION);
        if ("32".equals(System.getProperty("sun.arch.data.model"))) {
            JOptionPane.showMessageDialog(null,
					UIText.get("maestro.you.are.running.with.32.bit.java"),
					UIText.get("maestro.32.bit.detected"), JOptionPane.ERROR_MESSAGE);
            System.err.println(
                    "You are running with 32 bit Java.\nPlease start with 64 bit Java instead.\n Find Configure Java program in Start menu and\n configure it to start the 64 bit per default.\n\n");
            // System.exit(1);
            // return;
        }

		setRootPane(new JRootPane() {
			@Override
			public void requestFocus() {
				super.requestFocus();
				if (arrangementView != null) arrangementView.stopEditingLyrics();
			}
		});

        setMinimumSize(new Dimension(512, 384));
        Util.initWinBounds(this, prefs.node("window"), 1000, 800);

        ToolTipManager.sharedInstance().setDismissDelay(8000);

        disableSpaceFocus();

        partAutoNumberer = new PartAutoNumberer(
			prefs.node("partAutoNumberer"));

        partNameTemplate = new PartNameTemplate(
			prefs.node("partNameTemplate"));

        exportFilenameTemplate = new ExportFilenameTemplate(
			prefs.node("exportFilenameTemplate"));

        instrNameSettings = new InstrNameSettings(
			prefs.node("instrNameSettings"));

        saveSettings = new SaveAndExportSettings(
			prefs.node("saveAndExportSettings"));

		//if misc settings is empty use fallback; Maestro 2.5.0.115 and earlier save misc settings in saveAndExportSettings
        miscSettings = new MiscSettings(
			prefs.node("miscSettings"),
                true);

        if (miscSettings.checkForUpdates) checkVersionCompare();

		String welcomeMessageTitle = UIText.get("maestro.welcomeMessageTitle");
		String welcomeMessage =	UIText.get("maestro.DnD.file.to.open") + UIText.get("maestro.use.file.open");

        checkVolumeTransceiver();

        try {
            sequencer = new NoteFilterSequencerWrapper();
            if (volumeTransceiver != null)
                sequencer.addTransceiver(volumeTransceiver);

            abcSequencer = new LotroSequencerWrapper();
            if (abcVolumeTransceiver != null)
                abcSequencer.addTransceiver(abcVolumeTransceiver);

            if (LotroSequencerWrapper.getLoadLotroSynthError() != null) {
                welcomeMessageTitle = UIText.get("maestro.could.not.load.lotro.instrument.sounds");
				welcomeMessage = UIText.get("maestro.abc.preview.will.use.standard.midi.instruments.instead", LotroSequencerWrapper.getLoadLotroSynthError());
                failedToLoadLotroInstruments = true;
            }

        } catch (MidiUnavailableException | IllegalStateException e) {// the state exception is important to catch
			// Wrap so MaestroMain can react, but don't show UI or exit from here.
			throw new SequencerInitException(e);
        }

        // ------- SWING stuff starts here -------

		// SongInfoPanel
		boolean showGenreAndMood = miscSettings.showBadger;
		String defaultTranscriber = prefs.get("abcplayer.transcriber", "");
		songInfoPanel = new SongInfoPanel(showGenreAndMood, defaultTranscriber);
        songInfoPanel.setChangeListener(this::updateAbcSongFromSongInfo);

		// PartEditor
		partEditor = new PartEditor(this, null, miscSettings);

		// SongPartsListPanel
		songPartsListPanel = new SongPartsListPanel(abcSequencer, miscSettings);
        rollLivePreviewTimer.setRepeats(false);
        rollBaselineRefreshTimer.setRepeats(false);
        songPartsListPanel.setNoteEditorMuteSoloListener(this::syncRollMuteSolo);
		songPartsListPanel.addListSelectionListener(e -> {
			if (e.getValueIsAdjusting())
        		return;
						
			AbcPart abcPart = songPartsListPanel.getSelectedPart();
            if (abcPart != null && abcRollEditor != null)
                selectExactRollPart(abcPart);
            applyRollPlaybackScope();

			sequencer.getFilter().onAbcPartChanged(abcPart != null);
			abcSequencer.getFilter().onAbcPartChanged(abcPart != null);
			arrangementView.setAbcPart(abcPart, false);

			if (abcPart != null) {
				scheduleUiRefresh();
			} else {
				if (songPartsListPanel.getModel().getSize() > 0) {
					// If ctrl-clicking to deselect this will ensure something is selected
					songPartsListPanel.selectPart(0);
				}
			}
		});

		// SongPartsPanel
		songPartsPanel = new SongPartsPanel(this.songPartsListPanel);
		songPartsPanel.setActionListener(createSongPartsActionListener());

		//SongExportSettingsPanel
		songExportSettingsPanel = new SongExportSettingsPanel();
		fireTimingListeners = false;
		songExportSettingsPanel.setTimingMode(TimingMode.getFromSettings(saveSettings.defaultTiming));
		fireTimingListeners = true;
		songExportSettingsPanel.setActionListener(createSongExportSettingsListener());

        loadIcons();

        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (closeProjectForShutdown()) {
                    setVisible(false);
                    dispose();
                    System.exit(0);
                }
            }
        });

        loadControlIcons();

        // TableLayout tableLayout = new TableLayout(LAYOUT_COLS, LAYOUT_ROWS);
        tableLayout.setHGap(HGAP);
        tableLayout.setVGap(VGAP);

        content = new JPanel(tableLayout, false);
        setContentPane(content);

        generateMidiPartsAndControlsPanel();

		// after arrangementView is defined, but before welcome message is set.
		initTheme();

        add(generateTopLevelSplitPane(), "0, 0, 1, 0");

        dropListener = new FileFilterDropListener(false, Util.MID_FILE_EXTENSION_NO_DOT,
                Util.MIDI_FILE_EXTENSION_NO_DOT, Util.KAR_FILE_EXTENSION_NO_DOT, Util.ABC_FILE_EXTENSION_NO_DOT,
                Util.TXT_FILE_EXTENSION_NO_DOT, Util.MSX_FILE_EXTENSION_NO_DOT);
        dropListener.addActionListener(e -> {
            final File file = dropListener.getDroppedFile();
            SwingUtilities.invokeLater(() -> openFile(file));
        });
        new DropTarget(this, dropListener);

        // dropListener.exclude = partsList; // not the cause of the partsList d'n'd flicker

        mainSequencerListener = new MainSequencerListener();
        sequencer.addChangeListener(mainSequencerListener);

        abcSequencerListener = new AbcSequencerListener();
        abcSequencer.addChangeListener(abcSequencerListener);

        audioExporter = new AudioExportManager(this, MaestroMain.APP_NAME + " " + MaestroMain.APP_VERSION, prefs);

        initMenu();
        onSaveAndExportSettingsChanged();
        arrangementView.showInfoMessage(formatInfoMessage(welcomeMessageTitle, welcomeMessage, getHTMLFontSizeNormal()));
		
        // One shared dispatcher for the project and Maestro-owned dialogs.
        // It consumes Space before focused controls or menus can activate.
        keyboardShortcuts.install(this);

        /* Add a listener to remove focus from the current component when somewhere else is
         clicked.*/
        MouseAdapter listenForFocus = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                getRootPane().requestFocus();
				if(e.getSource() instanceof SongPartsListPanel)
					arrangementView.stopEditingLyrics();
            }
        };
        
		// Add to main structural panels that might capture clicks
		addMouseListener(listenForFocus);
		content.addMouseListener(listenForFocus);
		songInfoPanel.addMouseListener(listenForFocus);
		songPartsListPanel.addMouseListener(listenForFocus);
		songPartsPanel.addMouseListener(listenForFocus);
		songExportSettingsPanel.addMouseListener(listenForFocus);
		if (midiPartsAndControls != null) midiPartsAndControls.addMouseListener(listenForFocus);
		if (playControlPanel != null) playControlPanel.addMouseListener(listenForFocus);
		if (arrangementView != null) arrangementView.addMouseListener(listenForFocus);

		// Make sure content is capable of taking focus if RootPane refuses
		content.setFocusable(true);
		getRootPane().setFocusable(true);
		arrangementView.setFocusable(true);

		/* refreshUi once to set the initial state of the UI.
		This is needed in case a MIDI file is loaded by default or on first run,
		and also to set the correct state of the UI when no song is loaded. */
		scheduleUiRefresh();
    }

	/** @return true if no audio output line exists on this machine at all. */
	public static boolean hasNoAudioOutput() {
		try {
			DataLine.Info out = new DataLine.Info(SourceDataLine.class, null); // null = any format
			for (Mixer.Info mi : AudioSystem.getMixerInfo()) {
				try {
					if (AudioSystem.getMixer(mi).isLineSupported(out))
						return false;   // at least one device can play audio
				} catch (Exception | LinkageError ignored) {
					// a mixer that won't even answer - skip it
				}
			}
			return true;                // nothing can output audio
		} catch (Throwable t) {
			return true;                // if probing itself fails, treat as no audio
		}
	}

	public void highlightPartsForTrack(int trackNumber) {
		if (songPartsListPanel != null) {
			songPartsListPanel.highlightPartsForTrack(trackNumber);
		}
	}

	public void clearPartsTrackHighlight(int trackNumber) {
		if (songPartsListPanel != null) {
			songPartsListPanel.clearTrackHighlight(trackNumber);
		}
	}

	public static class SequencerInitException extends RuntimeException {
		public SequencerInitException(Throwable cause) { super(cause); }
	}

	/**
	 * update the abcSong with the new songInfo data. This is called when the user changes a field in the SongInfoPanel.
	 * 
	 * @param field
	 * @param songInfo
	 */
    private void updateAbcSongFromSongInfo(SongInfoField field,
        SongInfo songInfo) {		
		
		if (abcSong == null)
        		return;

    	switch (field) {
        	case TITLE ->
            	abcSong.setTitle(songInfo.title());

        	case COMPOSER ->
           		abcSong.setComposer(songInfo.composer());

        	case TRANSCRIBER -> {
            	abcSong.setTranscriber(songInfo.transcriber());
            	prefs.put("abcplayer.transcriber", songInfo.transcriber());
        	}

        	case GENRE ->
            	abcSong.setGenre(songInfo.genre());

        	case MOOD ->
            	abcSong.setMood(songInfo.mood());
    	}
	}

	/**
	 * update the SongInfoPanel with the current abcSong data. This is called when a new abcSong is loaded or created.
	 */
	private void updateSongInfoFromAbcSong() {
		if (!abcSong.isFromAbcFile() && !abcSong.isFromXmlFile()) {
        	abcSong.setTranscriber(songInfoPanel.getSongInfo().transcriber());
    	}

    	this.songInfoPanel.setSongInfo(new SongInfo(
            abcSong.getTitle(),
            abcSong.getComposer(),
            abcSong.getTranscriber(),
            abcSong.getGenre(),
            abcSong.getMood()
    	));
	}

	/**
	 * Clear the SongInfoPanel. This is called when a new abcSong is loaded or created.
	 */
	private void clearSongInfoPanel(){
		this.songInfoPanel.clearSongInfo();
	}

	/**
	 * get the current song info from the SongInfoPanel. This is called when a new abcSong is loaded or created.
	 * 
	 * @return The current song info
	 */
	private SongInfo getCurrentSongInfo() {
		if (abcSong == null)
        	return SongInfo.empty();

		return new SongInfo(
            abcSong.getTitle(),
            abcSong.getComposer(),
            abcSong.getTranscriber(),
            abcSong.getGenre(),
            abcSong.getMood()
    	);
	}

	/**
	 * Create a new SongPartsActionListener that will handle the actions from the SongPartsPanel.
	 * This might be a good candidate for future refactoring into a separate class, but for now it is an anonymous inner class.
	 * 
	 * @return The created {@link SongPartsActionListener}
	 */
	private SongPartsActionListener createSongPartsActionListener() {
		return new SongPartsActionListener() {

			@Override
			public void createPartRequested() {
				if (abcSong != null)
					abcSong.createNewPart();
			}

			@Override
			public void deletePartRequested() {
				if (abcSong == null) 
					return;
				
				if (abcSong.getParts().size() == 1) {
					// When deleting last past, make sure a new part is replacing it, so something
					// is selected
					AbcPart deleteMe = songPartsListPanel.getSelectedPart();
					abcSong.createNewPart();
					abcSong.deletePart(deleteMe);
				}

				if (abcSong.getParts().size() > 1)
					abcSong.deletePart(songPartsListPanel.getSelectedPart());
			}

			@Override
			public void sortPartsRequested() {
				if (abcSong != null) 
					abcSong.autoSortParts();
			}

			@Override
			public void numeratePartsRequested() {
				if (abcSong != null)
					abcSong.assignNumbersToSimilarPartTypes();
			}

			@Override
			public void openPartEditorRequested() {
				partEditor.setVisible(!partEditor.isVisible());
			}
			
		};
	}

	private SongExportSettingsListener createSongExportSettingsListener() {
		return new SongExportSettingsListener() {
			@Override
			public void transposeSettingsChanged() {
				if (abcSong != null && fireTransposeListeners) {
                    if (rollActive && abcRollEditor != null) stageRollEdits();
                    abcSong.setTranspose(songExportSettingsPanel.getTranspose());
					refreshPreviewSequence(false);
				}
			}

			@Override
			public void tempoSettingsChanged() {
				if (abcSong != null) {
					if (fireTempoListeners)
						abcSong.setTempoBPM(songExportSettingsPanel.getTempo());

					abcSequencer.setTempoFactor(abcSong.getTempoFactor());

					if (fireTempoListeners)
						refreshPreviewSequence(false);

				} else {
					abcSequencer.setTempoFactor(1.0f);
				}
			}

			@Override
			public void tempoResetRequested() {
				if (abcSong != null) {
					float tempoFactor = abcSong.getTempoFactor();

					songExportSettingsPanel.setTempo(
							abcSong.getSequenceInfo().getPrimaryTempoBPM());

					if (tempoFactor != 1.0f) {
						refreshPreviewSequence(false);
					}
				} else {
					songExportSettingsPanel.setTempo(MidiConstants.DEFAULT_TEMPO_BPM);
				}				
			}

			@Override
			public void timeSignatureChanged() {
				if (abcSong != null && fireMeterListeners) {
					abcSong.setTimeSignature(songExportSettingsPanel.getTimeSignature());

					// Breaking up of long notes can depend on time signature for bar lines.
					refreshPreviewSequence(false);
				}
			}

			@Override
			public void keySignatureChanged() {
				if (abcSong != null)
					abcSong.setKeySignature(songExportSettingsPanel.getKeySignature());
			}

			@Override
			public void timingModeChanged() {
				TimingMode mode = songExportSettingsPanel.getTimingMode();
            	songExportSettingsPanel.setTimingModeToolTipText(mode.getTooltip());

				if (abcSong != null && fireTimingListeners) {
					abcSong.setTimings(mode.organic, mode.multistage, mode.mixTimings, mode.swing, mode.priority, mode.upgraded);

					refreshPreviewSequence(false);
				}
			}

			@Override
			public void dynamicChordModeChanged() {
				if (abcSong != null) {
					if (fireDynaListeners) {
						abcSong.dynamicsMethod = songExportSettingsPanel.getDynamicChordMode();
						refreshPreviewSequence(false);
					}
				}
			}

			@Override
			public void countOnlyTempoChangesFromFirstTrackSettingsChanged() {
				if (abcSong == null)
                	return;            

            	if (abcSong.getProjectFile() == null) {
                	//return; // should be an invalid state, item is disabled if no msx file
            	}

            	abcSong.setUsingOldTempos(songExportSettingsPanel.isCountOnlyTempoChangesFromFirstTrackSelected());

            	setAbcSongModified(true);
				File sourceFile = abcSong.getSourceFile();
				reloadWithNewSource(sourceFile);
			}

			@Override
			public void exportRequested() {
				exportAbc();
			}
		};
	}

	private void generateMidiPartsAndControlsPanel() {
		arrangementView = new ArrangementView(sequencer, partAutoNumberer, abcSequencer, miscSettings.showMaxPolyphony, miscSettings.dissEnabled, this);
		arrangementView.setPoeticalLyricsAdvancement(saveSettings.countUpLyrics);
		arrangementView.setPoeticalLyricsTimestampEveryLine(saveSettings.lyricsTimestampEveryLine);
		arrangementView.addSettingsActionListener(e -> doSettingsDialog(SettingsDialog.NUMBERING_TAB));

		tuneEditorButton = new JButton();
		tuneEditorButton.setText(UIText.get("maestro.tune.editor"));
		tuneEditorButton
				.setToolTipText(UIText.get("maestro.tuneEdit.tip"));
		tuneEditorButton.addActionListener(e -> TuneEditor.show(ProjectFrame.this, abcSong));

		hideEditsCheckbox = new JCheckBox();
		hideEditsCheckbox.setText(UIText.get("maestro.hide.edits"));
		hideEditsCheckbox
				.setToolTipText(UIText.get("maestro.html.hide.edits.on.the.tracks.html"));
		hideEditsCheckbox.addActionListener(e -> abcSong.setHideEdits(hideEditsCheckbox.isSelected()));
		
		final Insets playControlButtonMargin = new Insets(5, 20, 5, 20);

		playButton = new JButton(playIcon);
		playButton.setDisabledIcon(playIconDisabled);
		playButton.setMargin(playControlButtonMargin);
		playButton.addActionListener(e -> updateSequencer());

		stopButton = new JButton(stopIcon);
		stopButton.setDisabledIcon(stopIconDisabled);
		stopButton.setToolTipText(UIText.get("maestro.stop"));
		stopButton.setMargin(playControlButtonMargin);
		stopButton.addActionListener(e -> {
            // All three views share Maestro's sequencers. Reset both transports,
            // including when the piano roll is visible, so the inactive source
            // cannot leave a stale position behind after stopping ABC Preview.
			abcSequencer.stop();
			sequencer.stop();
			abcSequencer.reset(false);
			sequencer.reset(false);
            scheduleUiRefresh();
		});

		ActionListener modeButtonListener = e -> {
            if(rollActive){
                stageRollEdits();
                leavePianoRoll();
            }
            rollSourceAbc=abcModeRadioButton.isSelected();
			updatePreviewMode(abcModeRadioButton.isSelected());
			if (arrangementView != null) {
				arrangementView.repaint();
			}
		};

		midiModeRadioButton = new JRadioButton(UIText.get("maestro.original"));
		midiModeRadioButton.addActionListener(modeButtonListener);
		midiModeRadioButton.setMargin(new Insets(1, 5, 1, 5));

		abcModeRadioButton = new JRadioButton(UIText.get("maestro.abc.preview"));
		abcModeRadioButton.addActionListener(modeButtonListener);
		abcModeRadioButton.setMargin(new Insets(1, 5, 1, 5));

		ButtonGroup modeButtonGroup = new ButtonGroup();
		modeButtonGroup.add(abcModeRadioButton);
		modeButtonGroup.add(midiModeRadioButton);
        pianoRollModeRadioButton=new JRadioButton("Piano roll");
        pianoRollModeRadioButton.setMargin(new Insets(1,5,1,5));
        modeButtonGroup.add(pianoRollModeRadioButton);
        pianoRollModeRadioButton.addActionListener(e -> enterPianoRoll());

		midiModeRadioButton.setSelected(true);
		abcPreviewMode = abcModeRadioButton.isSelected();
        SequencerWrapper.isAbcPreview = abcPreviewMode;

		volumeSlider = new JSlider(0, MidiConstants.MAX_VOLUME, getVolume());
		volumeSlider.setFocusable(false);
		volumeSlider.addChangeListener(e -> {
			setVolume(volumeSlider.getValue());
		});

		stereoSlider = new JSlider(0, 100, getPan());
		stereoSlider.setFocusable(false);
		stereoSlider.addChangeListener(e -> {
			setPan(stereoSlider.getValue());
		});

		midiPositionLabel = new SongPositionLabel(sequencer, "000:00/000:00");

		abcPositionLabel = new SongPositionLabel(abcSequencer, true /* adjustForTempo */, "000:00/000:00");
		abcPositionLabel.setVisible(!midiPositionLabel.isVisible());

		midiBarLabel = new BarNumberLabel(sequencer, null, true, "0000,00/0000");
		midiBarLabel.setToolTipText(UIText.get("maestro.original.bar.number"));

		abcBarLabel = new BarNumberLabel(abcSequencer, null, false,"0000/0000");
		abcBarLabel.setToolTipText(UIText.get("maestro.abc.preview.bar.number"));
		abcBarLabel.setVisible(!midiBarLabel.isVisible());

		sidepanelButton = new JButton("");
        if (Themer.isDarkMode()) {
            sidepanelButton.setIcon(IconLoader.getImageIcon("sidepanel_dark.png"));
            sidepanelButton.setDisabledIcon(IconLoader.getDisabledIcon("sidepanel_dark.png"));
        } else {
            sidepanelButton.setIcon(IconLoader.getImageIcon("sidepanel.png"));
            sidepanelButton.setDisabledIcon(IconLoader.getDisabledIcon("sidepanel.png"));
        }
		sidepanelButton.addActionListener(e -> arrangementView.sidepanelToggle());
		sidepanelButton.setToolTipText(UIText.get("maestro.tip.show.sidepanel"));
				
		feedLabel = new JLabel();
		feedLabel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				feed("", null);
				showFeed();
			}
		});
		
		playControlPanel = new JPanel(new MigLayout("fillx, hidemode 3, wrap 8, gap 8 4",
				"[][][][][][][grow -1][grow -1]"));
		//playControlPanel.setDoubleBuffered(false);
		playControlPanel.add(tuneEditorButton);
		playControlPanel.add(midiModeRadioButton);
		playControlPanel.add(playButton, "spany 2, alignx right");
		playControlPanel.add(stopButton, "spany 2");
		playControlPanel.add(new JLabel(UIText.get("maestro.volume")), "alignx right");
		playControlPanel.add(volumeSlider);
		playControlPanel.add(sidepanelButton, "spany 2, center");
		playControlPanel.add(midiPositionLabel);
		playControlPanel.add(abcPositionLabel, "wrap");
		
		playControlPanel.add(hideEditsCheckbox);
		playControlPanel.add(abcModeRadioButton);
        playControlPanel.add(pianoRollModeRadioButton);
		//play
		//stop
		playControlPanel.add(new JLabel(UIText.get("maestro.stereo")), "alignx right");
		playControlPanel.add(stereoSlider);
		//note
		playControlPanel.add(midiBarLabel);
		playControlPanel.add(abcBarLabel);

		playControlPanel.add(feedLabel, "span 8, center");

		midiPartsAndControls = new JPanel(new BorderLayout(HGAP, VGAP));
        rollModeCards = new java.awt.CardLayout();
        rollModeHost = new JPanel(rollModeCards);
        rollModeHost.add(arrangementView,"arrangement");
        rollModeHost.add(new JLabel("Loading piano roll...",SwingConstants.CENTER),"loading");
		midiPartsAndControls.add(rollModeHost, BorderLayout.CENTER);
		midiPartsAndControls.add(playControlPanel, BorderLayout.SOUTH);
		midiPartsAndControls.setBorder(BorderFactory.createTitledBorder(UIText.get("maestro.part.settings")));
	}
	
	/**
	 * Call this from any thread
	 * @param str info to be shown, can be null.
	 * @param str2 tooltip, can be null.
	 */
	public static synchronized void feed(String str, String str2) {
		feed = str;
		feedFull = str2;
	}
	
	/**
	 * Call this from AWT thread only
	 */
	public void showFeed() {
		assert SwingUtilities.isEventDispatchThread();
		final String feedStr;
		final String tipStr;
		synchronized(ProjectFrame.class) {
			feedStr = feed;
			tipStr = feedFull;
		}
		if (feedStr == null) {
			feedLabel.setText(null);
			feedLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		} else {
			String dismiss = feedStr.isEmpty()?"": UIText.get("maestro.click.to.dismiss");
			feedLabel.setText(feedStr + dismiss);
			if (!feedStr.isEmpty()) {
				feedLabel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Color.RED, 2), BorderFactory.createEmptyBorder(0, 2, 0, 2)));
			} else {
				feedLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
			}
		}
		feedLabel.setToolTipText(tipStr);
		playControlPanel.validate();
	}

	/**
	 * Play/pause button (or spacebar)
	 */
	private void updateSequencer() {
        // The embedded editor's TransportBridge delegates Play/Pause here.
        // Always control Maestro's own sequencer directly: dispatching back to
        // EditorTab.playOrPause() would recurse until StackOverflowError.
		SequencerWrapper curSequencer = abcPreviewMode ? abcSequencer : sequencer;

		boolean running = !curSequencer.isRunning();

		if (abcPreviewMode) {
			if (!refreshPreviewSequence(true) && running) {
				running = false;
			} else {
				long tick = abcSequencer.getTickPosition();
				if (tick < abcPreviewStartTick)
					tick = abcPreviewStartTick;

				if (tick >= abcSequencer.getTickLength()) {
					tick = 0;
					running = false;
				}
				abcSequencer.setTickPosition(tick);
			}
		} else if (curSequencer.isAtStart()) {
			curSequencer.setTickPosition(firstMidiNoteTick);
		}

		curSequencer.setRunning(running);
		scheduleUiRefresh();
	}

	private JSplitPane generateTopLevelSplitPane() {
		JPanel abcPartsAndSettings = new JPanel(new BorderLayout(HGAP, VGAP));
		abcPartsAndSettings.add(songInfoPanel, BorderLayout.NORTH);
		JPanel partsListAndColorizer = new JPanel(new BorderLayout(HGAP, VGAP));
		partsListAndColorizer.add(songPartsPanel, BorderLayout.CENTER);
		if (SHOW_COLORIZER)
			partsListAndColorizer.add(new Colorizer(arrangementView), BorderLayout.SOUTH);
		abcPartsAndSettings.add(partsListAndColorizer, BorderLayout.CENTER);
		abcPartsAndSettings.add(songExportSettingsPanel, BorderLayout.SOUTH);

		int splitPanePos = prefs.getInt("splitPanePos", -1);

		JSplitPane topLevelSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, abcPartsAndSettings,
				midiPartsAndControls);
		topLevelSplitPane.setBorder(BorderFactory.createEmptyBorder());
		topLevelSplitPane.setContinuousLayout(true);
		topLevelSplitPane.setFocusable(false);
		if (splitPanePos != -1) {
			topLevelSplitPane.setDividerLocation(splitPanePos);
		}
		topLevelSplitPane.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
			prefs.putInt("splitPanePos", (Integer) e.getNewValue());
		});
		return topLevelSplitPane;
	}

	private void loadIcons() {
		try {
            // 15, 32, 48 and 256 are the most common used icon sizes that a modern Windows uses
			List<Image> icons = new ArrayList<>();
            BufferedImage icon16 = IconLoader.getImage("maestro_16.png");
            BufferedImage icon32 = IconLoader.getImage("maestro_32.png");
            BufferedImage icon48 = IconLoader.getImage("maestro_48.png");
            BufferedImage icon256 = IconLoader.getImage("maestro_256.png");
			if (icon16 != null) icons.add(icon16);
            if (icon32 != null) icons.add(icon32);
            if (icon48 != null) icons.add(icon48);
            if (icon256 != null) icons.add(icon256);
			setIconImages(icons);
		} catch (Exception ex) {
			// Ignore
			log.log(Level.WARNING, "Error when loading icons", ex);
		}
	}

    private void loadControlIcons() {
        playIcon = IconLoader.getImageIcon("play_blue.png");
        playIconDisabled = IconLoader.getDisabledIcon("play_blue.png");
        pauseIcon = IconLoader.getImageIcon("pause_blue.png");
        pauseIconDisabled = IconLoader.getDisabledIcon("pause_blue.png");
        abcPlayIcon = IconLoader.getImageIcon("play_yellow.png");
        abcPlayIconDisabled = IconLoader.getDisabledIcon("play_yellow.png");
        abcPauseIcon = IconLoader.getImageIcon("pause.png");
        abcPauseIconDisabled = IconLoader.getDisabledIcon("pause.png");
        stopIcon = IconLoader.getImageIcon("stop.png");
        stopIconDisabled = IconLoader.getDisabledIcon("stop.png");
    }

	private void checkVolumeTransceiver() {
		volumeTransceiver = new VolumeTransceiver();
		volumeTransceiver.setVolume(prefs.getInt("volumizer", defaultVolume));

		abcVolumeTransceiver = new VolumeTransceiver();
		abcVolumeTransceiver.setVolume(volumeTransceiver.getVolume());
	}

	private void disableSpaceFocus() {
        // A focused mode radio button otherwise intercepts pressed/released SPACE
        // before the root pane's transport action can receive them.
        com.digero.maestro.noteedit.SpaceKeyBindings.reserveSpaceForTransport();
    }

	private static void discardObject(IDiscardable object) {
		if (object != null)
			object.discard();
	}

	@Override
	public void dispose() {
		hideEditsCheckbox.setSelected(false);
		if (abcSong != null) {
			abcSong.getParts().getListModel().removeListDataListener(partsListListener);
		}

		discardObject(sequencer);
		discardObject(abcSequencer);
		discardObject(abcSong);
		discardObject(midiPositionLabel);
		discardObject(abcPositionLabel);
		discardObject(midiBarLabel);
		discardObject(abcBarLabel);

		arrangementView.setTextnote("");
        arrangementView.setLyrics("");
		arrangementView.setLyricLines(null, false);
        arrangementView.setStats("");
		arrangementView.sidepanelVisible(false);

        keyboardShortcuts.close();
        super.dispose();
    }

	private void initMenu() {
		JMenuBar menuBar = new JMenuBar();
		setJMenuBar(menuBar);

		JMenu fileMenu = menuBar.add(new JMenu(UIText.get("maestro.menu.file")));
        // Optional, modeless editing mode. Leave upstream conversion controls intact.
        noteEditModeMenuItem = new JCheckBoxMenuItem("Piano roll editing mode");
        noteEditModeMenuItem.addActionListener(e -> {
            if (noteEditModeMenuItem.isSelected()) {
                pianoRollModeRadioButton.setSelected(true);
                enterPianoRoll();
            }
            else if(rollActive){
                stageRollEdits();
                leavePianoRoll();
                if(rollSourceAbc)abcModeRadioButton.setSelected(true);
                else midiModeRadioButton.setSelected(true);
            }
        });
        fileMenu.add(noteEditModeMenuItem);
		fileMenu.setMnemonic('F');

        JMenuItem newAbcItem = fileMenu.add(new JMenuItem("New ABC Composition..."));
        newAbcItem.addActionListener(e -> createBlankComposition());
        fileMenu.addSeparator();

        openItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.open.file")));
		openItem.setMnemonic('O');
		openItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, CTRL_DOWN_MASK));
		openItem.addActionListener(new ActionListener() {
			JFileChooser openFileChooser;

			@Override
			public void actionPerformed(ActionEvent e) {
				if (openFileChooser == null) {
					openFileChooser = createFilteredOpenChooser(prefs.get("openFileChooser.path", null));
					openFileChooser.setMultiSelectionEnabled(false);
                    openFileChooser.addChoosableFileFilter(
                            new ExtensionFileFilter("MIDI",
                                    Util.MID_FILE_EXTENSION_NO_DOT,
                                    Util.MIDI_FILE_EXTENSION_NO_DOT, Util.KAR_FILE_EXTENSION_NO_DOT));
                    openFileChooser.addChoosableFileFilter(
                            new ExtensionFileFilter("ABC",
                                    Util.ABC_FILE_EXTENSION_NO_DOT,
                                    Util.TXT_FILE_EXTENSION_NO_DOT));
                    openFileChooser.addChoosableFileFilter(
                            new ExtensionFileFilter(UIText.get("maestro.project"),
                                    Util.MSX_FILE_EXTENSION_NO_DOT));
					openFileChooser.setFileFilter(
							new ExtensionFileFilter(UIText.get("maestro.midi.abc.and.project"),
									Util.MID_FILE_EXTENSION_NO_DOT,
									Util.MIDI_FILE_EXTENSION_NO_DOT, Util.KAR_FILE_EXTENSION_NO_DOT,
									Util.ABC_FILE_EXTENSION_NO_DOT,
									Util.TXT_FILE_EXTENSION_NO_DOT, Util.MSX_FILE_EXTENSION_NO_DOT));
                    openFileChooser.setAcceptAllFileFilterUsed(false);
                }

				int result = openFileChooser.showOpenDialog(ProjectFrame.this);
				if (result == JFileChooser.APPROVE_OPTION) {
					openFile(openFileChooser.getSelectedFile());
					prefs.put("openFileChooser.path", openFileChooser.getCurrentDirectory().getAbsolutePath());
				}
			}
		});
		
		recentlyOpenedList = new RecentlyOpenedList(prefs.node("recentlyOpened"));
		
		openRecentMenu = new JMenu(UIText.get("maestro.menu.open.recent.projects"));
		fileMenu.add(openRecentMenu);
		
		updateOpenRecentMenu();

		fileMenu.addSeparator();

		saveMenuItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.save.0", AbcSong.MSX_FILE_DESCRIPTION)));
		saveMenuItem.setIcon(IconLoader.getImageIcon("msxfile_16.png"));
		saveMenuItem.setDisabledIcon(IconLoader.getDisabledIcon("msxfile_16.png"));
		saveMenuItem.setMnemonic('S');
		saveMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, CTRL_DOWN_MASK));
		saveMenuItem.addActionListener(e -> save());

		saveAsMenuItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.save.0.as", AbcSong.MSX_FILE_DESCRIPTION)));
		saveAsMenuItem.setMnemonic('A');
		saveAsMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_S, CTRL_DOWN_MASK | SHIFT_DOWN_MASK));
		saveAsMenuItem.addActionListener(e -> saveAs());

		fileMenu.addSeparator();

		exportMenuItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.export.abc")));
		exportMenuItem.setIcon(IconLoader.getImageIcon("abcfile_16.png"));
		exportMenuItem.setDisabledIcon(IconLoader.getDisabledIcon("abcfile_16.png"));
		exportMenuItem.setMnemonic('E');
		exportMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, CTRL_DOWN_MASK));
		exportMenuItem.addActionListener(e -> exportAbc());

		exportAsMenuItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.export.abc.as")));
		exportAsMenuItem.setMnemonic('p');
		exportAsMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_E, CTRL_DOWN_MASK | SHIFT_DOWN_MASK));
		exportAsMenuItem.addActionListener(e -> exportAbcAs());

		fileMenu.addSeparator();
        JMenuItem exportRollItem = fileMenu.add(new JMenuItem("Export Piano Roll As..."));
        exportRollItem.addActionListener(e -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if(rollActive && editor!=null) editor.exportAsFromMenu();
            else JOptionPane.showMessageDialog(this,"Open Piano Roll mode first.");
        });
        JMenuItem saveRollItem = fileMenu.add(new JMenuItem("Store Piano Roll Edits in Project"));
        saveRollItem.addActionListener(e -> {
            if(abcSong==null)return;
            try {stageRollEdits();setAbcSongModified(true);}
            catch(RuntimeException ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Piano Roll",JOptionPane.ERROR_MESSAGE);}
        });

		fileMenu.addSeparator();

		saveExpandedMidiMenuItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.export.expanded.midi")));
		saveExpandedMidiMenuItem.addActionListener(e -> expandMidi());

		fileMenu.addSeparator();
		
		exportAudioMenu = new JMenu(UIText.get("maestro.menu.export.audio"));
		
		fileMenu.add(exportAudioMenu);
		
		exportMp3MenuItem = exportAudioMenu.add(new JMenuItem(UIText.get("maestro.menu.export.mp3.file")));
		exportMp3MenuItem.addActionListener(e -> {
			if (!abcSequencer.isLoaded() || abcSong == null || audioExporter.isExporting()) {
				Toolkit.getDefaultToolkit().beep();
                log.warning("Cannot export audio. abcSequencer.isLoaded()"+abcSequencer.isLoaded()+" abcSong != null "+(abcSong != null)+" audioExporter.isExporting()"+audioExporter.isExporting());
				return;
			}
			audioExporter.exportMp3Builtin(abcSequencer, getAbcExportFile(), abcSong.getTitle(), abcSong.getComposer());
		});

		exportWavMenuItem = exportAudioMenu.add(new JMenuItem(UIText.get("maestro.menu.export.wav.file")));
		exportWavMenuItem.addActionListener(e -> {
			if (!abcSequencer.isLoaded() || abcSong == null || audioExporter.isExporting()) {
				Toolkit.getDefaultToolkit().beep();
                log.warning("Cannot export audio. abcSequencer.isLoaded()"+abcSequencer.isLoaded()+" abcSong != null "+(abcSong != null)+" audioExporter.isExporting()"+audioExporter.isExporting());
				return;
			}
			refreshPreviewSequence(true);//important, so last edits gets written
			audioExporter.exportWav(abcSequencer, getAbcExportFile());
		});
		
		fileMenu.addSeparator();
		
		chooseMidiFileMenuItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.change.midi.file")));
		chooseMidiFileMenuItem.addActionListener(e -> {
			if (abcSong == null || abcSong.getSourceFile() == null || abcSong.getProjectFile() == null) {
				return; // should be an invalid state, item is disabled if no msx file
			}
			
			int result = JOptionPane.showConfirmDialog(ProjectFrame.this, UIText.get("maestro.would.you.like.to.continue"), UIText.get("maestro.proceed"),
					JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
			if (result != JOptionPane.YES_OPTION)
				return;

			JFileChooser openMidiChooser = createFilteredOpenChooser(abcSong.getSourceFile().getAbsoluteFile().getParent());
			openMidiChooser.setMultiSelectionEnabled(false);
            openMidiChooser.addChoosableFileFilter(
                    new ExtensionFileFilter("MIDI",
                            Util.MID_FILE_EXTENSION_NO_DOT,
                            Util.MIDI_FILE_EXTENSION_NO_DOT, Util.KAR_FILE_EXTENSION_NO_DOT));
            openMidiChooser.addChoosableFileFilter(
                    new ExtensionFileFilter("ABC",
                            Util.ABC_FILE_EXTENSION_NO_DOT,
                            Util.TXT_FILE_EXTENSION_NO_DOT));
			openMidiChooser.setFileFilter(
					new ExtensionFileFilter(UIText.get("maestro.menu.midi.and.abc.files"), Util.MID_FILE_EXTENSION_NO_DOT,
							Util.MIDI_FILE_EXTENSION_NO_DOT, Util.KAR_FILE_EXTENSION_NO_DOT, Util.ABC_FILE_EXTENSION_NO_DOT,
							Util.TXT_FILE_EXTENSION_NO_DOT));
            openMidiChooser.setAcceptAllFileFilterUsed(false);

			result = openMidiChooser.showOpenDialog(ProjectFrame.this);
			if (result != JFileChooser.APPROVE_OPTION) {
				return;
			}
			
			reloadWithNewSource(openMidiChooser.getSelectedFile());
		});
		
		reloadMidiFileMenuItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.reload.midi.file")));
		reloadMidiFileMenuItem.setMnemonic(KeyEvent.VK_R);
		reloadMidiFileMenuItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, CTRL_DOWN_MASK));
		reloadMidiFileMenuItem.addActionListener(e -> {
			if (abcSong == null || abcSong.getProjectFile() == null) {
				return; // should be an invalid state, item is disabled if no msx file
			}
			File sourceFile = abcSong.getSourceFile();
			reloadWithNewSource(sourceFile);
		});
		
		fileMenu.addSeparator();

		closeProject = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.close.project")));
		closeProject.addActionListener(e -> closeProjectForNormal());

		JMenuItem exitItem = fileMenu.add(new JMenuItem(UIText.get("maestro.menu.exit")));
		exitItem.setMnemonic('x');
		exitItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F4, ALT_DOWN_MASK));
		exitItem.addActionListener(e -> {
			if (closeProjectForShutdown()) {
				setVisible(false);
				dispose();
				System.exit(0);
			}
		});

		JMenu toolsMenu = menuBar.add(new JMenu(UIText.get("maestro.menu.tools")));
		toolsMenu.setMnemonic('T');

		JMenuItem settingsItem = toolsMenu.add(new JMenuItem(UIText.get("maestro.menu.options")));
		settingsItem.setIcon(IconLoader.getImageIcon("gear_16.png"));
		settingsItem.setDisabledIcon(IconLoader.getDisabledIcon("gear_16.png"));
		settingsItem.setMnemonic('O');
		settingsItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_T, CTRL_DOWN_MASK));
		settingsItem.addActionListener(e -> doSettingsDialog());

		JMenuItem themeItem = toolsMenu.add(new JMenuItem(UIText.get("maestro.menu.colors")));
		themeItem.setMnemonic('C');
		themeItem.addActionListener(e -> showThemeEditor());

		toolsMenu.addSeparator();
		
		JMenuItem helpItem = toolsMenu.add(new JMenuItem(UIText.get("maestro.menu.help.opens.in.browser")));
		helpItem.setMnemonic('H');
		helpItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
		helpItem.addActionListener(e -> {
			Util.openURL(MaestroMain.WIKI_URL, this);
		});
		
        JMenuItem forkReleases = toolsMenu.add(new JMenuItem("Fork Releases..."));
        forkReleases.setToolTipText("Open avalkon/maestro-editor releases in your browser.");
        forkReleases.setMnemonic('V');
        forkReleases.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F6, 0));
        forkReleases.addActionListener(e ->
            Util.openURL("https://github.com/avalkon/maestro-editor/releases", this));

        JMenuItem versionItem = toolsMenu.add(new JMenuItem("Check for Upstream Updates"));
        versionItem.setToolTipText("Checks original upstream NikolaiVChr/maestro versions only.");
        versionItem.addActionListener(e -> checkVersionCompare());
		
		toolsMenu.addSeparator();

		JMenuItem aboutItem = toolsMenu.add(new JMenuItem(UIText.get("maestro.menu.about.0", MaestroMain.APP_NAME)));
		aboutItem.setMnemonic('A');
		aboutItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0));
		aboutItem.addActionListener(e -> AboutDialog.show(ProjectFrame.this, MaestroMain.APP_NAME,
				MaestroMain.APP_VERSION, MaestroMain.WIKI_URL, "maestro_64.png"));
        // Existing upstream menu accelerators and the new shortcuts share
        // a preference-backed registry, so menu hints follow user remaps.
        keyboardShortcuts.attachMenu("file.open", openItem);
        keyboardShortcuts.attachMenu("file.save", saveMenuItem);
        keyboardShortcuts.attachMenu("file.saveAs", saveAsMenuItem);
        keyboardShortcuts.attachMenu("file.export", exportMenuItem);
        keyboardShortcuts.attachMenu("file.exportAs", exportAsMenuItem);
        keyboardShortcuts.attachMenu("file.reload", reloadMidiFileMenuItem);
        keyboardShortcuts.attachMenu("file.exit", exitItem);
        keyboardShortcuts.attachMenu("tools.options", settingsItem);
        keyboardShortcuts.attachMenu("tools.help", helpItem);
        keyboardShortcuts.attachMenu("tools.updates", versionItem);
        keyboardShortcuts.attachMenu("tools.about", aboutItem);

        JMenu transportMenu = menuBar.add(new JMenu("Playback / Navigate"));
        attachShortcut(transportMenu, "transport.play", "Play / Pause (Space)", () -> {
            if (sequencer.isLoaded()) updateSequencer();
        });
        attachShortcut(transportMenu, "transport.stop", "Stop", this::stopFromShortcut);
        attachShortcut(transportMenu, "bar.start", "Beginning of song", this::seekSongStart);
        attachShortcut(transportMenu, "bar.current", "Beginning of current bar", () -> seekBar(0));
        attachShortcut(transportMenu, "bar.next", "Beginning of next bar", () -> seekBar(1));
        attachShortcut(transportMenu, "bar.previous", "Beginning of previous bar", () -> seekBar(-1));
        attachShortcut(transportMenu, "bar.forward1", "Forward 1 bar", () -> seekBar(1));
        attachShortcut(transportMenu, "bar.back1", "Back 1 bar", () -> seekBar(-1));
        attachShortcut(transportMenu, "bar.forward5", "Forward 5 bars", () -> seekBar(5));
        attachShortcut(transportMenu, "bar.back5", "Back 5 bars", () -> seekBar(-5));
        attachShortcut(transportMenu, "bar.forward10", "Forward 10 bars", () -> seekBar(10));
        attachShortcut(transportMenu, "bar.back10", "Back 10 bars", () -> seekBar(-10));

        JMenu windowsMenu = menuBar.add(new JMenu("Windows / Piano Roll"));
        attachShortcut(windowsMenu, "window.section", "Toggle Section Editor", this::toggleSectionEditor);
        attachShortcut(windowsMenu, "window.part", "Toggle Part Editor", () -> partEditor.setVisible(!partEditor.isVisible()));
        attachShortcut(windowsMenu, "window.tune", "Toggle Tune Editor", () -> {
            if (TuneEditor.isOpen()) TuneEditor.close();
            else if (abcSong != null) TuneEditor.show(this, abcSong);
        });
        attachShortcut(windowsMenu, "window.theme", "Toggle Color Editor", () -> {
            if (themeEditorDialog.isVisible()) themeEditorDialog.setVisible(false);
            else showThemeEditor();
        });
        attachShortcut(windowsMenu, "window.volume", "Show/hide track note velocities", () -> {
            arrangementView.setShowAllNoteVelocities(!arrangementView.isShowingAllNoteVelocities());
        });
        attachShortcut(windowsMenu, "window.roll", "Toggle Piano Roll", () -> noteEditModeMenuItem.doClick());

        JMenu editMenu = menuBar.add(new JMenu("Piano Roll Edit"));
        JMenuItem trackRoutingItem = editMenu.add(new JMenuItem("Assign Authored Tracks to Parts..."));
        trackRoutingItem.addActionListener(e -> editAuthoredTrackRouting());
        editMenu.addSeparator();
        attachShortcut(editMenu, "editor.copy", "Copy", () -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (rollActive && editor != null) editor.copyFromShortcut();
        });
        attachShortcut(editMenu, "editor.cut", "Cut", () -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (rollActive && editor != null) editor.cutFromShortcut();
        });
        attachShortcut(editMenu, "editor.paste", "Paste at playhead", () -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (rollActive && editor != null) editor.pasteFromShortcut();
        });
        attachShortcut(editMenu, "editor.selectAll", "Select all visible notes", () -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (rollActive && editor != null) editor.selectAllFromShortcut();
        });
        editMenu.addSeparator();
        attachShortcut(editMenu, "editor.undo", "Undo", () -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (rollActive && editor != null) editor.undoFromShortcut();
        });
        attachShortcut(editMenu, "editor.redo", "Redo", () -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (rollActive && editor != null) editor.redoFromShortcut();
        });
        attachShortcut(editMenu, "editor.delete", "Delete selected notes", this::deleteRollSelection);
        // Backspace is a separate remappable binding for the same command.
        keyboardShortcuts.register("editor.deleteBackspace", this::deleteRollSelection);
        attachShortcut(editMenu, "editor.clear", "Clear selection", () -> {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (rollActive && editor != null) editor.clearSelectionFromShortcut();
        });

	}

    private void changeRollHighlightKey(String key){
        if(abcSong==null)return;
        abcSong.setHighlightKey(key);
        if(midiRollEditor!=null)midiRollEditor.setHighlightKey(key);
        if(abcRollEditor!=null)abcRollEditor.setHighlightKey(key);
        setAbcSongModified(true);
    }

    /** The selected Song Part determines the checkbox's destination. No
     * imported MIDI track numbers or section settings are changed. An empty
     * destination set is deliberately retained so unassigned notes remain in
     * the project and can be routed again later. */
    void setAuthoredTrackRouted(int sourcePartNumber, int destinationPartNumber, boolean enabled) {
        if (abcSong == null) return;
        if (abcRollEditor != null && abcRollEditor.isShowing()) abcRollEditor.stageChanges();
        boolean destinationExists = false;
        for (AbcPart part : abcSong.getParts())
            if (part.getPartNumber() == destinationPartNumber) destinationExists = true;
        if (!destinationExists) return;
        var routes = new java.util.LinkedHashMap<Integer,java.util.Set<Integer>>(
            abcSong.getAuthoredTrackRouting());
        var destinations = new java.util.LinkedHashSet<Integer>(
            com.digero.maestro.noteedit.AuthoredTrackRouting.targets(sourcePartNumber, routes));
        if (enabled) destinations.add(destinationPartNumber);
        else destinations.remove(destinationPartNumber);
        if (destinations.equals(java.util.Set.of(sourcePartNumber))) routes.remove(sourcePartNumber);
        else routes.put(sourcePartNumber, destinations);
        abcSong.setAuthoredTrackRouting(routes);
        arrangementView.refreshTrackRoutingAppearance();
        setAbcSongModified(true);
        refreshPreviewSequence(false);
    }

    /** Advanced route matrix; ordinary routing is now directly available via
     * the authored track checkboxes in ABC Preview. */
    void editAuthoredTrackRouting() {
        if (abcSong == null) return;
        if (abcRollEditor != null && abcRollEditor.isShowing())
            abcRollEditor.stageChanges();
        java.util.Map<Integer,com.digero.maestro.noteedit.NoteEditorDelta.Part> deltas =
            abcSong.getNoteEditorDeltas();
        java.util.List<Integer> sources = new java.util.ArrayList<>();
        for (var entry : deltas.entrySet())
            if (!entry.getValue().added().isEmpty()) sources.add(entry.getKey());
        java.util.Collections.sort(sources);
        if (sources.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                "Create some notes in the Piano Roll first. Each part's authored notes form one track.",
                "Assign authored tracks",JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        java.util.Map<Integer,java.util.Map<Integer,JCheckBox>> boxes = new java.util.LinkedHashMap<>();
        JPanel rows = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
        gc.anchor=java.awt.GridBagConstraints.WEST;
        gc.insets=new Insets(3,6,3,6);
        gc.gridx=0;gc.gridy=0;
        rows.add(new JLabel("Piano Roll source track"),gc);
        int x=1;
        for (AbcPart part:abcSong.getParts()) {
            gc.gridx=x++;
            rows.add(new JLabel(part.getPartNumber()+": "+part.getTitle()),gc);
        }
        for (int source : sources) {
            gc.gridx=0;gc.gridy++;
            AbcPart origin=null;
            for (AbcPart part:abcSong.getParts()) if (part.getPartNumber()==source)origin=part;
            rows.add(new JLabel(source+": "+(origin==null?"(removed part)":origin.getTitle())+
                " ("+deltas.get(source).added().size()+" notes)"),gc);
            java.util.Map<Integer,JCheckBox> destinations=new java.util.LinkedHashMap<>();
            x=1;
            var assigned=com.digero.maestro.noteedit.AuthoredTrackRouting.targets(
                source,abcSong.getAuthoredTrackRouting());
            for (AbcPart part:abcSong.getParts()) {
                JCheckBox checkbox=new JCheckBox();
                checkbox.setSelected(assigned.contains(part.getPartNumber()));
                checkbox.setToolTipText("Send authored track "+source+" to part "+part.getTitle());
                destinations.put(part.getPartNumber(),checkbox);
                gc.gridx=x++;
                rows.add(checkbox,gc);
            }
            boxes.put(source,destinations);
        }
        JTextArea info=new JTextArea("Notes stay in their own Piano Roll track. " +
            "Each checked destination gets a separate preview track. " +
            "Imported and authored checkboxes are independent and can both play at once. " +
            "Unchecking every destination silences only the authored copy. " +
            "Existing MIDI source tracks are not changed.");
        info.setEditable(false);info.setLineWrap(true);info.setWrapStyleWord(true);
        info.setOpaque(false);info.setColumns(64);info.setRows(3);
        JPanel dialog=new JPanel(new BorderLayout(5,8));
        dialog.add(info,BorderLayout.NORTH);
        JScrollPane scroll=new JScrollPane(rows);
        scroll.setPreferredSize(new Dimension(Math.min(900,rows.getPreferredSize().width+35),
            Math.min(350,rows.getPreferredSize().height+35)));
        dialog.add(scroll,BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this,dialog,"Assign Authored Tracks to Song Parts",
            JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        java.util.Map<Integer,java.util.Set<Integer>> routing = new java.util.LinkedHashMap<>(
            abcSong.getAuthoredTrackRouting());
        for (var entry:boxes.entrySet()) {
            java.util.Set<Integer> destinations=new java.util.LinkedHashSet<>();
            for (var choice:entry.getValue().entrySet())
                if (choice.getValue().isSelected()) destinations.add(choice.getKey());
            if (destinations.equals(java.util.Set.of(entry.getKey()))) routing.remove(entry.getKey());
            else routing.put(entry.getKey(),destinations);
        }
        abcSong.setAuthoredTrackRouting(routing);
        setAbcSongModified(true);
        refreshPreviewSequence(false);
    }

    private void attachShortcut(JMenu menu, String id, String label, Runnable operation) {
        JMenuItem item = menu.add(new JMenuItem(label));
        item.addActionListener(e -> operation.run());
        keyboardShortcuts.attachMenu(id, item);
    }

    public boolean isShowingAllNoteVelocities() {
        return arrangementView.isShowingAllNoteVelocities();
    }

    private void deleteRollSelection() {
        var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
        if (rollActive && editor != null) editor.deleteFromShortcut();
    }
    private void seekSongStart() {
        SequencerWrapper transport = abcPreviewMode ? abcSequencer : sequencer;
        if (!transport.isLoaded()) return;
        transport.setTickPosition(abcPreviewMode ? abcPreviewStartTick : 0L);
        if (rollActive) {
            var editor = rollSourceAbc ? abcRollEditor : midiRollEditor;
            if (editor != null) editor.revealSongStart();
        }
        scheduleUiRefresh();
    }
    /** Move the active playhead while running, paused or stopped. */
    private void seekBar(int offsetBars) {
        if (abcSong == null) return;
        SequencerWrapper transport = abcPreviewMode ? abcSequencer : sequencer;
        javax.sound.midi.Sequence sequence = transport.getSequence();
        if (sequence == null || !transport.isLoaded()) return;
        long barTicks;
        if (abcPreviewMode) {
            var meter = abcSong.getTimeSignature();
            barTicks = Math.max(1L, Math.round(4.0 * sequence.getResolution()
                    * meter.numerator / meter.denominator));
        } else {
            barTicks = Math.max(1L, abcSong.getSequenceInfo().getDataCache().getBarLengthTicks());
        }
        long origin = abcPreviewMode ? abcPreviewStartTick : 0L;
        long tick = BarNavigation.barStart(transport.getTickPosition(), origin,
                barTicks, offsetBars, transport.getTickLength());
        transport.setTickPosition(tick);
        scheduleUiRefresh();
    }

    private void stopFromShortcut() {
        SequencerWrapper transport = abcPreviewMode ? abcSequencer : sequencer;
        if (!transport.isLoaded()) return;
        transport.stop();
        transport.reset(false);
        scheduleUiRefresh();
    }

    /** Select the first enabled source track in the selected Song Part. */
    private void toggleSectionEditor() {
        if (SectionEditor.isOpen()) { SectionEditor.close(); return; }
        AbcPart part = songPartsListPanel.getSelectedPart();
        if (part == null) return;
        for (int trackNumber : new java.util.TreeSet<>(arrangementView.trackPanels.keySet())) {
            TrackPanel panel = arrangementView.trackPanels.get(trackNumber);
            if (panel != null && part.isTrackEnabled(trackNumber)) {
                panel.openSectionEditor();
                return;
            }
        }
        for (int trackNumber : new java.util.TreeSet<>(arrangementView.trackPanels.keySet())) {
            TrackPanel panel = arrangementView.trackPanels.get(trackNumber);
            if (panel != null) { panel.openSectionEditor(); return; }
        }
    }

	private void initTheme() {
		themeEditorDialog = new JDialog(this, "Color Editor", false); // false = non-modal

		ColorSelector selector = new ColorSelector(arrangementView);

		themeEditorDialog.setContentPane(selector);
		//themeEditorDialog.setSize(550, 700);
		themeEditorDialog.setMinimumSize(new Dimension(450, 400));

		themeEditorDialog.pack();
		themeEditorDialog.setLocationRelativeTo(this);
	}

	private void showThemeEditor() {
		if (!themeEditorDialog.isVisible()) {
			themeEditorDialog.setVisible(true);
		} else {
			themeEditorDialog.toFront();
		}
	}

	public void themeUiEnabled(boolean on) {
		if (themeEditorDialog != null) {
			Component glassPane = themeEditorDialog.getGlassPane();
			if (!on) {
				themeEditorDialog.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
				glassPane.addMouseListener(blocker);
				glassPane.setVisible(true);
			} else {
				glassPane.setVisible(false);
				glassPane.removeMouseListener(blocker);
				themeEditorDialog.setCursor(Cursor.getDefaultCursor());
			}
		}
	}
	
	private void updateOpenRecentMenu() {
		openRecentMenu.removeAll();
		
		LinkedList<File> recentList = recentlyOpenedList.getList();
		
		if (recentList.isEmpty()) {
			openRecentMenu.setEnabled(false);
			return;
		}
		
		openRecentMenu.setEnabled(true);
		
		for (File file : recentList) {
			JMenuItem item = openRecentMenu.add(new JMenuItem(file.getName()));
			item.setToolTipText(file.getAbsolutePath());
			item.addActionListener(e -> {
				JMenuItem src = (JMenuItem)e.getSource();
				openFile(new File(src.getToolTipText()));
			});
		}
			
		openRecentMenu.addSeparator();
		
		JMenuItem clearMenuItem = openRecentMenu.add(new JMenuItem(UIText.get("maestro.menu.clear.list")));
		clearMenuItem.addActionListener(e -> {
			recentlyOpenedList.clearList();
			updateOpenRecentMenu();
		});
	}

	private int currentSettingsDialogTab = 0;

	private void doSettingsDialog() {
		doSettingsDialog(currentSettingsDialogTab);
	}
	
	private void doSettingsDialog(int tab) {
		boolean showSettingsAgain = false;
		int x = -1;
		int y = -1;
		do {
			showSettingsAgain = false;
			SettingsDialog dialog = new SettingsDialog(ProjectFrame.this, prefs, partAutoNumberer, partNameTemplate,
					exportFilenameTemplate, saveSettings.getCopy(), miscSettings.getCopy(),
					instrNameSettings.getCopy(), keyboardShortcuts);
			if (x > 0 && y > 0) {
				dialog.setLocation(x, y);
			}
			dialog.setActiveTab(tab);
			dialog.setVisible(true, abcSong);
			if (dialog.isSuccess()) {
				if (dialog.isNumbererSettingsChanged()) {
					partAutoNumberer.setSettings(dialog.getNumbererSettings());
					partAutoNumberer.renumberAllParts(abcSong.getParts());
				}
				partNameTemplate.setSettings(dialog.getNameTemplateSettings());
				arrangementView.settingsChanged();

				exportFilenameTemplate.setSettings(dialog.getExportFilenameTemplateSettings());

				instrNameSettings.copyFrom(dialog.getInstrNameSettings());
				instrNameSettings.saveToPrefs();

				saveSettings.copyFrom(dialog.getSaveAndExportSettings());
				saveSettings.saveToPrefs();

				miscSettings.copyFrom(dialog.getMiscSettings());
				miscSettings.saveToPrefs();
                keyboardShortcuts.setBindings(dialog.getShortcutBindings());
                keyboardShortcuts.setNoteClickShortcut(dialog.getNoteClickShortcut());
                updateRollNoteClickShortcuts();
                onSaveAndExportSettingsChanged();
			} else if (dialog.isSettingPageReset()) {
				tab = dialog.getResetPageIndex();
				switch (tab) {
				case 0: // part auto numberer
					partAutoNumberer.restoreDefaultSettings();
					partAutoNumberer.renumberAllParts(abcSong.getParts());
					break;
				case 1: // part naming
					partNameTemplate.restoreDefaultSettings();
					arrangementView.settingsChanged();
					break;
				case 2: // file naming
					exportFilenameTemplate.restoreDefaultSettings();
					break;
				case 3: // instr naming
					instrNameSettings.restoreDefaults();
					break;
				case 4: // save and export
					saveSettings.restoreDefaults();
					break;
				case 5: // misc
					miscSettings.restoreDefaults();
					if (sequencer != null) sequencer.reset(true);// To repopulate the devices
					break;
                case 6: // keyboard shortcuts
                    keyboardShortcuts.setBindings(ShortcutManager.defaults());
                    keyboardShortcuts.setNoteClickShortcut(ShortcutManager.defaultNoteClickShortcut());
                    updateRollNoteClickShortcuts();
                    break;
				}
				showSettingsAgain = true;
				x = dialog.getLocation().x;
				y = dialog.getLocation().y;
				onSaveAndExportSettingsChanged();
			}
			currentSettingsDialogTab = dialog.getActiveTab();
			dialog.dispose();
		} while (showSettingsAgain);
	}

	private void onSaveAndExportSettingsChanged() {
		if (saveSettings.showExportFileChooser) {
			exportAsMenuItem.setVisible(false);
			exportMenuItem.setText(UIText.get("maestro.menu.export.abc.as"));
		} else {
			exportAsMenuItem.setVisible(true);
			exportMenuItem.setText(UIText.get("maestro.menu.export.abc"));
		}
		
		songExportSettingsPanel.updateExportButton(shouldExportAbcAs());

        boolean needRefresh = false;

		if (abcSong != null) {
            if (abcSong.isSkipSilenceAtStart() != saveSettings.skipSilenceAtStart
                    || abcSong.isDeleteMinimalNotes() != saveSettings.deleteMinimalNotes
                    || abcSong.isUseRestsInChords() != saveSettings.useRestsInChords
            || miscSettings.dissModified) {
                // we do it here instead of in the song listener,
                // so we don't get nested calls to refresh.
                needRefresh = true;
                miscSettings.dissModified = false;
            }
			abcSong.setSkipSilenceAtStart(saveSettings.skipSilenceAtStart);
			abcSong.setDeleteMinimalNotes(saveSettings.deleteMinimalNotes);
            abcSong.setReducedFilesize(saveSettings.reducedFilesize);
            abcSong.setUseRestsInChords(saveSettings.useRestsInChords);
		}

		// if (abcSong != null)
		// abcSong.setShowPruned(saveSettings.showPruned);

		arrangementView.setPolyphony(miscSettings.showMaxPolyphony);
        arrangementView.setDissonanceEnabled(miscSettings.dissEnabled);
		arrangementView.setPoeticalLyricsAdvancement(saveSettings.countUpLyrics);
		arrangementView.setPoeticalLyricsTimestampEveryLine(saveSettings.lyricsTimestampEveryLine);
		if (abcSong != null) {
			abcSong.setBadger(miscSettings.showBadger);
		}

		String wantedDevice = NoteFilterSequencerWrapper.prefs.get(NoteFilterSequencerWrapper.prefMIDISelect, null);
		if ((NoteFilterSequencerWrapper.deviceInUse != null && !NoteFilterSequencerWrapper.deviceInUse.equals(wantedDevice)) || (NoteFilterSequencerWrapper.deviceInUse == null && wantedDevice != null)) {
			long tick = sequencer.getTickPosition();
			boolean running = sequencer.isRunning();
			sequencer.reset(true);
			sequencer.setTickPosition(tick);
			if (running) sequencer.setRunning(true); 
		}
		scheduleUiRefresh();
        if (needRefresh) refreshPreviewSequence(false);
	}

    @Deprecated
	public void onVolumeChanged() {
		volumeSlider.setValue(getVolume());
		volumeSlider.repaint();
	}
	
	private void setVolume(int volume) {
		if (volumeTransceiver != null)
			volumeTransceiver.setVolume(volume);
		if (abcVolumeTransceiver != null)
			abcVolumeTransceiver.setVolume(volume);
		prefs.putInt("volumizer", volume);
	}
	
	public int getVolume() {
		if (volumeTransceiver != null)
			return volumeTransceiver.getVolume();
		if (abcVolumeTransceiver != null)
			return abcVolumeTransceiver.getVolume();
		return defaultVolume;
	}
	
	public void setPan(int pan) {
		if (pan != prefs.getInt("stereoPan", defaultStereo)) {
			prefs.putInt("stereoPan", pan);
			saveSettings.saveToPrefs();
            updateStereo();
		}
	}
	
	public int getPan() {
		return prefs.getInt("stereoPan", defaultStereo);
	}

    /**
     * Change stereo rendition
     *
     */
    private void updateStereo() {
        if (abcSequencer != null && abcSequencer.getSequence() != null && abcSong != null) {
            int panModifier = prefs.getInt("stereoPan", defaultStereo);
            Sequence seq = abcSequencer.getSequence();
            Track[] tracks = seq.getTracks();
            PanGenerator panner = new PanGenerator();
            // explicit copy it, just to be 100% sure it's a shallow copy:
			List<AbcPart> panSortedParts = new ArrayList<>(abcSong.getParts().size());
			panSortedParts.addAll(abcSong.getParts());
            abcSong.allPans = new ArrayList<>();
            panner.sortParts(panSortedParts, abcSong.allPans);
            for (AbcPart part : panSortedParts) {
                MidiEvent prevEvent = part.getPanEvent();
                if (prevEvent == null) continue;
                if (part.getPreviewSequenceTrackNumber() > tracks.length - 1) {
                    log.warning("updateStereo: sequence vs. part preview track-number mismatch. tracks="+tracks.length+" part="+part.getPreviewSequenceTrackNumber());
                    break;
                }
                Track track = tracks[part.getPreviewSequenceTrackNumber()];
                MidiEvent newPanEvent = MidiUtils.replacePanningEvent(track, part.getInstrument(), part.getTitle(), prevEvent, panModifier, part.getUserPan(), panner, part.getPartNumber());
                part.setPanEvent(newPanEvent);
                abcSequencer.injectPanEvent(newPanEvent);
            }
            // Manually authored routes have their own MIDI tracks/channels;
            // give them the same destination Song Part pan as its generated
            // track instead of leaving the extra tracks stuck at center.
            for (AbcPart part : panSortedParts) {
                for (var authored : part.getAuthoredPreviewTracks()) {
                    if (authored.trackNumber < 0 || authored.trackNumber >= tracks.length) continue;
                    Track track=tracks[authored.trackNumber];
                    MidiEvent oldPan=null;
                    for (int i=0;i<track.size();i++) {
                        MidiEvent event=track.get(i);
                        if (event.getMessage() instanceof ShortMessage msg &&
                            msg.getCommand()==ShortMessage.CONTROL_CHANGE && msg.getData1()==10) {
                            oldPan=event; break;
                        }
                    }
                    if (oldPan!=null) {
                        MidiEvent updated=MidiUtils.replacePanningEvent(track,part.getInstrument(),
                            part.getTitle(),oldPan,panModifier,part.getUserPan(),panner,
                            part.getPartNumber());
                        abcSequencer.injectPanEvent(updated);
                    }
                }
            }
        }
    }

	private class MainSequencerListener implements Listener<SequencerEvent> {
		@Override
		public void onEvent(SequencerEvent evt) {

			SequencerProperty property = evt.getProperty();

			// POSITION and DRAG_POSITION fire on every playback timer tick. Nothing in
			// refreshUiState() depends on playback position (the moving cursor and the
			// bar/time readout have their own listeners), so scheduling a full UI refresh
			// here rebuilds borders/labels and repaints panels every tick. Only schedule
			// the refresh for structural events.
			if (property != SequencerProperty.POSITION
					&& property != SequencerProperty.DRAG_POSITION) {
				scheduleUiRefresh();
			}
			if (property == SequencerProperty.IS_RUNNING) {
				if (sequencer.isRunning()) {
					abcSequencer.stop();
				}
			} else if (!echoingPosition) {
				try {
					echoingPosition = true;
					if (property == SequencerProperty.POSITION) {
						if (abcSequencer.getTickLength() < abcPreviewStartTick) {
							// I don't fully understand how this can happen, bug report here:
							// https://discord.com/channels/1127545258729803797/1132590018985201664/1465902468419551324
							log.severe("MainSequencerListener: tick-length mismatch.");
							abcPreviewStartTick = 0L;
						}
						abcSequencer.setTickPosition(Util.clamp(sequencer.getTickPosition(),
								Math.min(abcPreviewStartTick,abcSequencer.getTickLength()),
								abcSequencer.getTickLength()));
					} else if (property == SequencerProperty.DRAG_POSITION) {
						if (abcSequencer.getTickLength() < abcPreviewStartTick) {
							log.severe("MainSequencerListener: tick-length mismatch.");
							abcPreviewStartTick = 0L;
						}
						abcSequencer.setDragTick(
								Util.clamp(sequencer.getDragTick(), Math.min(abcPreviewStartTick,abcSequencer.getTickLength()), abcSequencer.getTickLength()));
					} else if (property == SequencerProperty.IS_DRAGGING) {
						abcSequencer.setDragging(sequencer.isDragging());
					}
				} finally {
					echoingPosition = false;
				}
			}
		}
	}

	private class AbcSequencerListener implements Listener<SequencerEvent> {
		@Override
		public void onEvent(SequencerEvent evt) {
			SequencerProperty property = evt.getProperty();

			// See MainSequencerListener: skip the full UI refresh for the per-tick
			// POSITION/DRAG_POSITION events; only structural events need it.
			if (property != SequencerProperty.POSITION
					&& property != SequencerProperty.DRAG_POSITION) {
				scheduleUiRefresh();
			}

			if (property == SequencerProperty.IS_RUNNING) {
				if (abcSequencer.isRunning()) {
					sequencer.stop();
				}
			} else if (!echoingPosition) {
				try {
					echoingPosition = true;
					if (property == SequencerProperty.POSITION) {
						sequencer.setTickPosition(
								Util.clamp(abcSequencer.getTickPosition(), 0, sequencer.getTickLength()));
					} else if (property == SequencerProperty.DRAG_POSITION) {
						sequencer.setDragTick(Util.clamp(abcSequencer.getDragTick(), 0, sequencer.getTickLength()));
					} else if (property == SequencerProperty.IS_DRAGGING) {
						sequencer.setDragging(abcSequencer.isDragging());
					}
				} finally {
					echoingPosition = false;
				}
			}
		}
	}

	/**
	 * Flag to indicate whether a title update is pending. This prevents multiple title updates from being scheduled simultaneously.
	 */
	private boolean updateTitlePending = false;

    /**
     * Update title of maestro window
     */
	private void updateTitle() {
		if (!updateTitlePending) {
			updateTitlePending = true;
			SwingUtilities.invokeLater(() -> {
				updateTitlePending = false;
				String title = MaestroMain.APP_NAME + " " + MaestroMain.APP_VERSION;
				if (abcSong != null) {
					if (abcSong.getProjectFile() != null) {
						title += " - " + abcSong.getProjectFile().getName();
						if (abcSong.getSourceFile() != null)
							title += " [" + abcSong.getSourceFile().getName() + "]";
					} else if (abcSong.getSourceFile() != null) {
						title += " - " + abcSong.getSourceFile().getName();
					}

					if (isAbcSongModified())
						title += "*";
				}
				setTitle(title);
			});
		}
	}

	/**
	 * Tracks whether a deferred UI-state update is already queued.
	 */
	private AtomicBoolean uiRefreshPending = new AtomicBoolean();

	/**
	 * Schedules a UI-state update to run on the Event Dispatch Thread.
	 * If an update is already pending, this method does nothing.
	 * 
	 * TODO: This method should be enhanced to allow granular updates, e.g., only updating certain parts of the UI state instead of the entire state.
	 * This could further be enhanced by also coalescing multiple granular updates into a single update.
	 */
	void scheduleUiRefresh() {
    	if (uiRefreshPending.compareAndSet(false, true)) {
        	SwingUtilities.invokeLater(uiRefreshTask);
    	}
	}

	/**
	 * Performs the queued UI refresh on the Event Dispatch Thread.
	 *
	 * The pending flag is cleared before execution so that a state change caused
	 * during the refresh can schedule one follow-up refresh.
	 */
	private final Runnable uiRefreshTask = () -> {
		uiRefreshPending.set(false);
		refreshUiStateAndFeed();
	};

	/**
	 * Performs a UI-state update and refreshes the error feed afterward.
	 */
	private void refreshUiStateAndFeed() {
		try {
			refreshUiState();
		} finally {
			showFeed();
		}
	}

	/**
	 * Refreshes the UI state based on the current application context,
	 * including the current song, sequencer states, and user interface settings.
	 */
	private void refreshUiState() {
		AbcSong currentSong = abcSong;
		SequenceInfo sequenceInfo = currentSong != null ? currentSong.getSequenceInfo() : null;
		boolean midiLoaded = sequencer.isLoaded();
		boolean hasAbcNotes = hasEnabledAbcNotes(currentSong);
		boolean partSelected = songPartsListPanel.getSelectedIndex() != -1;
		boolean hasProjectFile = currentSong != null && currentSong.getProjectFile() != null;

        // Opening an MSX composition with authored notes must not land in the
        // empty Original scaffold. Select the playable ABC Preview instead.
        if (currentSong != null && currentSong.isCompositionMode() &&
            hasAbcNotes && !rollActive && !abcPreviewMode) {
            updatePreviewMode(true, false);
        }

		updatePlaybackIcons();

		/*ensureValidPreviewMode() is not purely a UI update:
		it can stop and clear the ABC sequencer. Since this refresh
		is now deferred and coalesced, should that sequencer cleanup
		be triggered explicitly when the last enabled ABC notes
		disappear instead of happening as a side effect of refreshing
		the UI? */
		ensureValidPreviewMode(hasAbcNotes);

		updatePlaybackControls(midiLoaded, hasAbcNotes);
		updateFileActions(currentSong, hasAbcNotes);
		updateSourceControls(midiLoaded, hasProjectFile);
		updateSongPartsControls(currentSong, midiLoaded, partSelected);
		updateTuneControls(currentSong, midiLoaded);
		updateTimingAndDynamicsControls(currentSong, sequenceInfo, midiLoaded);
		updateSongPartsLayoutAndTitle(currentSong);
	}

	/**
	 * Checks if the given AbcSong has any enabled ABC notes in its parts.	
	 * @param song the AbcSong to check
	 * @return true if the song has any enabled ABC notes, false otherwise
	 */
	private boolean hasEnabledAbcNotes(AbcSong song) {
		if (song == null) {
			return false;
		}

        // Authored notes in a new composition (or in a previously empty part)
        // are real preview material even without an enabled imported track.
        // The normal exporter still decides whether imported empty tracks are
        // omitted; this check only controls preview availability.
        return song.hasPreviewableParts();
	}

	/**
	 * Updates the playback icons (play/pause) based on the current sequencer state and preview mode.
	 */
	private void updatePlaybackIcons() {
		SequencerWrapper curSequencer = abcPreviewMode ? abcSequencer : sequencer;
		Icon curPlayIcon = abcPreviewMode ? abcPlayIcon : playIcon;
		Icon curPlayIconDisabled = abcPreviewMode ? abcPlayIconDisabled : playIconDisabled;
		Icon curPauseIcon = abcPreviewMode ? abcPauseIcon : pauseIcon;
		Icon curPauseIconDisabled = abcPreviewMode ? abcPauseIconDisabled : pauseIconDisabled;
		playButton.setIcon(curSequencer.isRunning() ? curPauseIcon : curPlayIcon);
		playButton.setDisabledIcon(curSequencer.isRunning() ? curPauseIconDisabled : curPlayIconDisabled);
	}

	/**
	 * Ensures that the preview mode is valid based on the presence of ABC notes.
	 * If there are no ABC notes, it switches to MIDI mode and clears the ABC sequencer.
	 * @param hasAbcNotes true if there are enabled ABC notes, false otherwise
	 */
	private void ensureValidPreviewMode(boolean hasAbcNotes) {
		if (!hasAbcNotes) {
            // New compositions can have an empty, editable piano roll before
            // the first note exists. A queued UI refresh must not deselect that
            // mode while its SwingWorker is preparing the editor card.
            if (ProjectUiState.shouldSelectOriginal(hasAbcNotes, rollActive))
			    midiModeRadioButton.setSelected(true);
			abcSequencer.setRunning(false);
			//updatePreviewMode(false);
			abcSequencer.clearSequence();
		}
	}

	/**
	 * Updates the playback controls (buttons, sliders) based on the current state of the sequencers and UI.
	 * @param midiLoaded true if the MIDI sequencer is loaded, false otherwise
	 * @param hasAbcNotes true if there are enabled ABC notes, false otherwise
	 */
	private void updatePlaybackControls(boolean midiLoaded, boolean hasAbcNotes) {
		volumeSlider.setEnabled(uiEnabled);
		stereoSlider.setEnabled(uiEnabled);

		playButton.setEnabled(midiLoaded && uiEnabled);
		// New compositions have a rest-only scaffold, not a useful Original MIDI track.
        // Keep the authoring and ABC Preview views authoritative until a source
        // MIDI-backed composition model is implemented.
        midiModeRadioButton.setEnabled((currentCompositionHasSource() && (midiLoaded || hasAbcNotes)) && uiEnabled);
        midiModeRadioButton.setToolTipText(currentCompositionHasSource() ? null :
            "New compositions have no imported Original track. Use ABC Preview or Piano Roll.");
		abcModeRadioButton.setEnabled(hasAbcNotes && uiEnabled);
		if (!hasAbcNotes && abcModeRadioButton.isSelected()) {
			log.severe("AbcPreview selected, but no abc preview available! And no, do not fix it here.");
		}
        // Stop reflects the *active* transport only. The other sequencer
        // follows its position for mode switching and may legitimately retain
        // a nonzero tick after ABC Preview has stopped.
        SequencerWrapper current = abcPreviewMode ? abcSequencer : sequencer;
        long firstTick = abcPreviewMode ? abcPreviewStartTick : 0L;
        stopButton.setEnabled(ProjectUiState.stopEnabled(uiEnabled,
                current.isLoaded(), current.isRunning(), current.getTickPosition(), firstTick));
	}

	/**
	 * Updates the file-related actions (export, save) based on the current song and UI state.
	 * @param currentSong the current AbcSong
	 * @param hasAbcNotes true if there are enabled ABC notes, false otherwise
	 */
    private boolean currentCompositionHasSource() {
        return abcSong == null || !abcSong.isCompositionMode();
    }

	private void updateFileActions(AbcSong currentSong, boolean hasAbcNotes) {
		songExportSettingsPanel.setExportButtonEnabled(hasAbcNotes);// so that it keep focus, we keep it enabled during export.
		exportMenuItem.setEnabled(hasAbcNotes && uiEnabled);
		exportAsMenuItem.setEnabled(hasAbcNotes && uiEnabled);
		saveMenuItem.setEnabled(currentSong != null && uiEnabled);
		saveAsMenuItem.setEnabled(currentSong != null && uiEnabled);
		saveExpandedMidiMenuItem.setEnabled(currentSong != null && uiEnabled);
		exportAudioMenu.setEnabled(currentSong != null && uiEnabled);
		exportMp3MenuItem.setEnabled(currentSong != null && uiEnabled);
		exportWavMenuItem.setEnabled(currentSong != null && uiEnabled);
	}

	/**
	 * Updates the source-related controls (choose/reload MIDI file, open recent) based on the current song and UI state.
	 * @param midiLoaded true if the MIDI sequencer is loaded, false otherwise
	 * @param hasProjectFile true if the current song has an associated project file, false otherwise
	 */
	private void updateSourceControls(boolean midiLoaded, boolean hasProjectFile) {
		String errStr = UIText.get("maestro.html.p.style.color.red.must.save.as.an.msx.project.first.p.html");
		chooseMidiFileMenuItem.setEnabled(hasProjectFile && uiEnabled && sourceChangeEnabled);
		chooseMidiFileMenuItem.setToolTipText(hasProjectFile ? "" : errStr);
		reloadMidiFileMenuItem.setEnabled(hasProjectFile && uiEnabled && sourceChangeEnabled);
		reloadMidiFileMenuItem.setToolTipText(hasProjectFile ? "" : errStr);

		openRecentMenu.setEnabled(sourceChangeEnabled);
		openItem.setEnabled(sourceChangeEnabled);

		closeProject.setEnabled(midiLoaded && uiEnabled && sourceChangeEnabled);
	}

	/**
	 * Updates the song parts controls (editing, buttons) based on the current song, MIDI state, and part selection.
	 * @param currentSong the current AbcSong
	 * @param midiLoaded true if the MIDI sequencer is loaded, false otherwise
	 * @param partSelected true if a part is currently selected, false otherwise
	 */
	private void updateSongPartsControls(AbcSong currentSong, boolean midiLoaded, boolean partSelected) {
		songInfoPanel.setEditingEnabled(midiLoaded && uiEnabled);
		songInfoPanel.setGenreAndMoodVisible(miscSettings.showBadger);

		songPartsPanel.setButtonsEnabled(currentSong != null && uiEnabled, partSelected && uiEnabled, midiLoaded && uiEnabled,
				partSelected && uiEnabled);

		Color c = UIManager.getColor("Button.foreground");
		songPartsPanel.setOpenEditorButtonForeground(
				currentSong != null && currentSong.isPartEdited() ? ColorTable.CONTROLS_EDITED.get() : c);
	}

	/**
	 * Updates the tune-related controls (transpose, tempo, editor) based on the current song and MIDI state.
	 * @param currentSong the current AbcSong
	 * @param midiLoaded true if the MIDI sequencer is loaded, false otherwise
	 */
	private void updateTuneControls(AbcSong currentSong, boolean midiLoaded) {
		songExportSettingsPanel.setTransposeSpinnerEnabled(midiLoaded && uiEnabled);
		songExportSettingsPanel.setTempoSpinnerEnabled(midiLoaded && uiEnabled);
		tuneEditorButton.setEnabled(midiLoaded && uiEnabled);
		hideEditsCheckbox.setEnabled(midiLoaded && uiEnabled);
		if (!midiLoaded)
			hideEditsCheckbox.setSelected(false);

		if (midiLoaded
				&& currentSong != null
				&& (currentSong.tuneBars != null
						|| currentSong.getFirstBar() != null
						|| currentSong.getLastBar() != null)) {
			tuneEditorButton.setForeground(ColorTable.CONTROLS_EDITED.get());
		} else {
			Color c = UIManager.getColor("Button.foreground");
			tuneEditorButton.setForeground(c);
		}
		songExportSettingsPanel.setResetTempoButtonEnabledAndVisible(midiLoaded && currentSong != null && currentSong.getTempoFactor() != 1.0f && uiEnabled);
	}

	/**
	 * Updates the timing and dynamics controls (key signature, time signature, dynamics) based on the current song,
	 * sequence information, and MIDI state.
	 * @param currentSong the current AbcSong
	 * @param sequenceInfo the SequenceInfo associated with the current song
	 * @param midiLoaded true if the MIDI sequencer is loaded, false otherwise
	 */
	private void updateTimingAndDynamicsControls(AbcSong currentSong, SequenceInfo sequenceInfo, boolean midiLoaded) {

		boolean midiLoadedAndUiEnabled = midiLoaded && uiEnabled;

		songExportSettingsPanel.setKeySignatureFieldEnabled(midiLoadedAndUiEnabled);
		songExportSettingsPanel.setTimeSignatureFieldEnabled(midiLoadedAndUiEnabled);
		songExportSettingsPanel.setTimingModeComboEnabled(midiLoadedAndUiEnabled);
		songExportSettingsPanel.setDynamicChordModeComboEnabled(midiLoadedAndUiEnabled);
		songExportSettingsPanel.setCountOnlyTempoChangesFromFirstTrackCheckBoxEnabled(
			currentSong != null &&
			sequenceInfo != null &&
			sequenceInfo.getDataCache().isTempoInHigherTracks() &&
			uiEnabled); //  && currentSong.getProjectFile() != null

		sidepanelButton.setEnabled(midiLoadedAndUiEnabled);

		if (midiLoaded && currentSong != null && sequenceInfo != null) {
			midiModeRadioButton.setText(
					UIText.get(
							"maestro.original.0",
							sequenceInfo.standard + (sequenceInfo.hasPorts ? "+" : "")
					)
			);
		} else {
			midiModeRadioButton.setText(UIText.get("maestro.original"));
		}
	}

	/**
	 * Updates the layout and title of the song parts panel based on the current song.
	 * @param currentSong the current AbcSong
	 */
	private void updateSongPartsLayoutAndTitle(AbcSong currentSong) {
		// double[] LAYOUT_COLS_DYN = new double[] { partsList.getFixedCellWidth() + 32, FILL };
		double[] LAYOUT_COLS_DYN = new double[] { 300 + 32, FILL };

		//This call is attempt of fix for no delete button on MacOS part 2
		tableLayout.setColumn(LAYOUT_COLS_DYN);

		String partListTitle = UIText.get("maestro.song.parts");
		if (currentSong != null) {
			partListTitle = UIText.get(
					"maestro.0.count.1",
					partListTitle,
					currentSong.getActivePartCount()
			);
		}
		songPartsPanel.setBorder(BorderFactory.createTitledBorder(partListTitle));
	}

	private final Listener<AbcPartEvent> abcPartListener = e -> {
        //log.warning(this.getClass().getTypeName()+" AbcPartEvent: "+e.getProperty());

        if (e.getProperty() == AbcPartProperty.EXCLUSION) {
            if (arrangementView != null)
                arrangementView.refreshAuthoredPlaybackAppearance();
            if (histogram != null) {
                histogram.setDirty();
                compileStats();
            }
            // This is a runtime event only, so we return
            // so that the project doesn't get marked modified
            return;
        }

        if (e.getProperty() == AbcPartProperty.PREVIEW_TRACK_NUMBER) {
            // This is a runtime event only, so we return
            // so that the project doesn't get marked modified
            return;
        }

		if (e.getProperty() == AbcPartProperty.TRACK_ENABLED)
			scheduleUiRefresh();

		if (e.getProperty() == AbcPartProperty.TITLE && arrangementView != null)
			arrangementView.setNewTitle(e.getSource());

        if (e.getProperty() == AbcPartProperty.PART_NUMBER_MANUAL)
            partAutoNumberer.renumberAllParts(abcSong.getParts());

        if (e.getProperty() == AbcPartProperty.USER_PAN) {
            updateStereo();
        }

		songPartsListPanel.repaint();
		partEditor.repaint();

		setAbcSongModified(true);

		if (e.isAbcPreviewRelated()) {
            // must be immediate since song.parts can change in subsequent
            // part listeners and generate preview now runs on a different thread
            // update: since it now uses copy of abcsong, non-immediate is ok.
			refreshPreviewSequence(false);

			if (arrangementView != null) {
				arrangementView.repaint();
			}
		}
		
		songExportSettingsPanel.updateExportButton(shouldExportAbcAs());
	};

	private final Listener<AbcSongEvent> abcSongListener = e -> {
		if (abcSong == null || abcSong != e.getSource())
			return;

		int idx;
        boolean modified = true;

        //log.warning(this.getClass().getTypeName()+" AbcSongEvent: "+e.getProperty());

		switch (e.getProperty()) {
			case TITLE:
			case COMPOSER:
			case TRANSCRIBER:
			case GENRE:
			case MOOD:
    			songInfoPanel.setSongInfo(getCurrentSongInfo());
    			break;

			case TEMPO_FACTOR:
				if (songExportSettingsPanel.getTempo() != abcSong.getTempoBPM())
					setTempoWithoutEvent(abcSong.getTempoBPM());
				break;
			case TRANSPOSE:
				setTransposeWithoutEvent(abcSong.getTranspose());
                requestRollBaselineRefresh();
				break;
			case KEY_SIGNATURE:
				if (SHOW_KEY_FIELD) {
					if (!songExportSettingsPanel.getKeySignature().equals(abcSong.getKeySignature()))
						songExportSettingsPanel.setKeySignature(abcSong.getKeySignature());
				}
				break;
			case TIME_SIGNATURE:
				setTimeSignatureWithoutEvent(abcSong.getTimeSignature());
				break;
			case TIMINGS_MULTI:
				// one or more timing settings were changed in abc song
				// that only happens at load project or from Abc Auto Exporter, which wont have this class loaded.
				setTimingModeWithoutEvent(
					TimingMode.getInstance(
						abcSong.isOrganic(),
						abcSong.isOrganic2(),
						abcSong.isMixTiming(),
						abcSong.isTripletTiming(),
						abcSong.isPriorityActive(),
						abcSong.isUpgraded()
					)
				);

				scheduleUiRefresh();
				break;
			case CALC_DYNAMICS:
				setDynamicChordModeWithoutEvent(abcSong.dynamicsMethod);
				break;
			case PART_ADDED:
				e.getPart().addAbcListener(abcPartListener);

				idx = abcSong.getParts().indexOf(e.getPart());
				songPartsListPanel.selectPart(idx);
				songPartsListPanel.ensureIndexIsVisible(idx);
				songPartsListPanel.repaint();
				partEditor.repaint();
				scheduleUiRefresh();
				compileStats();
				break;
			case BADGER:
				AbcPart ap = songPartsListPanel.getSelectedPart();
				songPartsListPanel.updateParts();
				idx = abcSong.getParts().indexOf(ap);
				songPartsListPanel.selectPart(idx);
				songPartsListPanel.ensureIndexIsVisible(idx);
				songPartsListPanel.repaint();
				partEditor.updateParts();
				partEditor.repaint();
				scheduleUiRefresh();
				modified = false;
				break;
			case TUNE_EDIT:
				scheduleUiRefresh();
                // Apply rebases the persisted notes immediately. Do not let an
                // old editor document overwrite them on a quick second Apply
                // or Save before the asynchronous baseline refresh completes.
                refreshRollAfterTuneEdit();
				if (songPartsListPanel.getSelectedPart() != null) {
					// We do this to show the tempo panel if the tune editor has changed something
					arrangementView.setAbcPart(songPartsListPanel.getSelectedPart(), true);
				}
				if (abcPreviewMode)
					refreshPreviewSequence(false);
				break;

			case BEFORE_PART_REMOVED:
				e.getPart().removeAbcListener(abcPartListener);

				idx = abcSong.getParts().indexOf(e.getPart());
				if (idx > 0)
					songPartsListPanel.selectPart(idx - 1);
				else if (abcSong.getParts().size() > 1) {
					songPartsListPanel.selectPart(1);
				}

				if (abcSong.getParts().isEmpty()) {
					sequencer.stop();
					arrangementView.showInfoMessage(
						formatInfoMessage(
							UIText.get("maestro.add.a.part"),
							UIText.get("maestro.this.abc.song.has.no.parts.click.the.0.button.to.add.a.new.part",
							UIText.get("maestro.new.part")),
							getHTMLFontSizeNormal()
						)
					);
				}

				songPartsListPanel.repaint();
				scheduleUiRefresh();
				break;
			case AFTER_PART_REMOVED:
				refreshPreviewSequence(false);
				break;
			case PART_LIST_ORDER:
				songPartsListPanel.updateParts();//important to run before the below code
				songPartsListPanel.selectPart(abcSong.getParts().indexOf(arrangementView.getAbcPart()));
				// this is important, else after a deletion, the tracklist might be in the wrong state:
				if (songPartsListPanel.getSelectedPart() != null) {
					// might be null shortly after loading from midi
					arrangementView.setAbcPart(songPartsListPanel.getSelectedPart(), true);
				}
				refreshPreviewSequence(false);// autoPan depend on part order
				songPartsListPanel.repaint();
				partEditor.repaint();
				scheduleUiRefresh();
				break;

			case SKIP_SILENCE_AT_START:
				if (saveSettings.skipSilenceAtStart != abcSong.isSkipSilenceAtStart()) {
					/*
					 not sure that this is sane
					 skip is not a song property it's a setting
					 it's only in abcSong for convenience
					 I cannot think of a case where setting it on abcsong
					 should propagate to settings.
					 Same for DELETE_MINIMAL_NOTES.
					 So commented out.
					*/

					//saveSettings.skipSilenceAtStart = abcSong.isSkipSilenceAtStart();
					//saveSettings.saveToPrefs();
				}
				modified = false;
				break;
			case DELETE_MINIMAL_NOTES:
				if (saveSettings.deleteMinimalNotes != abcSong.isDeleteMinimalNotes()) {
					//saveSettings.deleteMinimalNotes = abcSong.isDeleteMinimalNotes();
					//saveSettings.saveToPrefs();
				}
				modified = false;
				break;
			case COUNT_IN:
				setAbcSongModified(true);
				refreshPreviewSequence(false);
				// apply preview will set the delay on wrapper, so that playhead gets delayed by countin.
				break;
			case EXPORT_FILE:
				// Don't care
				break;
			case SONG_CLOSING:
				modified = false;
				break;
			case HIDE_EDITS_UPDATE:
				// Don't care
				modified = false;
				break;
			case USER_NOTE:
				break;
			case USER_LYRICS:
				break;
		}
		songExportSettingsPanel.updateExportButton(shouldExportAbcAs());
		if (modified) setAbcSongModified(true);
	};

	private final ListDataListener partsListListener = new ListDataListener() {
		@Override
		public void intervalAdded(ListDataEvent e) {
			songPartsListPanel.updateParts();
			partEditor.updateParts();
			scheduleUiRefresh();
		}

		@Override
		public void intervalRemoved(ListDataEvent e) {
			songPartsListPanel.updateParts();
			partEditor.updateParts();
			scheduleUiRefresh();
		}

		@Override
		public void contentsChanged(ListDataEvent e) {
			songPartsListPanel.updateParts();
			partEditor.updateParts();
			scheduleUiRefresh();
		}
	};

	private void setAbcSongModified(boolean abcSongModified) {
		if (this.abcSongModified != abcSongModified) {
			this.abcSongModified = abcSongModified;
			updateTitle();
		}
	}

	public void setMIDIFileResolved() {
		midiResolved = true;
	}

	private boolean isAbcSongModified() {
		return abcSong != null && abcSongModified;
	}

    /**
     * Will not activate the changelistener to set abcSong
     */
    public void setTransposeWithoutEvent(int transpose) {
		assert SwingUtilities.isEventDispatchThread():"Called from non-swing thread. Listener boolean must be a volatile instead.";
        fireTransposeListeners = false;
        songExportSettingsPanel.setTranspose(transpose);
        fireTransposeListeners = true;
    }

    /**
     * Will not activate the changelistener to set abcSong
     */
    private void setTimeSignatureWithoutEvent(TimeSignature ts) {
		assert SwingUtilities.isEventDispatchThread():"Called from non-swing thread. Listener boolean must be a volatile instead.";
        fireMeterListeners = false;
        songExportSettingsPanel.setTimeSignature(ts);
        fireMeterListeners = true;
    }

    /**
     * Will not activate the changelistener to set abcSong
     */
    private void setTempoWithoutEvent(int tempoBPM) {
		assert SwingUtilities.isEventDispatchThread():"Called from non-swing thread. Listener boolean must be a volatile instead.";
        fireTempoListeners = false;
        songExportSettingsPanel.setTempo(tempoBPM);
        fireTempoListeners = true;
    }

    /**
     * Will not activate the changelistener to set abcSong
     */
    private void setDynamicChordModeWithoutEvent(Chord.CalcDynamics dyna) {
		assert SwingUtilities.isEventDispatchThread():"Called from non-swing thread. Listener boolean must be a volatile instead.";
        fireDynaListeners = false;
        songExportSettingsPanel.setDynamicChordMode(dyna);
        fireDynaListeners = true;
    }

	/**
	 * Will not activate the changelistener to set abcSong
	 * nor fire preview rebuild
	 */
	private void setTimingModeWithoutEvent(TimingMode mode) {
		assert SwingUtilities.isEventDispatchThread():"Called from non-swing thread. Listener boolean must be a volatile instead.";
		fireTimingListeners = false;
		songExportSettingsPanel.setTimingMode(mode);
		fireTimingListeners = true;
	}

	private enum CloseProjectMode {
    	NORMAL,
    	SHUTDOWN
	}

	/**
	 * closes the current project as part of the application shutdown process
	 * @return true if it was closed
	 */
	protected boolean closeProjectForShutdown() {
		return closeProject(CloseProjectMode.SHUTDOWN);
	}

	/**
	 * closes the current project as part of normal project closing,
	 * either to open another project or just to close the current project without opening another
	 * @return true if it was closed
	 */
	protected boolean closeProjectForNormal() {
		return closeProject(CloseProjectMode.NORMAL);
	}

	/**
	 * Close the current song, prompting to save if there are unsaved changes.
	 * Does not prompt to save if there is no current song, even if there are unsaved changes.
	 * 
	 * @return true if it was closed
	 */
	private boolean closeProject(CloseProjectMode mode) {
		sequencer.stop();
		abcSequencer.stop();

		// Commit an in-progress lyric edit while it still belongs to this song.
		// This fires USER_LYRICS -> setAbcSongModified(true), so the save prompt below
		// sees it. Afterwards no editor can survive into the next project.
		arrangementView.stopEditingLyrics();

		boolean promptSave = isAbcSongModified() && (saveSettings.promptSaveNewSong || abcSong.getProjectFile() != null);
		if (promptSave) {
			String message;
			if (abcSong.getProjectFile() == null)
				message = UIText.get("maestro.do.you.want.to.save.this.new.song");
			else
				message = UIText.get("maestro.do.you.want.to.save.changes.to.0", abcSong.getProjectFile().getName());

			int result = JOptionPane.showConfirmDialog(this, message, UIText.get("maestro.save.changes"), JOptionPane.YES_NO_CANCEL_OPTION,
					JOptionPane.QUESTION_MESSAGE, IconLoader.getImageIcon("msxfile_32.png"));
			if (result == JOptionPane.CANCEL_OPTION || result == JOptionPane.CLOSED_OPTION)
				return false;

			if (result == JOptionPane.YES_OPTION) {
				if (!save())
					return false;
			}
		}
		
		log.fine("Closing project");

		boolean skipSequencerReset = mode == CloseProjectMode.SHUTDOWN;
		SectionEditor.clearClipboard();
		TrackPanel.clearDrumClipboard();

		hideEditsCheckbox.setSelected(false);//best to have this before song is set to null

		if (abcSong != null) {
			abcSong.getParts().getListModel().removeListDataListener(partsListListener);
			abcSong.discard();
			abcSong = null;
		}

		arrangementView.setAbcPart(null, false);
		arrangementView.setTextnote("");
        arrangementView.setLyrics("");
		arrangementView.setLyricLines(null, false);
        arrangementView.setStats("");
		arrangementView.sidepanelVisible(false);
		arrangementView.unZoom();
		arrangementView.closeAbcSong();

		partEditor.dispose();

		songPartsListPanel.updateParts();
		partEditor.updateParts();

		allowOverwriteSaveFile = false;
		allowOverwriteExportFile = false;

		sequencer.clearSequence();
		abcSequencer.clearSequence();

		/*
		 * If we shut down we do not need to reset the sequencers as they get disposed in ProjectFrame.dispose()
		 * If we are just closing the project, we want to reset them so that they are fresh for the next project.
		 * Before the implementation of this if-Statement, SequencerWrapper.reset(boolean) caused a recreation of
		 * the sequencer only to have it immediately discarded in ProjectFrame.dispose()
		*/
		if (!skipSequencerReset)  {
			sequencer.reset(true);
			abcSequencer.reset(false);
		}

		abcSequencer.setTempoFactor(1.0f);
		abcPreviewStartTick = 0L;

		clearSongInfoPanel();

		setTransposeWithoutEvent(0);
		setTempoWithoutEvent(MidiConstants.DEFAULT_TEMPO_BPM);
		songExportSettingsPanel.setKeySignature(KeySignature.C_MAJOR);
		setTimeSignatureWithoutEvent(TimeSignature.FOUR_FOUR);
		setTimingModeWithoutEvent(TimingMode.getFromSettings(saveSettings.defaultTiming));
		setDynamicChordModeWithoutEvent(AbcSong.dynamicsMethodDefault);
		songExportSettingsPanel.setCountOnlyTempoChangesFromFirstTrackSelected(false);

		midiBarLabel.setBarNumberCache(null);
		abcBarLabel.setBarNumberCache(null);
		abcBarLabel.setInitialOffsetTick(abcPreviewStartTick);
		abcPositionLabel.setInitialOffsetTick(abcPreviewStartTick);

		updatePreviewMode(false, false);
		setAbcSongModified(false);
		scheduleUiRefresh();
		updateTitle();

		return true;
	}
	
	public void openFile(File file) {
        if (!uiEnabled || (audioExporter != null && audioExporter.isExporting())) {
            log.warning("Cannot open file. uiEnabled="+uiEnabled+" audioExporter.isExporting()="+(audioExporter != null && audioExporter.isExporting()));
            Toolkit.getDefaultToolkit().beep();
            return;
        }

		openFile(file, true);
	}
	
	private File filetemp = null;
	private File file = null;
	private boolean inCloseFile = false;// In progress of asking user if wanting to save
	private boolean inOpenFile = false;// In progress of either opening file or finding midi.

	public void openFile(File openfile, boolean updateLastOpenedList) {
		// begin system for preventing cascading dialogs
		// As long as the dialog for asking to close open project is there,
		// double-clicking in explorer in windows will change which song eventually gets open.
		// When dialog for finding midi is there, subsequent explorer double clicks are ignored
		// until after midi found or canceled.
		// Not ideal, but works.
		filetemp = openfile;
		if (inCloseFile || inOpenFile) {
			return;
		}
		inCloseFile = true;
		if (!closeProjectForNormal()) {
			inCloseFile = false;
			return;
		}
		inOpenFile = true;
		inCloseFile = false;
		file = filetemp;
		// end system for preventing cascading dialogs

		file = Util.resolveShortcut(file);
		allowOverwriteSaveFile = false;
		allowOverwriteExportFile = false;
		setAbcSongModified(false);

		boolean openSucceeded = false;

		log.info("Attempting to open "+file.getName());//dont reveal full path in log files
		try {
			abcSong = new AbcSong(file, partAutoNumberer, partNameTemplate, exportFilenameTemplate, instrNameSettings,
					openFileResolver, miscSettings, saveSettings);
			SequencerWrapper.onlyFirstTrackTempos = abcSong.isUsingOldTempos();
			abcSong.setBadger(miscSettings.showBadger);
			abcSong.addSongListener(abcSongListener);
			abcSong.addSongListener(songPartsListPanel.songListener);
			abcSong.addSongListener(partEditor.getSongListener());
			for (AbcPart part : abcSong.getParts()) {
				part.addAbcListener(abcPartListener);
				part.addAbcListener(songPartsListPanel.partListener);
				part.addAbcListener(partEditor.getPartListener());
			}

			updateSongInfoFromAbcSong();
			setDynamicChordModeWithoutEvent(abcSong.dynamicsMethod);

            arrangementView.sidepanelTab(UIText.get("maestro.notes"));

            String lyrics = abcSong.getLyrics();
            if (lyrics.isBlank()) {
                lyrics = UIText.get("maestro.contains.no.lyrics");
            } else if (!abcSong.isFromXmlFile()) {
                arrangementView.sidepanelVisible(true);
                arrangementView.sidepanelTab(UIText.get("maestro.lyrics"));
            } else {
				arrangementView.sidepanelTab(UIText.get("maestro.lyrics"));
			}
            arrangementView.setLyrics(lyrics);
			if (abcSong.getLyricLines() != null) arrangementView.setLyricLines(abcSong.getLyricLines(), true);
			else arrangementView.setLyricLines(abcSong.getSequenceInfo().getDataCache().getLyricLines(), false);

            if (abcSong.isFromXmlFile()) {
                String note = abcSong.getNote();
                if (note != null && !note.equals(lyrics)) {
                	// the check for note==lyrics is to clear userNote if it's an old project and
                	// lyrics were saved unchanged in the note.
                    arrangementView.setTextnote(note);
                    if (!note.isEmpty()) {
                        arrangementView.sidepanelTab(UIText.get("maestro.notes"));
                        arrangementView.sidepanelVisible(true);
                    }
                }
            }

			setTransposeWithoutEvent(abcSong.getTranspose());
			setTempoWithoutEvent(abcSong.getTempoBPM());
			songExportSettingsPanel.setKeySignature(abcSong.getKeySignature());
			setTimeSignatureWithoutEvent(abcSong.getTimeSignature());

			setTimingModeWithoutEvent(
				TimingMode.getInstance(
					abcSong.isOrganic(),
					abcSong.isOrganic2(),
					abcSong.isMixTiming(),
					abcSong.isTripletTiming(),
					abcSong.isPriorityActive(),
					abcSong.isUpgraded()
				)
			);

			songExportSettingsPanel.setCountOnlyTempoChangesFromFirstTrackSelected(abcSong.isUsingOldTempos());

			SequenceInfo sequenceInfo = abcSong.getSequenceInfo();
			sequencer.setSequence(sequenceInfo.getSequence());
			sendMIDIResets(sequenceInfo.standard);
			// TODO: should we not have sequencer be a LotroSeqWrapper when loading from abc?
            //       in which case we should here call seq.setCurrentTrackInfos(sequenceInfo.getLastTrackInfos());
            //       else its only abc player that can play abc files with more then 15 parts.
            //       Haven't checked how easy it is to do. NoteFilterSequencerWrapper differs in some ways.
			sequencer.setRealDura(sequenceInfo.realDuraTicks);
			
			firstMidiNoteTick = sequenceInfo.calcFirstNoteTick();
			if (!abcSong.isFromXmlFile() && !abcSong.isFromAbcFile()) {
				sequencer.setTickPosition(firstMidiNoteTick);
			}
			midiBarLabel.setBarNumberCache(sequenceInfo.getDataCache());

			setPartsListModel();
			abcSong.getParts().getListModel().addListDataListener(partsListListener);

			if (abcSong.isFromXmlFile()) {
				allowOverwriteSaveFile = true;
			}

			boolean autoplay = miscSettings.autoplayOnOpen;
			if (abcSong.isFromAbcFile() || abcSong.isFromXmlFile()) {
				// abc or msx
				if (abcSong.getParts().isEmpty()) {
					abcSong.createNewPart();
				}

				songPartsListPanel.selectPart(0);

				boolean startWithAbcPreview = hasEnabledAbcNotes(abcSong);
				if (!startWithAbcPreview && sequencer.isAtStart()) {
					// No ABC preview, playback from source MIDI.
					// Skip silence.
					sequencer.setTickPosition(firstMidiNoteTick);
				}
				updatePreviewMode(startWithAbcPreview, autoplay);
			} else {
				// midi
				if (abcSong.getParts().isEmpty()) {
					abcSong.createNewPart();
				}

				// Uncomment this line to preview lots of midis and skipping their intro:
				//sequencer.setTickPosition(sequencer.getTickLength()/4L);

				updatePreviewMode(false, autoplay);
			}
			scheduleUiRefresh();

			abcSong.setSkipSilenceAtStart(saveSettings.skipSilenceAtStart);
			abcSong.setDeleteMinimalNotes(saveSettings.deleteMinimalNotes);
            abcSong.setReducedFilesize(saveSettings.reducedFilesize);
            abcSong.setUseRestsInChords(saveSettings.useRestsInChords);

			
			// abcSong.setShowPruned(saveSettings.showPruned);

			setAbcSongModified(midiResolved);//  || abcSong.isDegraded() // if we dont mark it modified when degraded, there is less chance the user will save it and make the orig msx corrupt.
			midiResolved = false;
			updateTitle();
            arrangementView.scrollToTop();
			openSucceeded = true;
		} catch (SAXParseException e) {
			String message = e.getMessage();
			if (e.getLineNumber() >= 0) {
				message += "\nLine " + e.getLineNumber();
				if (e.getColumnNumber() >= 0)
					message += ", column " + e.getColumnNumber();
			}

			arrangementView.showInfoMessage(formatErrorMessage(UIText.get("maestro.could.not.open.0", file.getName()), message, getHTMLFontSizeNormal()));
			midiResolved = false;
		} catch (InvalidMidiDataException | IOException | FileParseException | SAXException e) {
			arrangementView.showInfoMessage(formatErrorMessage(UIText.get("maestro.could.not.open.0", file.getName()), e.getMessage(), getHTMLFontSizeNormal()));
			midiResolved = false;
		}
		
		// Don't add to recent projects if the file wasn't successfully opened or if it isn't an MSX file.
		if (shouldAddToRecentProjects(file, updateLastOpenedList, openSucceeded)) {
    		recentlyOpenedList.addOpenedFile(file);
    		updateOpenRecentMenu();
		}

		inOpenFile = false;
	}

	/**
	 * Determines whether the current project should be added to the recent projects list.
	 *
	 * @param file the project file
	 * @param includeInRecentProjectsList whether the last opened list should be updated
	 * @param openSucceeded whether the project was successfully opened
	 * @return true if the project should be added to the recent projects list, false otherwise
	 */
	static boolean shouldAddToRecentProjects(File file, boolean includeInRecentProjectsList, boolean openSucceeded) {
		return includeInRecentProjectsList
            && openSucceeded
            && file.getAbsolutePath().endsWith(Util.MSX_FILE_EXTENSION);
	}

	private void sendMIDIResets(MidiStandard standard) {
		volumeTransceiver.setStandard(standard);
		if (sequencer.isDefault() || standard == MidiStandard.ABC) {
			return;
		}
		try {

			volumeTransceiver.send(MidiFactory.createGMReset(), -1);
			Thread.sleep(100);
			//volumeTransceiver.send(MidiFactory.createGSReset(), -1);
			//Thread.sleep(200);
			switch (standard) {
				case GM2:
					volumeTransceiver.send(MidiFactory.createGM2Reset(), -1);
					Thread.sleep(100);
					break;
				case GS:
					volumeTransceiver.send(MidiFactory.createGSReset(), -1);
					Thread.sleep(100);
					break;
				case XG:
					volumeTransceiver.send(MidiFactory.createXGReset(), -1);
					Thread.sleep(100);
					break;
				case GM:
					//volumeTransceiver.send(MidiFactory.createGMReset(), -1);
					//Thread.sleep(100);
					break;
				default:
					break;
			}

			/*
			volumeTransceiver.send(MidiFactory.createNoteOnEventEx(64,0,100,0).getMessage(), -1);
			Thread.sleep(150);
			volumeTransceiver.send(MidiFactory.createNoteOffEventEx(64,0,0,0).getMessage(), -1);
			Thread.sleep(50);
			volumeTransceiver.send(MidiFactory.createNoteOnEventEx(64,0,100,0).getMessage(), -1);
			Thread.sleep(150);
			volumeTransceiver.send(MidiFactory.createNoteOffEventEx(64,0,0,0).getMessage(), -1);
			System.out.println("MIDI sent");
			Thread.sleep(200);
			*/
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private boolean reloadWithNewSource(File newSource) {
		if (newSource == null) {
			log.log(Level.WARNING, "No new source file to reload the project with");
			return false;
		}
		List<Pair<Boolean, Boolean>> soloMuteState = songPartsListPanel.getSoloMuteStates();
		File originalMsx = abcSong.getProjectFile();
		File oldSource = abcSong.getSourceFile();
		boolean modified = abcSongModified;
		final File tmpMsx;
		try {
			/* Create a temporary MSX file to save the current project state with the new
			* source file. Files.createTempFile() creates the
			* file with restrictive default permissions (typically -rw------- on
			* POSIX systems), making it accessible only to the creating user. This helps
			* prevent local information disclosure vulnerabilities.
			*/
			tmpMsx = Files.createTempFile(
        		Path.of(System.getProperty("java.io.tmpdir")),
        		"tmpproj",
        		Util.MSX_FILE_EXTENSION)
    		.toFile();
		} catch (IOException e) {
			log.log(Level.SEVERE, "Failed to create temporary MSX file for saving project state", e);
			return false;
		}

		// A different source: how to read it (if ABC) is decided again when the tmp project is loaded
		Object[] oldAbcReading = Objects.equals(newSource, oldSource) ? null : abcSong.resetAbcReading();
		abcSong.setProjectFile(tmpMsx);
		abcSong.setSourceFile(newSource);

		if (!finishSave(false)) {
			abcSong.setProjectFile(originalMsx);
			abcSong.setSourceFile(oldSource);
			if (oldAbcReading != null)
				abcSong.restoreAbcReading(oldAbcReading);
			log.log(Level.SEVERE, "Failed to save temporary MSX file");
			return false;
		}

        // discard old abcSong and abcParts
		AbcSong oldAbcSong = abcSong;
        if (oldAbcSong != null) {
            oldAbcSong.getParts().getListModel().removeListDataListener(partsListListener);
            oldAbcSong.discard();
        }
        boolean abcPreview = abcPreviewMode;
        boolean hideEdits = hideEditsCheckbox.isSelected();
        abcSongModified = false;

		openFile(tmpMsx, false);
		if (abcSong != null) {
			abcSong.setProjectFile(originalMsx);
			setAbcSongModified(!Objects.equals(newSource, oldSource) || modified);
			updateTitle();
		}
		
		songPartsListPanel.restoreSoloMuteState(soloMuteState);

        updatePreviewMode(abcPreview, miscSettings.autoplayOnOpen);
        if (hideEdits && !hideEditsCheckbox.isSelected()) hideEditsCheckbox.doClick();
		
		return true;
	}

	private void setPartsListModel() {
		// Not really used as a model anymore since switching to PartsList rather than JList
		songPartsListPanel.setModel(abcSong.getParts().getListModel());
		partEditor.setModel(abcSong.getParts().getListModel());
	}

	/** Used when the MIDI file in a Maestro song project can't be loaded. */
	private final FileResolver openFileResolver = new FileResolver() {
		@Override
		public File locateFile(File original, String message) {
			message += UIText.get("maestro.would.you.like.to.try.to.locate.the.file");
			return resolveHelper(original, message);
		}

		@Override
		public File resolveFile(File original, String message) {
			message += UIText.get("maestro.would.you.like.to.pick.a.different.file");
			return resolveHelper(original, message);
		}

		private File resolveHelper(File original, String message) {
			int result = JOptionPane.showConfirmDialog(ProjectFrame.this, message, UIText.get("maestro.failed.to.open.file"),
					JOptionPane.OK_CANCEL_OPTION);

			File alternateFile = null;
			if (result == JOptionPane.OK_OPTION) {
				JFileChooser jfc = createFilteredOpenChooser(null);
                jfc.addChoosableFileFilter(
                        new ExtensionFileFilter("MIDI",
                                Util.MID_FILE_EXTENSION_NO_DOT,
                                Util.MIDI_FILE_EXTENSION_NO_DOT, Util.KAR_FILE_EXTENSION_NO_DOT));
                jfc.addChoosableFileFilter(
                        new ExtensionFileFilter("ABC",
                                Util.ABC_FILE_EXTENSION_NO_DOT,
                                Util.TXT_FILE_EXTENSION_NO_DOT));
				jfc.setFileFilter(new ExtensionFileFilter(UIText.get("maestro.midi.and.abc.files"), Util.MID_FILE_EXTENSION_NO_DOT,
						Util.MIDI_FILE_EXTENSION_NO_DOT, Util.KAR_FILE_EXTENSION_NO_DOT, Util.ABC_FILE_EXTENSION_NO_DOT,
						Util.TXT_FILE_EXTENSION_NO_DOT));
                jfc.setAcceptAllFileFilterUsed(false);
				jfc.setDialogTitle(UIText.get("maestro.open.missing.midi.abc"));
				if (original != null)
					jfc.setSelectedFile(original);

				if (jfc.showOpenDialog(ProjectFrame.this) == JFileChooser.APPROVE_OPTION)
					alternateFile = jfc.getSelectedFile();
			}

			return alternateFile;
		}
	};

	/**
	 * A JFileChooser for opening files that rejects anything none of its filters would list.
	 * Swing otherwise returns a typed file name even if no filter accepts it
	 * (e.g. an extension-less file), and even if the file doesn't exist.
	 * Any of the choosable filters may accept the file, not just the active one, so a user
	 * who switched the dropdown to "MIDI" can still type the name of an .abc file.
	 *
	 * @param currentDirectoryPath start directory, or null for the user's default directory
	 */
	private static JFileChooser createFilteredOpenChooser(String currentDirectoryPath) {
		return new JFileChooser(currentDirectoryPath) {
			@Override
			public void approveSelection() {
				File selected = getSelectedFile();
				if (selected != null && selected.isFile() && isAcceptedByAnyFilter(selected)) {
					super.approveSelection();
				} else {
					JOptionPane.showMessageDialog(this,
							UIText.get("maestro.file.0.is.not.a.supported.file",
									selected == null ? "" : selected.getName()),
							UIText.get("maestro.unsupported.file"), JOptionPane.WARNING_MESSAGE);
					// dialog stays open, so the user can pick again
				}
			}

			private boolean isAcceptedByAnyFilter(File file) {
				FileFilter acceptAll = getAcceptAllFileFilter();
				for (FileFilter filter : getChoosableFileFilters()) {
					// "All files" would accept anything; it is disabled in Maestro's choosers,
					// but skip it explicitly so enabling it later can't bypass this check.
					if (filter != acceptAll && filter.accept(file))
						return true;
				}
				return false;
			}
		};
	}

	private static String formatInfoMessage(String title, String message, int fontSize) {
		int fontSizeHeader = Math.min(7, fontSize + 2);
		return "<html><font size='"+fontSizeHeader+"' color=\"" + ColorTable.CENTER_TEXT.getHtml()+ "\"><b>" + Util.htmlEscape(title)
				+ "<br></b></font><font size='"+fontSize+"' color=\"" + ColorTable.CENTER_TEXT.getHtml()+ "\">"
				+ Util.htmlEscape(message).replace("\n", "<br>")
				+ "</font><h3>&nbsp;</h3></html>";
	}

	private static String formatErrorMessage(String title, String message, int fontSize) {
		int fontSizeHeader = Math.min(7, fontSize + 2);
		return "<html><font size='"+fontSizeHeader+"' color=\"" + ColorTable.CENTER_TEXT_ERROR.getHtml() + "\"><b>" + Util.htmlEscape(title)
				+ "<br></b></font><font size='"+fontSize+"' color=\"" + ColorTable.CENTER_TEXT.getHtml()+ "\">"
				+ Util.htmlEscape(message).replace("\n", "<br>")
				+ "</font><h3>&nbsp;</h3></html>";
	}

	private int getHTMLFontSizeNormal() {
		return switch ((Integer)miscSettings.fontSize) {
			case Integer i when i < 7  -> 1; // approx 8pt
			case Integer i when i < 9  -> 2; // approx 10pt
			case Integer i when i < 11 -> 3; // approx 12pt (Standard)
			case Integer i when i < 13 -> 4; // approx 14pt
			case Integer i when i < 15 -> 5; // approx 18pt
			case Integer i when i < 19 -> 6; // approx 24pt
			default                    -> 7; // 36pt+
        };
	}

    public float getSourcePlayHeadBar() {
        long tickLength = Math.max(0, sequencer.getTickLength());
        long tick = Math.min(tickLength, sequencer.getThumbTick());
        SequenceDataCache cache = abcSong.getSequenceInfo().getDataCache();
        /*
        QuantizedTimingInfo qtm = null;
        try {
            qtm = abcSong.getAbcExporter().getTimingInfo();
        } catch (AbcConversionException e) {
            throw new RuntimeException(e);
        }
         */
        return cache.tickToBarNumberFloat(tick);
    }

	/**
	 * Switch between abc preview mode and source playback.
	 *
	 * If the preview is not up to date or loaded, a new preview will be generated synchronously if 'newAbcPreviewMode' is true.
	 *
	 * @param abcPreviewModeNew true for abc preview mode
	 */
	private void updatePreviewMode(boolean abcPreviewModeNew) {
		SequencerWrapper oldSequencer = abcPreviewMode ? abcSequencer : sequencer;
		updatePreviewMode(abcPreviewModeNew, oldSequencer.isRunning());
	}

	/**
	 * Switch between abc preview mode and source playback.
	 * This method will not skip initial silence, do that before calling the method.
	 *
	 * If the preview is not up to date or loaded, a new preview will be generated synchronously if 'newAbcPreviewMode' is true.
	 *
	 * @param newAbcPreviewMode true for abc preview mode
	 * @param shouldBeRunning true for starting sequencer if it's not running.
	 */
	private void updatePreviewMode(boolean newAbcPreviewMode, boolean shouldBeRunning) {
        // A created composition has no meaningful Original source. Prevent
        // switching to the blank scaffold when its authored preview exists.
        if (abcSong != null && abcSong.isCompositionMode() && hasEnabledAbcNotes(abcSong))
            newAbcPreviewMode = true;
		boolean runningNow = abcPreviewMode ? abcSequencer.isRunning() : sequencer.isRunning();

		// Do not allow switching to abc preview if there is no preview possible.
		// The check for 'abcPreviewMode' is to avoid calling the method which has a loop.
		if (newAbcPreviewMode && !abcPreviewMode && !hasEnabledAbcNotes(abcSong)) newAbcPreviewMode = false;

		if (newAbcPreviewMode != abcPreviewMode || runningNow != shouldBeRunning) {
			if (shouldBeRunning && newAbcPreviewMode) {

				// If the preview is still current (e.g. the background rebuild kicked
				// off when we switched to MIDI has since finished), reuse it instead of
				// re-running the full export synchronously on the EDT.
				// Only rebuild when it's not current or nothing is loaded.
				boolean ready = (previewAppliedSeq == previewRequestSeq) && abcSequencer.isLoaded();
				if (!ready) {
					ready = refreshPreviewSequence(true);
				}
				if (!ready) {
					shouldBeRunning = false;

					SequencerWrapper oldSequencer = abcPreviewMode ? abcSequencer : sequencer;
					oldSequencer.stop();
				}

			} else if (abcSong != null && abcSong.hasPreviewableParts()) {
				// for histogram. The condition is due to it might be
                // refreshPreviewSequence thats calling us, and we
                // don't want infinite loop.
				refreshPreviewSequence(false);
			}

			midiPositionLabel.setVisible(!newAbcPreviewMode);
			abcPositionLabel.setVisible(newAbcPreviewMode);
			midiBarLabel.setVisible(!newAbcPreviewMode);
			abcBarLabel.setVisible(newAbcPreviewMode);
            if(!rollActive){
                midiModeRadioButton.setSelected(!newAbcPreviewMode);
                abcModeRadioButton.setSelected(newAbcPreviewMode);
            }

			SequencerWrapper newSequencer = newAbcPreviewMode ? abcSequencer : sequencer;

			if (!newAbcPreviewMode && abcSong != null) {
				sendMIDIResets(abcSong.getSequenceInfo().standard);
			}

			newSequencer.setRunning(shouldBeRunning);

			abcPreviewMode = newAbcPreviewMode;
            SequencerWrapper.isAbcPreview = abcPreviewMode;

			arrangementView.setAbcPreviewMode(abcPreviewMode);
			scheduleUiRefresh();
		}
		if (abcPreviewMode) {
			long tick = abcSequencer.getTickPosition();
			if (tick < abcPreviewStartTick)
				tick = abcPreviewStartTick;

			if (tick >= abcSequencer.getTickLength()) {
				tick = 0;
				abcSequencer.setRunning(false);
			}
			abcSequencer.setTickPosition(tick);
		}
	}

    private JPanel playControlPanel;

    private class PreviewExportWorker extends SwingWorker<SequenceInfo, Boolean> {

        private final AbcExporter myExporter;
        private final AbcSong mySong;
        private final boolean lotroInstruments;
        private final boolean oldVelocities;
		private final long requestSeq;
        private Throwable backgroundException = null;

        public PreviewExportWorker(AbcSong mySong, boolean lotroInstruments, boolean oldVelocities, int pan, long requestSeq) throws AbcConversionException {
            this.mySong = new AbcSong(mySong);
            this.myExporter = this.mySong.getAbcExporter();
            this.myExporter.stereoPan = pan;
            this.lotroInstruments = lotroInstruments;
            this.oldVelocities = oldVelocities;
			this.requestSeq = requestSeq;
        }

        /**
         * This runs on non-Swing thread
         */
        @Override
        protected SequenceInfo doInBackground() throws AbcConversionException, InvalidMidiDataException {
            try {
                return SequenceInfo.fromAbcParts(mySong, lotroInstruments, oldVelocities);
            } catch (Throwable t) {
                backgroundException = t;
                throw t;
            }
        }

        /**
         * This runs on Swing thread
         */
        @Override
        protected void done() {
            if (isCancelled()) {
                if (backgroundException != null) {
                    // Log the error that
                    // happened even though we were canceled.
                    log.log(Level.WARNING, "Exception occurred during cancelled preview export", backgroundException);
                }
                return;
            }
            try {
				// Apply only if this build is still the latest requested one - a newer
				// edit may have superseded it while it ran - and the song still matches.
				// Otherwise the result is stale: don't load it and don't stamp it current.
				// The abcsong check is to guard against preview generated on worker thread,
				// but while that happens, the user sends a new file to Maestro via Windows
				// explorer. So we check if the abcSong matches.
				if (requestSeq == previewRequestSeq && abcSong == mySong.origSong) {
					applyPreview(get(), myExporter, requestSeq);
				}
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.log(Level.WARNING, "Error exporting preview", cause);

                sequencer.stop();
                abcSequencer.stop();
                JOptionPane.showMessageDialog(ProjectFrame.this, cause.getMessage(), UIText.get("maestro.error.previewing.abc"),
                        JOptionPane.WARNING_MESSAGE);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                log.log(Level.WARNING, "Error applying preview", e);
                Throwable cause = e.getCause() != null ? e.getCause() : e;

                sequencer.stop();
                abcSequencer.stop();
                JOptionPane.showMessageDialog(ProjectFrame.this, cause.getMessage(), UIText.get("maestro.error.previewing.abc"),
                        JOptionPane.WARNING_MESSAGE);
            } finally {
                setSourceChangeEnabled(true);
            }
        }
    }

    /**
     * Runs only in Swing Thread
     */
    private void applyPreview(SequenceInfo previewSequenceInfo, AbcExporter exporter, long appliedRequestSeq) {
        abcPreviewStartTick = exporter.getExportStartTick();
        // The source/export timing table can describe only the one-bar blank
        // scaffold in a native composition. Calculate authored-only bar labels
        // from the preview resolution and composition meter instead.
        if (abcSong != null && abcSong.isCompositionMode() && abcSong.hasAuthoredPartNotes()) {
            final int resolution = previewSequenceInfo.getSequence().getResolution();
            final long barTicks = Math.max(1L, Math.round(resolution * 4.0 *
                abcSong.getTimeSignature().numerator / abcSong.getTimeSignature().denominator));
            abcBarLabel.setBarNumberCache(new com.digero.common.midi.IBarNumberCache() {
                public int tickToBarNumber(long tick) { return (int)Math.max(0, tick/barTicks); }
                public float tickToBarNumberFloat(long tick) { return Math.max(0, tick)/(float)barTicks; }
                public long getBarToTick(int bar) { return Math.max(0L,bar)*barTicks; }
            });
        } else {
            abcBarLabel.setBarNumberCache(exporter.getTimingInfo());
        }
        abcBarLabel.setInitialOffsetTick(abcPreviewStartTick);
        abcPositionLabel.setInitialOffsetTick(abcPreviewStartTick);

        // A preview rebuild while playing must use the active ABC position,
        // not the original source's (often zero-length) sequencer position.
        long tick = PreviewPlayhead.restoreTick(abcPreviewMode,
                sequencer.getTickPosition(), abcSequencer.getTickPosition());

        boolean abcRunning = abcSequencer.isRunning();
        if (abcPreviewMode) {
            // Refreshing while playing Original (GS) will cause a GS Reset,
            // which will mess with volume, hence the if statement.
            abcSequencer.reset(false);
        }
        try {
            abcSequencer.setSequence(previewSequenceInfo.getSequence());
            abcSequencer.setCurrentTrackInfos(previewSequenceInfo.getLastTrackInfos());
            for(AbcPart p : abcSong.getParts()) {
                p.numberOfExportedNotes = 0;
                p.numberOfRemovedNotesForSafety = 0;
                p.numberOfRemovedNotesFromPruning = 0;
                p.numberOfRemovedNotesZeros = 0;
                p.numberOfRemovedNotesFromFitting = 0;
                p.setPanEvent(null);
                p.setMaxPoly(0);

                // Resetting to -1 is important, else when part A
                // disables all its tracks, it still keeps its preview-track
                // number from previous preview. Another part B might have
                // gotten the same preview track number in the meantime,
                // so when part A toggles mute or solo, it will affect
                // the wrong part.
                p.setPreviewSequenceTrackNumber(-1);
                p.clearAuthoredPreviewTracks();
            }
			AbcSong songCopy = null;
            if (previewSequenceInfo.getLastTrackInfos() != null) {
                //System.out.println("\nApply preview:");
                for (AbcExporter.ExportTrackInfo trackInfo : previewSequenceInfo.getLastTrackInfos()) {
					if(trackInfo.part.getAbcSong() != null) songCopy = trackInfo.part.getAbcSong();
                    //threadsafe to do it here
                    if (trackInfo instanceof com.digero.maestro.abc.AuthoredPreviewTrackInfo authored) {
                        // Keep the generated part's primary preview track stable.
                        // For an authored-only part use the first authored track.
                        if (trackInfo.part.getPreviewSequenceTrackNumber() < 0)
                            trackInfo.part.setPreviewSequenceTrackNumber(trackInfo.trackNumber);
                        trackInfo.part.addAuthoredPreviewTrack(authored);
                    } else {
                        trackInfo.part.setPreviewSequenceTrackNumber(trackInfo.trackNumber);
                    }
                    // Several preview tracks can belong to the same Song Part:
                    // a generated source track plus one or more routed piano-roll
                    // tracks. Add their counts instead of letting the last authored
                    // track overwrite the imported total. The removed-note stats
                    // still originate solely from the generated exporter.
                    if (!(trackInfo instanceof com.digero.maestro.abc.AuthoredPreviewTrackInfo)) {
                        trackInfo.part.numberOfRemovedNotesForSafety += trackInfo.numberOfRemovedNotesForSafety;
                        trackInfo.part.numberOfRemovedNotesFromPruning += trackInfo.numberOfRemovedNotesFromPruning;
                        trackInfo.part.numberOfRemovedNotesZeros += trackInfo.numberOfRemovedNotesZeros;
                        trackInfo.part.numberOfRemovedNotesFromFitting += trackInfo.numberOfRemovedNotesFromFitting;
                        trackInfo.part.setMaxPoly(trackInfo.maxPoly);
                    }
                    if (!(trackInfo instanceof com.digero.maestro.abc.AuthoredPreviewTrackInfo))
                        trackInfo.part.setPanEvent(trackInfo.panEvent);
					trackInfo.seqInfo = previewSequenceInfo;
                    //System.out.println(trackInfo);
                }
            }
            // Reconcile ALL tracks per part, not just whichever (often authored)
            // track happens to appear last in the preview metadata list.
            com.digero.maestro.abc.PreviewPartStats.updateExportedNoteCounts(
                previewSequenceInfo.getLastTrackInfos(), abcSong.getEditedPreviewSourcePartNumbers());
            // The selected part's separate authored-track graphs must be
            // reconstructed after receiving new preview metadata. Without this
            // they remain invisible until the user reselects the Song Part.
            // Use instrument-aware audio voice durations for the per-part peak
            // as well: raw piano-roll NOTE_OFF edges are not when a plucked
            // sample stops sounding in LOTRO.
            for (AbcPart part : abcSong.getParts()) {
                if (!part.getAuthoredPreviewTracks().isEmpty()
                        || abcSong.getNoteEditorDeltas().containsKey(part.getPartNumber()))
                    part.setMaxPoly(previewSequenceInfo.histogram.peakForPart(part));
            }
            abcSequencer.setStartTick(abcPreviewStartTick);// Needed for MP3 and WAV exports.
			if (songCopy != null && songCopy.getCountIn() != null) {
				abcSequencer.setCountInMicros(songCopy.getCountIn().micros);
			} else {
				abcSequencer.setCountInMicros(0L);
			}

            long lengthABC = abcSong.getSongLengthMicros();

            log.info("A new preview was generated");
            log.info("Duration MIDI:    " + Util.formatDurationM(sequencer.getLength()));
            log.info("Duration Preview: " + Util.formatDurationM(abcSequencer.getLength() - abcSequencer.tickToMicros(abcPreviewStartTick)) + ", rounded up: " + Util.formatDuration(abcSequencer.getLength() - abcSequencer.tickToMicros(abcPreviewStartTick)));
            log.info("Duration ABC:     " + Util.formatDurationM(lengthABC) + ", rounded up: " + Util.formatDuration(lengthABC));

            /*
            if (tick < abcPreviewStartTick)
                tick = abcPreviewStartTick;

            if (tick >= abcSequencer.getTickLength()) {
                tick = 0;
                abcRunning = false;
            }
            */

            if (abcRunning && sequencer.isRunning())
                sequencer.stop();

            abcSequencer.setTickPosition(tick);
			abcSequencer.setRunning(abcRunning);
            previewSequenceInfo.histogram.setSequencer(abcSequencer);
            if (previewSequenceInfo.dissonance != null) previewSequenceInfo.dissonance.setSequencer(abcSequencer);
			PolyphonyHistogram oldHisto = arrangementView.getHistogram();
			if (oldHisto != null && oldHisto != previewSequenceInfo.histogram) {
				// remove listeners
				oldHisto.setSequencer(null);
			}
            arrangementView.setHistogram(previewSequenceInfo.histogram);
			DissonanceDetector oldDisso = arrangementView.getDissonance();
			if (oldDisso != null && oldDisso != previewSequenceInfo.dissonance) {
				// remove listeners
				oldDisso.setSequencer(null);
			}
            arrangementView.setDissonance(previewSequenceInfo.dissonance);
            histogram = previewSequenceInfo.histogram;
            updateStereo();// we call this here to benefit PanVisualizerPanel

			// Success: the abcSequencer now holds the build for this request id.
			previewAppliedSeq = appliedRequestSeq;
            requestRollBaselineRefresh();
            applyRollPlaybackScope();
            // setSequence() raises TRACK_ACTIVE before the new preview track
            // numbers are installed. A removed part shifts later track indices,
            // so imported rows can cache colors for the previous mapping.
            // Reconcile every row once the new routing and mute/solo state are final.
            arrangementView.refreshTrackRoutingAppearance();
            // Only refresh the row layout once the new sequencer, histogram,
            // and part metadata are ready. The old B2.4 path rebuilt it inside
            // the metadata loop and could paint a half-initialized preview.
            AbcPart selectedForRows = songPartsListPanel.getSelectedPart();
            if (selectedForRows != null &&
                (arrangementView.hasAuthoredPreviewRows() ||
                 !selectedForRows.getAuthoredPreviewTracks().isEmpty())) {
                final AbcSong expectedSong = abcSong;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (abcSong == expectedSong && appliedRequestSeq == previewAppliedSeq &&
                        selectedForRows == songPartsListPanel.getSelectedPart())
                        arrangementView.setAbcPart(selectedForRows,true);
                });
            }
        } catch (InvalidMidiDataException e) {
            log.log(Level.WARNING, "Error after exporting preview", e);
            sequencer.stop();
            abcSequencer.stop();
            arrangementView.setHistogram(null);
            arrangementView.setDissonance(null);
            histogram = null;
            JOptionPane.showMessageDialog(ProjectFrame.this, e.getMessage(), UIText.get("maestro.error.previewing.abc"),
                    JOptionPane.WARNING_MESSAGE);
        }
        compileStats();
    }

    private boolean refreshPreviewSequence(boolean immediate) {
        if (!SwingUtilities.isEventDispatchThread()) {
            log.log(Level.SEVERE, "refreshPreviewSequence: not on Swing thread", new RuntimeException());
            return false;
        }

		// Each rebuild request gets a new monotonic id. The preview counts as current
		// only once applyPreview stamps this id into previewAppliedSeq.
		final long requestSeq = ++previewRequestSeq;
		if (log.isLoggable(Level.FINE)) {
			log.log(Level.FINE, "refreshPreviewSequence #" + requestSeq + " immediate=" + immediate,
					new Throwable("preview rebuild call site"));
		}

        PreviewExportWorker oldWorker = null;
        if (previewWorker != null) {
            if (!previewWorker.isDone()) {
                oldWorker = previewWorker;
                previewWorker.cancel(true);
            }
        }

        if (abcSong == null || !abcSong.hasPreviewableParts()) {
            abcPreviewStartTick = 0L;
            abcSequencer.clearSequence();
            abcSequencer.reset(false);
            abcBarLabel.setBarNumberCache(null);
            abcBarLabel.setInitialOffsetTick(abcPreviewStartTick);
            abcPositionLabel.setInitialOffsetTick(abcPreviewStartTick);
            arrangementView.setHistogram(new PolyphonyHistogram());
            arrangementView.setDissonance(new DissonanceDetector(null));
            histogram = null;
			previewAppliedSeq = -1;
            updatePreviewMode(false);
            setSourceChangeEnabled(true);
            return false;
        }

        if (!immediate) {
            try {
                abcSong.setSkipSilenceAtStart(saveSettings.skipSilenceAtStart);
                abcSong.setDeleteMinimalNotes(saveSettings.deleteMinimalNotes);
                abcSong.setReducedFilesize(saveSettings.reducedFilesize);
                abcSong.setUseRestsInChords(saveSettings.useRestsInChords);
                // abcSong.setShowPruned(saveSettings.showPruned);
                previewWorker = new PreviewExportWorker(abcSong, !failedToLoadLotroInstruments, false, prefs.getInt("stereoPan", defaultStereo), requestSeq);
                setSourceChangeEnabled(false);
                previewWorker.execute();
            } catch (AbcConversionException e) {
                log.log(Level.WARNING, "Error exporting preview", e);
                sequencer.stop();
                abcSequencer.stop();
                JOptionPane.showMessageDialog(ProjectFrame.this, e.getMessage(), UIText.get("maestro.error.previewing.abc"),
                        JOptionPane.WARNING_MESSAGE);
                setSourceChangeEnabled(true);
            }

            return true;
        }

        try {
            if (oldWorker != null) {
                try {
					// The cancelled worker runs on its own deep copy of the AbcSong
					// (PreviewExportWorker copies it; AbcSong's copy ctor nulls abcExporter),
					// so it cannot touch the exporter this immediate build uses, the old
					// "wait so getAbcExporter can't corrupt shared state" reason is gone.
					// We give it a brief bounded moment to unwind after cancel(true) so we don't
					// run two exports on the same cores, but never freeze the EDT waiting.
					// If it finishes anyway its done() is requestSeq-guarded and won't apply.
					oldWorker.get(2, TimeUnit.SECONDS);
                    // doInBackground has now finished
				} catch (TimeoutException te) {
					// Cancelled worker didn't stop in time. Don't block the EDT waiting
					// on it; the immediate rebuild below builds from current state anyway,
					// and the stale worker's done() is guarded by requestSeq so it can't
					// apply over us.
					log.log(Level.WARNING, "Cancelled preview worker did not finish in time; proceeding", te);
				} catch (Throwable ignored) {
                }
            }

            abcSong.setSkipSilenceAtStart(saveSettings.skipSilenceAtStart);
            abcSong.setDeleteMinimalNotes(saveSettings.deleteMinimalNotes);
            abcSong.setReducedFilesize(saveSettings.reducedFilesize);
            abcSong.setUseRestsInChords(saveSettings.useRestsInChords);
            // abcSong.setShowPruned(saveSettings.showPruned);
            AbcExporter exporter = abcSong.getAbcExporter();
            exporter.stereoPan = prefs.getInt("stereoPan", defaultStereo);
            // Overlay authored tracks while constructing SequenceInfo, before its
            // tempo cache and polyphony histogram are finalized (as in the worker).
            SequenceInfo previewSequenceInfo = SequenceInfo.fromAbcParts(exporter, abcSong,
                !failedToLoadLotroInstruments, false);
            applyPreview(previewSequenceInfo, exporter, requestSeq);
            setSourceChangeEnabled(true);
            return true;
        } catch(Exception e) {
            //setUIEnabled(true);
            log.log(Level.WARNING, "Error exporting preview (immediate)", e);
            Throwable cause = e.getCause() != null ? e.getCause() : e;

            sequencer.stop();
            abcSequencer.stop();
            JOptionPane.showMessageDialog(ProjectFrame.this, cause.getMessage(), UIText.get("maestro.error.previewing.abc"),
                    JOptionPane.WARNING_MESSAGE);
        }
        setSourceChangeEnabled(true);
        return false;
    }

	private void commitAllFields() {
		try {
			arrangementView.stopEditingLyrics();
			abcSong.setNote(arrangementView.getTextnote(), false);
			if (arrangementView.isLyricsModified()) abcSong.setLyricLines(arrangementView.getLyricLines(), false);
			else abcSong.setLyricLines(null, false);
			arrangementView.commitAllFields();
			songExportSettingsPanel.commitAllFields();
		} catch (ParseException ignore) {
		}
	}
	
	public void compileStats() {
        boolean hasAbcNotes = false;
        if (abcSong != null) {
            for (AbcPart part : abcSong.getParts()) {
                if (part.getEnabledTrackCount() > 0) {
                    hasAbcNotes = true;
                    break;
                }
            }
        }
		if (abcSong != null && abcSong.hasAuthoredPartNotes()) hasAbcNotes = true;
		if (abcSong == null || !hasAbcNotes) {
			arrangementView.setStats("No stats available");
			return;
		}
		String tempNote = "";
		tempNote += "Source song:\n" + abcSong.getSourceFile().getAbsolutePath()+"\n\n";
		tempNote += getTimingStats();
		tempNote += checkDuplicatePartTitles();
		tempNote += getNumberOfExportNotes();
        tempNote += getPoly6plusStats();
		tempNote += getEmptyParts();
        tempNote += abcSong.getStats();
        if (histogram != null) {
            if (histogram.isDirty()) {
                // important for solo/mute parts
                histogram.sumUp(abcSong);
            }
            tempNote += histogram.getStats();
        }
		int tempo = songExportSettingsPanel.getTempo();
        tempNote += "\nMain export tempo will be " + tempo + ".\n"
                + (AbcConstants.isStrangeBPM(tempo)?(
                "Recommendation: To ease output of fractions"
                +" without repeating decimals, Maestro recommend"
                +" to decrease the tempo to "
                +(AbcConstants.isStrangeBPM(tempo-1)?tempo-2:tempo-1)):"");
		arrangementView.setStats(tempNote);

        /*
            TODO:
                Stats on broken up notes.
                Stats on bent notes. How many per part, and how many they got expanded to.
                Stats on removed notes and why. And then enable number of exported notes.
                Stats on shortest/longest note durations in song.
                Stats on highest/lowest exported pitch.
                Number of song section-edits.
         */
	}
	
	private String getTimingStats() {
		String out = "";
		QuantizedTimingInfo qtm = abcSong.getQTM();
		if (qtm != null) {
			out += qtm.getStats()+"\n";
			out += qtm.getTempoStats()+"\n";
		}		
		return out;
	}

	@SuppressWarnings("HardCodedStringLiteral")
	private String getNumberOfExportNotes() {
        StringBuilder out = new StringBuilder();
        int songNotes = 0;
        for (AbcPart part : abcSong.getParts()) {
            out.append("Part #").append(part.getPartNumber()).append(" will export ").append(part.numberOfExportedNotes).append(" notes.\n");
            songNotes += part.numberOfExportedNotes;
        }
        out.append("\nSong").append(" will export ").append(songNotes).append(" notes.\n");
        if ((saveSettings.deleteMinimalNotes && !abcSong.isOrganic()) || (abcSong.isOrganic() && abcSong.isOrganic2())) {
            out.append("\n");
            if (abcSong.isOrganic()) out.append("Delete short notes that became dissonant due to fitting:\n");
            else out.append("Delete minimal (very short) notes that due to slight fitting might produce undesired dissonance:\n");
            boolean active = false;
            for (AbcPart part : abcSong.getParts()) {
                if (part.numberOfRemovedNotesForSafety > 0) {
                    out.append("Part #").append(part.getPartNumber()).append(" removed ").append(part.numberOfRemovedNotesForSafety).append("\n");
                    active = true;
                }
            }
            if (!active) {
                out.append(" None.\n");
            }
        }
        out.append("\nDeletion of notes (or partial notes) that start at same time and exceeded max poly for a part:\n");
        int sum = 0;
        for (AbcPart part : abcSong.getParts()) {
            if (part.numberOfRemovedNotesFromPruning > 0) {
                sum += part.numberOfRemovedNotesFromPruning;
                out.append("Part #").append(part.getPartNumber()).append(" removed ").append(part.numberOfRemovedNotesFromPruning).append("\n");
            }
        }
        if (sum == 0) out.append("None.\n");
        if (abcSong.isOrganic()) {
            out.append("\nDeletion of notes (or partial notes) that didn't fit into timeline, considering lotro constraints:\n");
            sum = 0;
            for (AbcPart part : abcSong.getParts()) {
                if (part.numberOfRemovedNotesFromFitting > 0) {
                    sum += part.numberOfRemovedNotesFromFitting;
                    out.append("Part #").append(part.getPartNumber()).append(" removed ").append(part.numberOfRemovedNotesFromFitting).append("\n");
                }
            }
            if (sum == 0) out.append("None.\n");
        }
        if (abcSong.isOrganic() && !abcSong.isOrganic2()) {
            out.append("\nDeletion of notes that had zero duration in source midi:\n");
            sum = 0;
            for (AbcPart part : abcSong.getParts()) {
                if (part.numberOfRemovedNotesZeros > 0) {
                    sum += part.numberOfRemovedNotesZeros;
                    out.append("Part #").append(part.getPartNumber()).append(" removed ").append(part.numberOfRemovedNotesZeros).append("\n");
                }
            }
            if (sum == 0) out.append("None.\n");
        }
        out.append("\nBeside the above deletions, export counts can differ due to the way bent notes are subdivided," +
                " how arpeggios are handled and how long notes are being interrupted. Also note that some deletions are of partial notes.\n");
		out.append("\n");
		return out.toString();
	}

    private String getPoly6plusStats() {
        StringBuilder out = new StringBuilder();
        if (abcSong != null && abcSong.isOrganic() && abcSong.isUseRestsInChords()) {
            // expensive to compute, so we only do it for poly 6+
            out.append("\n");
            out.append(UIText.get("maestro.part.polyphony"));
            for (AbcPart part : abcSong.getParts()) {
                out.append(UIText.get("maestro.partnumber")).append(part.getPartNumber()).append(UIText.get("maestro.has.max"));
                out.append(part.getMaxPoly()).append("\n");
            }
            out.append("\n");
        }
        return out.toString();
    }

	private String getEmptyParts() {
		StringBuilder out = new StringBuilder();
		for (AbcPart part : abcSong.getParts()) {
			if (part.getEnabledTrackCount() == 0 && part.getAuthoredPreviewTracks().isEmpty()) {
				out.append(UIText.get("maestro.partnumber")).append(part.getPartNumber()).append(UIText.get("maestro.has.no.assigned.tracks"));
			}
		}
		return out.toString();
	}
	
	private String checkDuplicatePartTitles() {
		StringBuilder out = new StringBuilder();
		ListModelWrapper<AbcPart> parts = abcSong.getParts();
		for (int i = 0 ; i < parts.size(); i++) {
			AbcPart part1 = parts.get(i);
			for (int j = i+1 ; j < parts.size(); j++) {
				AbcPart part2 = parts.get(j);
				if (part1.getTitle().equals(part2.getTitle())) {
					out.append(UIText.get("maestro.warning.part")).append(part1.getPartNumber()).append(UIText.get("maestro.and.part")).append(part2.getPartNumber()).append(UIText.get("maestro.has.same.title")).append(part1.getTitle()).append("\n\n");
				}
			}
		}
		return out.toString();
	}

	private File doSaveDialog(File defaultFile, File allowOverwriteFile, String extension, FileFilter fileFilter) {
		JFileChooser jfc = new JFileChooser();
		jfc.setFileFilter(fileFilter);
		jfc.setSelectedFile(defaultFile);

		jfc.setDialogTitle(UIText.get("maestro.save.to.0", Util.ellipsis(jfc.getCurrentDirectory().getAbsolutePath(), 64)));
		jfc.addPropertyChangeListener(JFileChooser.DIRECTORY_CHANGED_PROPERTY, new PropertyChangeListener() {
			@Override
			public void propertyChange(PropertyChangeEvent evt) {
				File currentDir = (File) evt.getNewValue();
				if (currentDir != null) {
					jfc.setDialogTitle(UIText.get("maestro.save.to.0", Util.ellipsis(jfc.getCurrentDirectory().getAbsolutePath(), 64)));
				}
			}
		});

		while (true) {
			int result = jfc.showSaveDialog(this);
			if (result != JFileChooser.APPROVE_OPTION || jfc.getSelectedFile() == null)
				return null;

			File selectedFile = jfc.getSelectedFile();
			String fileName = selectedFile.getName();
			int dot = fileName.lastIndexOf('.');
			if (dot <= 0 || !fileName.substring(dot).equalsIgnoreCase(extension)) {
				fileName += extension;
				selectedFile = new File(selectedFile.getParent(), fileName);
			}

			if (selectedFile.exists() && !selectedFile.equals(allowOverwriteFile)) {
				int res = JOptionPane.showConfirmDialog(this,
						UIText.get("maestro.file.0.already.exists.do.you.want.to.replace.it", fileName),
						UIText.get("maestro.confirm.replace.file"), JOptionPane.YES_NO_CANCEL_OPTION);
				if (res == JOptionPane.CANCEL_OPTION || res == JOptionPane.CLOSED_OPTION)
					return null;
				if (res != JOptionPane.YES_OPTION)
					continue;
			}

			return selectedFile;
		}
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot >= 0 ? name.substring(0, dot) : name;
	}
	
	private File getAbcExportFile() {
		File exportFile = abcSong.getExportFile();

		String defaultFolder = Util.getLotroMusicPath(true).getAbsolutePath();
		String folder = prefs.get("exportDialogFolder", defaultFolder);
		if (exportFile != null) // Use previously exported folder if it exists
			folder = exportFile.getAbsoluteFile().getParent();
		if (!new File(folder).exists())
			folder = defaultFolder;

		String fileName = "mySong"+Util.ABC_FILE_EXTENSION;

		// Always regenerate setting from pattern export is highest precedent
		if (exportFilenameTemplate.shouldRegenerateFilename()) {
			fileName = exportFilenameTemplate.formatName(abcSong);
		} else if (exportFile != null) // else use abc filename if exists already
		{
			fileName = exportFile.getName();
		} else if (abcSong.getProjectFile() != null) // else use msx filename if exists already
		{
			fileName = abcSong.getProjectFile().getName();
		} else if (exportFilenameTemplate.isEnabled()) // else use pattern if usage is enabled
		{
			fileName = exportFilenameTemplate.formatName(abcSong);
		} else if (abcSong.getSourceFile() != null) // else default to source file (midi/abc)
		{
			fileName = abcSong.getSourceFilename();
		}

		fileName = StringCleaner.cleanForFileName(stripExtension(fileName));
		fileName += Util.ABC_FILE_EXTENSION;

		exportFile = new File(folder, fileName);
		
		return exportFile;
	}


    /** Convert between the editor's PPQ and the currently playing sequence's PPQ.
     *  ABC preview has a possible start offset, which is not part of the ABC notes. */
    private long rollTickToMaestro(boolean abc,long editorTick,int editorPpq){
        SequencerWrapper transport=abc?abcSequencer:sequencer;
        javax.sound.midi.Sequence seq=transport.getSequence();
        if(seq==null || editorPpq<=0)return 0;
        if (abc && abcSong != null) {
            double microsPerTick = 60_000_000.0 / (abcSong.getTempoBPM() * (double) editorPpq);
            return Math.max(0, transport.microsToTick(transport.tickToMicros(abcPreviewStartTick)
                + Math.round(editorTick * microsPerTick)));
        }
        return Math.max(0,Math.round(editorTick*(double)seq.getResolution()/editorPpq));
    }
    private long maestroTickToRoll(boolean abc,int editorPpq){
        SequencerWrapper transport=abc?abcSequencer:sequencer;
        javax.sound.midi.Sequence seq=transport.getSequence();
        if(seq==null)return 0;
        if (abc && abcSong != null) {
            double microsPerTick = 60_000_000.0 / (abcSong.getTempoBPM() * (double) editorPpq);
            return Math.max(0, Math.round((transport.tickToMicros(transport.getTickPosition())
                - transport.tickToMicros(abcPreviewStartTick)) / microsPerTick));
        }
        return Math.max(0,Math.round(transport.getTickPosition()*(double)editorPpq/seq.getResolution()));
    }
    private com.digero.maestro.noteedit.NoteEditorApp.TransportBridge makeRollTransport(boolean abc){
        return new com.digero.maestro.noteedit.NoteEditorApp.TransportBridge(){
            @Override public void playOrPause(){updateSequencer();}
            @Override public void stop(){
                (abc?abcSequencer:sequencer).stop();
                (abc?abcSequencer:sequencer).reset(false);
                scheduleUiRefresh();
            }
            @Override public void seek(long tick,int ppq){
                SequencerWrapper transport=abc?abcSequencer:sequencer;
                if(!transport.isLoaded())return;
                transport.setTickPosition(Math.min(transport.getTickLength(),rollTickToMaestro(abc,tick,ppq)));
                scheduleUiRefresh();
            }
            @Override public long position(int ppq){return maestroTickToRoll(abc,ppq);}
            @Override public boolean isRunning(){return (abc?abcSequencer:sequencer).isRunning();}
        };
    }
    private void requestRollBaselineRefresh(){
        if(rollActive && abcRollEditor!=null && rollSong==abcSong && abcSong!=null)
            rollBaselineRefreshTimer.restart();
    }
    /** Selection is keyed by X: number, never instrument or list position.
     * An empty newly added part is immediately editable, even before export. */
    private void selectExactRollPart(AbcPart part) {
        if (part==null || abcRollEditor==null || abcSong==null) return;
        for (var spec:abcSong.getEditorPartSpecs()) {
            if (spec.number()==part.getPartNumber()) {
                abcRollEditor.ensureAbcPart(spec,abcSong.getNoteEditorDeltas());
                return;
            }
        }
    }

    /** Keep the Song Parts mute/solo state; current-part playback is an
     *  additional temporary solo filter that is removed when leaving the roll. */
    private void applyRollPlaybackScope(){
        if(abcSong==null || abcSequencer==null || !abcSequencer.isLoaded())return;
        boolean currentOnly=rollActive && rollSourceAbc && abcRollEditor!=null && !abcRollEditor.playsAllParts();
        int activePart=currentOnly?abcRollEditor.currentPartNumber():-1;
        for(AbcPart part:abcSong.getParts()){
            for(int track:part.getAllPreviewTrackNumbers()) {
                if(abcSequencer.getSequence()==null ||
                   track>=abcSequencer.getSequence().getTracks().length)continue;
                abcSequencer.setTrackSolo(track,currentOnly?part.getPartNumber()==activePart:part.isSoloed());
                abcSequencer.setTrackMute(track,part.isMuted());
            }
        }
    }
    /** Changes to Solo/Mute are playback filters, not deletions in the editable ABC. */
    private void syncRollMuteSolo(){
        if(abcRollEditor==null || abcSong==null)return;
        java.util.Map<Integer,boolean[]> states=new java.util.HashMap<>();
        for(AbcPart part:abcSong.getParts())
            states.put(part.getPartNumber(),new boolean[]{part.isMuted(),part.isSoloed()});
        abcRollEditor.setPartMuteSoloStates(states);
        applyRollPlaybackScope();
    }
    /** Refresh ONLY from Maestro's current final generated arrangement (never source tracks). */
    private void refreshGeneratedRollAsync(){
        if(!rollActive || abcRollEditor==null || rollSong!=abcSong || abcSong==null)return;
        final AbcSong current=abcSong;
        final long request=++rollRefreshGeneration;
        final AbcSong snapshot=new AbcSong(current);
        new SwingWorker<String,Void>(){
            @Override protected String doInBackground() throws Exception {
                Path f=Files.createTempFile("maestro-roll-generated-", ".abc");
                try {
                    snapshot.exportGeneratedAbc(f.toFile(),MaestroMain.APP_NAME);
                    return snapshot.addMissingEditorParts(Files.readString(f));
                } finally {Files.deleteIfExists(f);}
            }
            @Override protected void done(){
                if(request!=rollRefreshGeneration || abcSong!=current || abcRollEditor==null)return;
                try {
                    abcRollEditor.reloadGeneratedAbc(get(),current.getNoteEditorDeltas());
                    AbcPart selected=songPartsListPanel.getSelectedPart();
                    if(selected!=null)selectExactRollPart(selected);
                    syncRollMuteSolo();
                } catch(Exception ex){
                    log.log(Level.WARNING,"Could not refresh piano roll from generated ABC",ex);
                }
            }
        }.execute();
    }

    /** Update the cached editor atomically with Tune Apply, including when
     * the roll is hidden. Cancel outstanding refreshes of the previous map. */
    private void refreshRollAfterTuneEdit() {
        rollBaselineRefreshTimer.stop();
        ++rollRefreshGeneration;
        if (abcRollEditor == null || rollSong != abcSong || abcSong == null) return;
        Path temp = null;
        try {
            temp = Files.createTempFile("maestro-tune-roll-", ".abc");
            new AbcSong(abcSong).exportGeneratedAbc(temp.toFile(), MaestroMain.APP_NAME);
            abcRollEditor.reloadGeneratedAbc(abcSong.addMissingEditorParts(Files.readString(temp)),
                abcSong.getNoteEditorDeltas());
            AbcPart selected = songPartsListPanel.getSelectedPart();
            if (selected != null) selectExactRollPart(selected);
            syncRollMuteSolo();
        } catch (Exception ex) {
            log.log(Level.WARNING, "Could not refresh piano roll after Tune Apply; preserving project notes", ex);
            // The model is authoritative: never stage an obsolete editor.
            abcRollEditor.suspendEmbeddedView();
            rollModeHost.remove(abcRollEditor);
            abcRollEditor = null;
            if (rollActive) enterPianoRoll();
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) {}
        }
    }

    /** Persist edits against OLD Tune Editor sections before applying new FX. */
    void persistRollEditsBeforeTuneApply() {
        if (rollActive && abcRollEditor != null) stageRollEdits();
    }

    private void stageRollEdits(){
        if(rollSong==null || abcSong!=rollSong)return;
        if(midiRollEditor!=null)midiRollEditor.stageChanges();
        if(abcRollEditor!=null){
            abcRollEditor.stageChanges();
            // Saving must use the editor's current notes even if a background
            // preview refresh already marked its document clean. Persist
            // the final deltas before serializing the MSX project.
            abcSong.setNoteEditorDeltas(abcRollEditor.getNoteDeltasPreserving(
                abcSong.getNoteEditorDeltas()));
        }
    }
    private void updateRollNoteClickShortcuts() {
        ShortcutManager.NoteClickShortcut shortcut = keyboardShortcuts.getNoteClickShortcut();
        if (midiRollEditor != null)
            midiRollEditor.setNoteCreationMouseGesture(shortcut.enabled(), shortcut.modifiers());
        if (abcRollEditor != null)
            abcRollEditor.setNoteCreationMouseGesture(shortcut.enabled(), shortcut.modifiers());
    }
    private void leavePianoRoll(){
        rollBaselineRefreshTimer.stop();
        if(midiRollEditor!=null)midiRollEditor.suspendEmbeddedView();
        if(abcRollEditor!=null)abcRollEditor.suspendEmbeddedView();
        rollModeCards.show(rollModeHost,"arrangement");
        rollActive=false;
        applyRollPlaybackScope(); // Restore the Song Parts' own solo/mute settings
        if(noteEditModeMenuItem!=null)noteEditModeMenuItem.setSelected(false);
    }
    private void enterPianoRoll(){
        if(abcSong==null){pianoRollModeRadioButton.setSelected(false);return;}
        rollActive=true;
        rollSourceAbc=abcPreviewMode; // preserve Maestro transport position and running state
        if(noteEditModeMenuItem!=null)noteEditModeMenuItem.setSelected(true);
        // Only the current original/ABC preview context determines the document;
        // we do not infer this from an unrelated file-chooser tab.
        boolean desiredAbc=rollSourceAbc || abcSong.getSourceFile()==null ||
            abcSong.getSourceFile().getName().toLowerCase(java.util.Locale.ROOT).endsWith(".abc");
        if(desiredAbc && !abcPreviewMode){
            // An ABC-source project has no editable raw-MIDI source layer here;
            // use the generated preview's LOTRO transport and matching timebase.
            rollSourceAbc=true;
            updatePreviewMode(true,sequencer.isRunning());
        }
        com.digero.maestro.noteedit.NoteEditorApp.EditorTab existing=desiredAbc?abcRollEditor:midiRollEditor;
        if(rollSong==abcSong && existing!=null){
            if(desiredAbc){
                AbcPart selectedPart=songPartsListPanel.getSelectedPart();
                if(selectedPart!=null)existing.selectPartNumber(selectedPart.getPartNumber());
                syncRollMuteSolo();
            }
            rollModeCards.show(rollModeHost,desiredAbc?"abcRoll":"midiRoll");
            existing.fitView();
            existing.resumeEmbeddedView();
            applyRollPlaybackScope();
            requestRollBaselineRefresh();
            return;
        }
        if(noteEditorOpening)return;
        noteEditorOpening=true;
        rollModeCards.show(rollModeHost,"loading");
        final AbcSong current=abcSong;
        new SwingWorker<File[],Void>(){
            @Override protected File[] doInBackground() throws Exception{
                Path dir=Files.createTempDirectory("maestro-roll-");dir.toFile().deleteOnExit();
                File source=current.getSourceFile();
                byte[] stored=current.getNoteEditorMidi();
                if(stored!=null){source=dir.resolve("source-edited.mid").toFile();
                    Files.write(source.toPath(),stored);source.deleteOnExit();}
                File abc=dir.resolve("arrangement.abc").toFile();abc.deleteOnExit();
                String saved=current.getNoteEditorAbc();
                if(saved!=null && current.getNoteEditorDeltas().isEmpty())
                    Files.writeString(abc.toPath(),saved); // v1.3 snapshot fallback
                else {
                    current.exportGeneratedAbc(abc,MaestroMain.APP_NAME);
                    Files.writeString(abc.toPath(),current.addMissingEditorParts(Files.readString(abc.toPath())));
                }
                return new File[]{source,abc};
            }
            @Override protected void done(){
                noteEditorOpening=false;
                if(abcSong!=current)return;
                try{
                    File[] files=get();
                    if(rollSong!=current){
                        if(midiRollEditor!=null){midiRollEditor.suspendEmbeddedView();rollModeHost.remove(midiRollEditor);}
                        if(abcRollEditor!=null){abcRollEditor.suspendEmbeddedView();rollModeHost.remove(abcRollEditor);}
                        midiRollEditor=null;abcRollEditor=null;rollSong=current;
                    }
                    com.digero.maestro.noteedit.NoteEditorApp.ProjectBridge bridge =
                        new com.digero.maestro.noteedit.NoteEditorApp.ProjectBridge(){
                            @Override public void save(com.digero.maestro.noteedit.NoteDocument.Kind kind,byte[] bytes)
                                    throws Exception {
                                if(abcSong!=current)throw new IOException("Project changed");
                                if(kind==com.digero.maestro.noteedit.NoteDocument.Kind.ABC)
                                    current.setNoteEditorAbc(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                                else current.setNoteEditorMidi(bytes);
                                setAbcSongModified(true);
                            }
                            @Override public void changed(com.digero.maestro.noteedit.NoteDocument.Kind kind,
                                    com.digero.maestro.noteedit.NoteDocument document){
                                if(abcSong!=current)return;
                                if(kind==com.digero.maestro.noteedit.NoteDocument.Kind.ABC){
                                    // Do not preserve an obsolete rendered ABC snapshot:
                                    // the MSX's lossless note deltas are authoritative.
                                    current.setNoteEditorAbc(null);
                                    current.setNoteEditorDeltas(
                                        com.digero.maestro.noteedit.NoteEditorDelta.capturePreservingMissing(
                                            document,current.getNoteEditorDeltas()));
                                    // When the first note is placed in a blank composition,
                                    // switch from the non-playable starter source to the
                                    // real ABC preview transport. Without this, Play acts
                                    // on the short empty source and immediately stops.
                                    if (current.isCompositionMode() && current.hasAuthoredPartNotes()
                                            && !abcPreviewMode) {
                                        rollSourceAbc = true;
                                        updatePreviewMode(true, false);
                                    }
                                    rollLivePreviewTimer.restart();
                                }
                                setAbcSongModified(true);
                            }
                        };
                    if(midiRollEditor==null && files[0]!=null && files[0].isFile() &&
                        !files[0].getName().toLowerCase(java.util.Locale.ROOT).endsWith(".abc")){
                        midiRollEditor=new com.digero.maestro.noteedit.NoteEditorApp.EditorTab(
                            com.digero.maestro.noteedit.NoteDocument.Kind.MIDI,ProjectFrame.this,bridge);
                        midiRollEditor.load(files[0]);
                        midiRollEditor.useEmbeddedMode();
                        midiRollEditor.setHighlightKey(current.getHighlightKey());
                        midiRollEditor.setOnHighlightKeyChanged(ProjectFrame.this::changeRollHighlightKey);
                        midiRollEditor.setSharedTransport(makeRollTransport(false));
                        midiRollEditor.setMasterAudioControls(ProjectFrame.this::getVolume,ProjectFrame.this::getPan);
                        rollModeHost.add(midiRollEditor,"midiRoll");
                    }
                    if(abcRollEditor==null){
                        abcRollEditor=new com.digero.maestro.noteedit.NoteEditorApp.EditorTab(
                            com.digero.maestro.noteedit.NoteDocument.Kind.ABC,ProjectFrame.this,bridge);
                        abcRollEditor.load(files[1]);
                        // This is a project-owned ABC editor, not the standalone
                        // ABC file editor. Persist lossless deltas, including
                        // overlapping unisons, instead of serializing ABC on
                        // every gesture (which can be lossy or unsupported).
                        abcRollEditor.useEmbeddedMode();
                        abcRollEditor.setHighlightKey(current.getHighlightKey());
                        abcRollEditor.setOnHighlightKeyChanged(ProjectFrame.this::changeRollHighlightKey);
                        if(current.isCompositionMode())
                            abcRollEditor.setMinimumMeasures(current.getInitialCompositionMeasures());
                        abcRollEditor.useSongPartsSelection();
                        abcRollEditor.setFollowSupplier(arrangementView::isFollowEnabled);
                        abcRollEditor.setSharedTransport(makeRollTransport(true));
                        abcRollEditor.setMasterAudioControls(ProjectFrame.this::getVolume,ProjectFrame.this::getPan);
                        abcRollEditor.setOnScopeChanged(ProjectFrame.this::applyRollPlaybackScope);
                        if(!current.getNoteEditorDeltas().isEmpty())
                            abcRollEditor.reloadGeneratedAbc(Files.readString(files[1].toPath()),current.getNoteEditorDeltas());
                        rollModeHost.add(abcRollEditor,"abcRoll");
                    }
                    updateRollNoteClickShortcuts();
                    if(midiRollEditor!=null)midiRollEditor.setFollowSupplier(arrangementView::isFollowEnabled);
                    AbcPart selectedPart=songPartsListPanel.getSelectedPart();
                    if(abcRollEditor!=null && selectedPart!=null)
                        selectExactRollPart(selectedPart);
                    syncRollMuteSolo();
                    // The mode selection can be temporarily changed by a
                    // queued UI refresh while a blank composition has no
                    // previewable notes. The actual editing state is rollActive,
                    // not the radio button's transient selection.
                    if(rollActive) {
                        pianoRollModeRadioButton.setSelected(true);
                        rollModeCards.show(rollModeHost,desiredAbc?"abcRoll":
                            (midiRollEditor==null?"abcRoll":"midiRoll"));
                        com.digero.maestro.noteedit.NoteEditorApp.EditorTab chosen =
                            desiredAbc || midiRollEditor==null ? abcRollEditor:midiRollEditor;
                        chosen.fitView();chosen.resumeEmbeddedView();
                        applyRollPlaybackScope();
                        requestRollBaselineRefresh();}
                }catch(Exception ex){
                    pianoRollModeRadioButton.setSelected(false);
                    leavePianoRoll();
                    JOptionPane.showMessageDialog(ProjectFrame.this,"Piano roll: "+ex.getMessage(),
                        "Note editor",JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** New ABC is a native project editing mode, not an imported or dummy MIDI. */
    private void createBlankComposition() {
        JTextField songTitle = new JTextField("Untitled Composition",24);
        JTextField composer = new JTextField("",24);
        JSpinner tempo = new JSpinner(new SpinnerNumberModel(120,20,400,1));
        JSpinner measures = new JSpinner(new SpinnerNumberModel(32,1,2000,1));
        JComboBox<String> meter = new JComboBox<>(new String[]{"4/4","3/4","2/4","6/8","5/4","7/8","9/8","12/8"});
        JComboBox<String> key = new JComboBox<>(new String[]{"C","G","D","A","E","F","Bb","Eb","Am","Em","Bm","Dm","Gm"});
        JComboBox<LotroInstrument> instrument = new JComboBox<>(LotroInstrument.values());
        instrument.setSelectedItem(LotroInstrument.LUTE_OF_AGES);
        JPanel fields = new JPanel(new GridLayout(0,2,8,6));
        fields.add(new JLabel("Song title:"));fields.add(songTitle);
        fields.add(new JLabel("Composer:"));fields.add(composer);
        fields.add(new JLabel("Tempo (BPM):"));fields.add(tempo);
        fields.add(new JLabel("Time signature:"));fields.add(meter);
        fields.add(new JLabel("Key:"));fields.add(key);
        fields.add(new JLabel("Initial measures:"));fields.add(measures);
        fields.add(new JLabel("First LOTRO instrument:"));fields.add(instrument);
        if(JOptionPane.showConfirmDialog(this,fields,"New ABC Composition",
                JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION)return;
        String name=songTitle.getText().strip();
        if(name.isEmpty())name="Untitled Composition";
        String time=(String)meter.getSelectedItem(), tonality=(String)key.getSelectedItem();
        LotroInstrument voice=(LotroInstrument)instrument.getSelectedItem();
        int bpm=(Integer)tempo.getValue(), bars=(Integer)measures.getValue();
        File scaffold=null;
        try {
            String source=com.digero.maestro.noteedit.CompositionAbc.starter(
                name,bpm,time,tonality,voice.friendlyName,bars);
            scaffold=Files.createTempFile("maestro-new-abc-", ".abc").toFile();
            Files.writeString(scaffold.toPath(),source);
            // Reuse the normal project-loading UI; do not add a transient ABC
            // scaffold to Recently Opened or persist its temporary file path.
            openFile(scaffold,false);
            if(abcSong==null || !abcSong.getSourceFile().equals(scaffold))return;
            abcSong.initializeComposition(name,source,bars);
            abcSong.setTitle(name);
            abcSong.setComposer(composer.getText().strip());
            abcSong.setTempoBPM(bpm);
            abcSong.setTimeSignature(new TimeSignature(time));
            abcSong.setKeySignature(new KeySignature(tonality));
            AbcPart first=abcSong.getParts().isEmpty()?abcSong.createNewPart():abcSong.getParts().get(0);
            first.setInstrument(voice);
            first.setTitle(voice.friendlyName);
            songPartsListPanel.selectPart(0);
            setAbcSongModified(true);
            // New parts are editable even before they have any source track.
            abcModeRadioButton.setSelected(true);
            updatePreviewMode(true,false);
            pianoRollModeRadioButton.setSelected(true);
            enterPianoRoll();
        } catch(Exception ex) {
            log.log(Level.WARNING,"Unable to start blank ABC composition",ex);
            JOptionPane.showMessageDialog(this,"New composition failed: " + ex.getMessage(),
                "New ABC",JOptionPane.ERROR_MESSAGE);
        } finally {
            if(scaffold!=null)try { Files.deleteIfExists(scaffold.toPath()); }
                catch(IOException cleanup) {scaffold.deleteOnExit();}
        }
    }

    /** Legacy modeless editor retained for upstream compatibility. */
    private void openNoteEditor() {
        if (noteEditorOpening) return;
        if (noteEditorFrame != null && noteEditorFrame.isDisplayable()) {
            noteEditorFrame.toFront();
            noteEditorFrame.requestFocus();
            return;
        }
        if (abcSong == null) {
            registerNoteEditor(com.digero.maestro.noteedit.NoteEditorApp.openWindow());
            return;
        }
        commitAllFields();
        noteEditorOpening = true;
        final AbcSong currentSong = abcSong;
        final File sourceFile = currentSong.getSourceFile();
        final File existingExport = currentSong.getExportFile();
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        new SwingWorker<File[], Void>() {
            @Override protected File[] doInBackground() throws Exception {
                Path folder = Files.createTempDirectory("maestro-note-editor-");
                folder.toFile().deleteOnExit();
                File source = sourceFile;
                byte[] embeddedMidi = currentSong.getNoteEditorMidi();
                if (embeddedMidi != null) {
                    source = folder.resolve("project-edited-source.mid").toFile();
                    Files.write(source.toPath(), embeddedMidi);
                    source.deleteOnExit();
                }
                File abc = folder.resolve("arrangement.abc").toFile();
                abc.deleteOnExit();
                String embeddedAbc = currentSong.getNoteEditorAbc();
                if (embeddedAbc != null) {
                    Files.writeString(abc.toPath(), embeddedAbc);
                } else {
                    currentSong.exportAbc(abc, MaestroMain.APP_NAME);
                }
                return new File[]{source, abc};
            }
            @Override protected void done() {
                noteEditorOpening = false;
                setCursor(Cursor.getDefaultCursor());
                if (!isDisplayable() || abcSong != currentSong ||
                        (noteEditModeMenuItem != null && !noteEditModeMenuItem.isSelected())) {
                    if (noteEditModeMenuItem != null) noteEditModeMenuItem.setSelected(false);
                    return;
                }
                try {
                    File[] inputs = get();
                    registerNoteEditor(com.digero.maestro.noteedit.NoteEditorApp.openWindow(inputs[0], inputs[1],
                            (kind, bytes) -> {
                                if (abcSong != currentSong)
                                    throw new IOException("The project has changed. Reopen the editor for the new project.");
                                if (kind == com.digero.maestro.noteedit.NoteDocument.Kind.ABC)
                                    currentSong.setNoteEditorAbc(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                                else currentSong.setNoteEditorMidi(bytes);
                                setAbcSongModified(true);
                                if (!save()) throw new IOException("The MSX project could not be saved.");
                            }));
                } catch (Exception ex) {
                    if (noteEditModeMenuItem != null) noteEditModeMenuItem.setSelected(false);
                    File fallback = existingExport != null && existingExport.isFile() ? existingExport : null;
                    JOptionPane.showMessageDialog(ProjectFrame.this,
                        "Could not prepare the note editor arrangement snapshot.\n" + ex.getMessage() +
                        "\nYou can still open the standalone editor from its JAR.",
                        "Note editor", JOptionPane.WARNING_MESSAGE);
                }
            }
        }.execute();
    }

    private void registerNoteEditor(JFrame editor) {
        noteEditorFrame = editor;
        if (noteEditModeMenuItem != null) noteEditModeMenuItem.setSelected(true);
        editor.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                if (noteEditorFrame == editor) noteEditorFrame = null;
                if (noteEditModeMenuItem != null) noteEditModeMenuItem.setSelected(false);
            }
        });
    }

	private boolean exportAbcAs() {
		songExportSettingsPanel.setExportSuccessfulLabelVisible(false);

		if (abcSong == null) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.no.abc.song.is.open"), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}

        if (!confirmExportDespiteWarnings()) {
            return false;
        }
		
		File exportFile = getAbcExportFile();
		File allowOverwriteFile = allowOverwriteExportFile ? abcSong.getExportFile() : null;

		exportFile = doSaveDialog(exportFile, allowOverwriteFile, Util.ABC_FILE_EXTENSION,
				new ExtensionFileFilter(UIText.get("maestro.abc.files.abc.txt"), Util.ABC_FILE_EXTENSION_NO_DOT, Util.TXT_FILE_EXTENSION_NO_DOT));

		if (exportFile == null) {
			return false;
		}

		prefs.put("exportDialogFolder", exportFile.getAbsoluteFile().getParent());

		abcSong.setExportFile(exportFile);
		allowOverwriteExportFile = true;
		return finishExportAbc(exportFile);
	}

	private boolean shouldExportAbcAs() {
		boolean regeneratedFilenameIsDifferent =
				abcSong != null && abcSong.getExportFile() != null &&
						exportFilenameTemplate.shouldRegenerateFilename() &&
						!(StringCleaner.cleanForFileName(stripExtension(exportFilenameTemplate.formatName(abcSong)))
								+ Util.ABC_FILE_EXTENSION).equals(abcSong.getExportFile().getName());

        File exportFile = abcSong == null ? null : abcSong.getExportFile();

		return saveSettings.showExportFileChooser || !allowOverwriteExportFile || exportFile == null
				|| !exportFile.exists() || regeneratedFilenameIsDifferent;
	}

	private boolean exportAbc() {
		songExportSettingsPanel.setExportSuccessfulLabelVisible(false);
		if (abcSong == null) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.no.abc.song.is.open"), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}

		if (shouldExportAbcAs()) {
            return exportAbcAs();
        } else if (!confirmExportDespiteWarnings()) {
            return false;
        }

		return finishExportAbc(abcSong.getExportFile());
	}

    private boolean confirmExportDespiteWarnings() {
        List<String> warnings = abcSong.getExportWarnings(histogram);
        for(String warning : warnings) {
            int option = JOptionPane.showConfirmDialog(this, UIText.get("maestro.0.do.you.want.to.proceed.with.exporting.without.fixing.it", warning), UIText.get("maestro.warning"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (option != JOptionPane.YES_OPTION) {
                return false;
            }
        }
        return true;
    }

	private boolean finishExportAbc(File exportFile) {
        setUIEnabled(false);
		songExportSettingsPanel.setExportSuccessfulLabelVisible(false);
		commitAllFields();
        StringCleaner.cleanABC = saveSettings.convertABCStringsToBasicAscii;

        AbcExportWorker worker = new AbcExportWorker(exportFile);
        worker.execute();
        return true;
        /*
		try {

			abcSong.exportAbc(exportFile, MaestroMain.APP_NAME);

			SwingUtilities.invokeLater(() -> {
				exportSuccessfulLabel.setText(abcSong.getExportFile().getName());
				exportSuccessfulLabel.setToolTipText("Exported " + abcSong.getExportFile().getName());
				exportSuccessfulLabel.setVisible(true);
				if (exportLabelHideTimer == null) {
					exportLabelHideTimer = new Timer(8000, e -> exportSuccessfulLabel.setVisible(false));
					exportLabelHideTimer.setRepeats(false);
				}
				exportLabelHideTimer.stop();
				exportLabelHideTimer.start();
				onSaveAndExportSettingsChanged();
			});
			return true;
		} catch (FileNotFoundException e) {
			JOptionPane.showMessageDialog(this, "Failed to create file!\n" + e.getMessage(), "Failed to create file",
					JOptionPane.ERROR_MESSAGE);
			return false;
		} catch (IOException | AbcConversionException e) {
			JOptionPane.showMessageDialog(this, e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
			return false;
		}
        */
	}

    private class AbcExportWorker extends SwingWorker<Void, Void> {

        private final File exportFile;

        public AbcExportWorker(File exportFile) {
            this.exportFile = exportFile;
        }

        /**
         * This runs on non-Swing thread
         */
        @Override
        protected Void doInBackground() throws IOException, AbcConversionException {
            abcSong.exportAbc(exportFile, MaestroMain.APP_NAME);
            return null;
        }

        /**
         * This runs on Swing thread
         */
        @Override
        protected void done() {
            try {
                get(); // get exceptions from doInBackground()

                songExportSettingsPanel.setExportSuccessfulLabelText(abcSong.getExportFile().getName());
                songExportSettingsPanel.setExportSuccessfulLabelToolTipText(UIText.get("maestro.exported.0", abcSong.getExportFile().getName()));
                songExportSettingsPanel.setExportSuccessfulLabelVisible(true);

                if (exportLabelHideTimer == null) {
                    exportLabelHideTimer = new Timer(8000, e -> songExportSettingsPanel.setExportSuccessfulLabelVisible(false));
                    exportLabelHideTimer.setRepeats(false);
                }
                exportLabelHideTimer.stop();
                exportLabelHideTimer.start();
                onSaveAndExportSettingsChanged();
            } catch (CancellationException ignored) {
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.log(Level.WARNING, "Error when exporting ABC", cause);
                if (cause instanceof FileNotFoundException) {
                    JOptionPane.showMessageDialog(ProjectFrame.this, UIText.get("maestro.failed.to.create.file.0", cause.getMessage()),
							UIText.get("maestro.failed.to.create.file"), JOptionPane.ERROR_MESSAGE);
                } else if (cause instanceof IOException || cause instanceof AbcConversionException) {
                    JOptionPane.showMessageDialog(ProjectFrame.this, cause.getMessage(), UIText.get("maestro.error"),
                            JOptionPane.ERROR_MESSAGE);
                } else {
                    // Catch any other unexpected errors
                    JOptionPane.showMessageDialog(ProjectFrame.this, UIText.get("maestro.an.unexpected.error.occurred.0", cause.getMessage()),
							UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
                }

            } catch (Exception e) {
                log.log(Level.SEVERE, "Error exporting ABC", e);
                JOptionPane.showMessageDialog(ProjectFrame.this, UIText.get("maestro.an.unexpected.ui.error.occurred.0", e.getMessage()),
						UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
            } finally {
                setUIEnabled(true);
            }
        }
    }

    private final MouseAdapter blocker = new MouseAdapter() {};

    /**
     * A setEnabled for changing source
     */
    private void setSourceChangeEnabled(boolean on) {
        sourceChangeEnabled = on;
        scheduleUiRefresh();
    }

    /**
     * A setEnabled for the entire Maestro App.
     */
    private void setUIEnabled(boolean on) {
        uiEnabled = on;
        Component glassPane = getGlassPane();
        if (!on) {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            glassPane.addMouseListener(blocker);
            glassPane.setVisible(true);
        } else {
            glassPane.setVisible(false);
            glassPane.removeMouseListener(blocker);
            setCursor(Cursor.getDefaultCursor());
        }

        // Disable the dialogs also,
        // as any changes from them can bring multi-threading trouble:
        partEditor.uiEnabled(on);
        SectionEditor.uiEnabled(on);
        TuneEditor.uiEnabled(on);
		themeUiEnabled(on);
    }

	private boolean saveAs() {
		if (abcSong == null) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.no.abc.song.is.open"), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}

		File saveFile = abcSong.getProjectFile();
		File allowOverwriteFile = allowOverwriteSaveFile ? saveFile : null;

		String defaultFolder;
		if (abcSong.getExportFile() != null)
			defaultFolder = abcSong.getExportFile().getAbsoluteFile().getParent();
		else
			defaultFolder = Util.getLotroMusicPath(false).getAbsolutePath();

		String folder = prefs.get("saveDialogFolder", defaultFolder);
		if (saveFile != null)
			folder = saveFile.getAbsoluteFile().getParent();
		if (!new File(folder).exists())
			folder = defaultFolder;

		String fileName = "mySong";

		// Always regenerate setting from pattern export is highest precedent
		if (exportFilenameTemplate.shouldRegenerateFilename()) {
			fileName = stripExtension(exportFilenameTemplate.formatName(abcSong));
		} else if (saveFile != null) // else use MSX file if exists already
		{
			fileName = stripExtension(saveFile.getName());
		} else if (abcSong.getExportFile() != null) // else use ABC filename if exists
		{
			fileName = stripExtension(abcSong.getExportFile().getName());
		} else if (exportFilenameTemplate.isEnabled()) // else use pattern if enabled
		{
			fileName = stripExtension(exportFilenameTemplate.formatName(abcSong));
		} else if (abcSong.getSourceFile() != null) // else use source (midi/abc) file
		{
			fileName = stripExtension(abcSong.getSourceFilename());
		}
		// Template names come from song metadata and can contain '/', ':' etc. Names from
		// existing files are already valid, so this is a no-op for them.
		fileName = StringCleaner.cleanForProjectFileName(fileName);
		fileName += Util.MSX_FILE_EXTENSION;

		saveFile = new File(folder, fileName);

		saveFile = doSaveDialog(saveFile, allowOverwriteFile, Util.MSX_FILE_EXTENSION,
				new ExtensionFileFilter(AbcSong.MSX_FILE_DESCRIPTION_PLURAL + " (*" + Util.MSX_FILE_EXTENSION + ")",
						Util.MSX_FILE_EXTENSION_NO_DOT));

		if (saveFile == null)
			return false;

		prefs.put("saveDialogFolder", saveFile.getAbsoluteFile().getParent());
		abcSong.setProjectFile(saveFile);
		allowOverwriteSaveFile = true;
		return finishSave();
	}

	private boolean save() {
		if (abcSong == null) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.no.abc.song.is.open"), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}

		if (!allowOverwriteSaveFile || abcSong.getProjectFile() == null || !abcSong.getProjectFile().exists()) {
			return saveAs();
		}

		return finishSave();
	}
	
	private boolean finishSave() {
		return finishSave(true);
	}

	private boolean finishSave(boolean updateRecentlyOpenedFiles) {
        try{stageRollEdits();}
        catch(RuntimeException ex){JOptionPane.showMessageDialog(this,ex.getMessage(),"Piano roll save",JOptionPane.ERROR_MESSAGE);return false;}
		commitAllFields();

		try {
			XmlUtil.saveDocument(abcSong.saveToXml(), abcSong.getProjectFile());
		} catch (FileNotFoundException e) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.failed.to.create.file.0", e.getMessage()), UIText.get("maestro.failed.to.create.file"),
					JOptionPane.ERROR_MESSAGE);

			return false;
		} catch (IOException | TransformerException e) {
			JOptionPane.showMessageDialog(this, e.getMessage(), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}
		
		if (updateRecentlyOpenedFiles) {
			recentlyOpenedList.addOpenedFile(abcSong.getProjectFile());
			updateOpenRecentMenu();
		}

		setAbcSongModified(false);
		return true;
	}

	private boolean expandMidi() {
		if (abcSong == null || abcSong.getSourceFile() == null) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.no.midi.loaded"), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}

		if (abcSong.getSequenceInfo().standard == MidiStandard.ABC) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.cannot.expand.abc.song"), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}

		if (abcSong.getSourceFile().getName().startsWith("expanded_")) {
			JOptionPane.showMessageDialog(this, UIText.get("maestro.this.midi.has.already.been.expanded"), UIText.get("maestro.error"),
					JOptionPane.ERROR_MESSAGE);
			return false;
		}

		File saveFile = null;

        String defaultFolder;

        defaultFolder = Util.getLotroMusicPath(false).getAbsolutePath();

        String folder = prefs.get("saveDialogFolder", defaultFolder);
        if (!new File(folder).exists())
            folder = defaultFolder;

        saveFile = abcSong.getSourceFile();
        String fileName = "expanded_" + saveFile.getName();
        Path path = Paths.get(saveFile.getAbsolutePath());
        String directory = path.getParent().toString();

        int dot = fileName.lastIndexOf('.');
        if (dot > 0)
            fileName = fileName.substring(0, dot);
        fileName += Util.MID_FILE_EXTENSION;

        saveFile = new File(directory, fileName);

        saveFile = doSaveDialog(saveFile, saveFile, Util.MID_FILE_EXTENSION, new ExtensionFileFilter(UIText.get("maestro.midi.songs.mid"), Util.MID_FILE_EXTENSION_NO_DOT));

		if (saveFile == null)
			return false;

		return finishExpand(saveFile);
	}

	private boolean finishExpand(File saveFile) {
		try {
			Sequence sequence2 = abcSong.getSequenceInfo().split();
			if (sequence2 == null) {
				JOptionPane.showMessageDialog(this, UIText.get("maestro.something.went.wrong.in.the.splitting.process"), UIText.get("maestro.error"),
						JOptionPane.ERROR_MESSAGE);
				return false;
			}
			int[] types = MidiSystem.getMidiFileTypes(sequence2);
			if (types.length != 0) {
				log.info("Writing type " + types[types.length - 1] + " expanded midi as '"
						+ saveFile.getAbsolutePath() + "'");
				MidiSystem.write(sequence2, types[types.length - 1], saveFile);
			} else {
				JOptionPane.showMessageDialog(this, UIText.get("maestro.something.went.wrong.when.in.midi.type.handling"), UIText.get("maestro.error"),
						JOptionPane.ERROR_MESSAGE);
				return false;
			}
		} catch (FileNotFoundException e) {
			log.log(Level.WARNING, "Failed to create expanded midi file", e);
			JOptionPane.showMessageDialog(this, UIText.get("maestro.failed.to.create.file.0", e.getMessage()), UIText.get("maestro.failed.to.create.file"),
					JOptionPane.ERROR_MESSAGE);

			return false;
		} catch (InvalidMidiDataException | IOException | FileParseException e) {
			log.log(Level.SEVERE, "Failed to export expanded midi file", e);
			JOptionPane.showMessageDialog(this, e.getMessage(), UIText.get("maestro.error"), JOptionPane.ERROR_MESSAGE);
			return false;
		}

		int result = JOptionPane.showConfirmDialog(this, UIText.get("maestro.would.you.also.like.to.load.the.new.expanded.midi"),
				UIText.get("maestro.expanded.midi"), JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);

		switch (result) {
		case JOptionPane.YES_OPTION:
			openFile(saveFile);
			break;
		case JOptionPane.NO_OPTION:
		case JOptionPane.CANCEL_OPTION, JOptionPane.CLOSED_OPTION:
			break;
		}

		return true;
	}

	/**
	 * 
	 * Will output what all threads are doing.
	 * 
	 * @return A string ready to be printed out
	 */
	@SuppressWarnings("unused")
	private static String threadDump(boolean lockedMonitors, boolean lockedSynchronizers) {
		StringBuilder threadDump = new StringBuilder(System.lineSeparator());
		ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
		for (ThreadInfo threadInfo : threadMXBean.dumpAllThreads(lockedMonitors, lockedSynchronizers)) {
			threadDump.append(threadInfo.toString());
		}
		return threadDump.toString();
	}

	/**
	 * Checks if there is a new version of Maestro available on github, and if so, prompts the user to download it.
	 * To avoid spamming github with requests, it only does this check if there isn't already a check in progress, or if the previous check is finished.
	 */
    private void checkVersionCompare() {
        if (future == null || future.isDone()) {
            future = CompletableFuture.runAsync(() -> {
                try {
                    try (var client = HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(4))
                            .build()) {

                        var request = HttpRequest.newBuilder()
                                .uri(new URI("https://raw.githubusercontent.com/NikolaiVChr/mver/refs/heads/main/main"))
                                .timeout(Duration.ofSeconds(10))
                                .GET()
                                .build();

                        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

                        if (response.statusCode() == HttpURLConnection.HTTP_OK) {
                            String line = response.body().lines().findFirst().orElse("");
                            latestVer = Version.parseVersion(line);
                            Version myVersion = MaestroMain.APP_VERSION;
                            if (latestVer != null && myVersion.compareTo(latestVer) < 0) {
                                SwingUtilities.invokeLater(() -> {
                                        int result = JOptionPane.showConfirmDialog(ProjectFrame.this, UIText.get("maestro.version.0.is.available.do.you.want.to.close.and.download.it", latestVer), UIText.get("maestro.version.check"),
                                                JOptionPane.YES_NO_OPTION);
                                        if (result == JOptionPane.YES_OPTION) {
                                            URI uriDownload;
                                            try {
                                                uriDownload = new URI(MaestroMain.DOWNLOAD_URL);
                                                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
													// Attempt to open the download URL in the user's default browser
													// If the project can be closed successfully, open the browser and exit the application
                                                    if (closeProjectForShutdown()) {
                                                        Desktop.getDesktop().browse(uriDownload);
                                                        System.exit(0);
                                                    }
                                                }
                                            } catch (URISyntaxException | IOException e) {
                                                log.log(Level.WARNING, "Failed to open browser", e);
                                            }
                                        }
                                    }
                                );
                            }
                        } else {
                            log.warning("Failed to read current version string from HTTPS. Response: " + response.statusCode());
                        }
                    }
                } catch (Exception e) {
                    log.log(Level.WARNING, "Failed to connect to github for version check", e);
                }
            });
        }
    }
}
