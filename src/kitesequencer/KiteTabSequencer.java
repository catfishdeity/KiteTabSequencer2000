package kitesequencer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.IntStream;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Box;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import kitesequencer.events.ControlEvent;
import kitesequencer.events.ControlEventType;
import kitesequencer.events.NilControlEvent;
import kitesequencer.events.StickyNote;
import kitesequencer.events.TempoEvent;
import kitesequencer.events.TimeSignatureDenominator;
import kitesequencer.events.TimeSignatureEvent;


public class KiteTabSequencer {
	
	public static void main(String[] args) {
		KiteTabSequencer.getInstance(); 
	}
	
	public static Font font = new Font("Monospaced",Font.BOLD,14);
	public static FontMetrics fontMetrics = new Canvas().getFontMetrics(font);
	final double middleC = 220.0 * Math.pow(2d, 3.0/12.0);
	public static int cellWidth = fontMetrics.stringWidth("88")+3;
	public static int rowHeight = fontMetrics.getMaxAscent()+2;
	
	private static KiteTabSequencer instance;
	
	public static int scrollTimeMargin = 8;
	
	private PlaylistInputMode inputMode = PlaylistInputMode.NOTE_EDIT;
	
	final AtomicBoolean isPlaying = new AtomicBoolean(false);
	final AtomicInteger playT = new AtomicInteger(0);
	final AtomicInteger viewT0 = new AtomicInteger(-scrollTimeMargin);
	final AtomicInteger cursorT = new AtomicInteger(0);
	final AtomicInteger stopT0 = new AtomicInteger(0);
	final AtomicInteger repeatT = new AtomicInteger(-1);
	final AtomicInteger tempo = new AtomicInteger(120);
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);
		 
	final TreeMap<Integer,Integer> cachedMeasurePositions = new TreeMap<>();
	
	Synthesizer synth = null;
	Synthesizer synth2 = null;
	
	Map<KiteTabCanvas<?>,Map<Integer,SynthChannelCombo>> channelMappingMap =
			new HashMap<>();
	
	final ScheduledExecutorService playbackDaemon = Executors.newSingleThreadScheduledExecutor();
	
	//Map<MidiChannel,Integer> activeBassNotes = new HashMap<>();
	//Map<MidiChannel,Integer> activeAcousticNotes = new HashMap<>();
	//Map<MidiChannel,Integer> activeGuitarNotes = new HashMap<>();
	//final Map<Integer,InputVal> openBassNotes = new ConcurrentHashMap<>();
	//final Map<Integer,InputVal> openAcousticNotes = new ConcurrentHashMap<>();
	//final Map<Integer,InputVal> openGuitarNotes = new ConcurrentHashMap<>();
	final Map<Integer,DrumVal> openDrumNotes = new ConcurrentHashMap<>();
	
	final JFrame frame = new JFrame("Kite Tab Sequencer 2000");
	LeftClickablePanelButton playButton, stopButton;
	PlayStatusPanel playStatusPanel = new PlayStatusPanel();
	
	EventCanvas eventCanvas = new EventCanvas();
	
	TabCanvas bassCanvas = new TabCanvas(6,"Bass",
			IntStream.of(5,4,3,2,1,0).mapToDouble(i -> 
			middleC * 0.25 * Math.pow(2,(i*13d)/41d)).toArray());
	TabCanvas acousticCanvas = new TabCanvas(6,"AcousticG",
			IntStream.of(6,5,4,3,2,1).mapToDouble(i -> 
			middleC * 0.5 *Math.pow(2,(i*13d)/41d)).toArray());
	
	TabCanvas guitarCanvas = new TabCanvas(7,"ElectricG",
			IntStream.of(6,5,4,3,2,1,0).mapToDouble(i -> 
			middleC * 0.5 *Math.pow(2,(i*13d)/41d)).toArray());
	
	DrumTabCanvas drumCanvas = new DrumTabCanvas();
	NavigationCanvas navigationBar = new NavigationCanvas();
	AtomicReference<KiteTabCanvas<?>> selectedCanvas = new AtomicReference<>();
	{
		Arrays.asList(eventCanvas,acousticCanvas,bassCanvas,guitarCanvas,drumCanvas).forEach(a->navigationBar.addComponentToRepaint(a));
		selectedCanvas.set(eventCanvas);
		
	}
	
	void playbackDaemonFunction() {
		//DO NOT CALL THIS ON MASTER THREAD
		while (true) {
			if (!isPlaying.get()) {
				continue;
			}
			else {
				int t = playT.getAndIncrement();
				Arrays.asList(eventCanvas,bassCanvas,guitarCanvas,acousticCanvas,drumCanvas).forEach(a->a.repaint());
				
				eventCanvas.data.getOrDefault(t,Collections.emptyMap()).entrySet().forEach(entry -> {
					switch (entry.getValue().getType()) {
					case NIL:
						break;
					case TEMPO:
						tempo.set(((TempoEvent) entry.getValue()).getTempo());
						break;
					case TIME_SIGNATURE:
						break;
					default:
						break;
					
					}
				}); 
				//Map<Integer, InputVal> bassMap = bassCanvas.data.getOrDefault(t, Collections.emptyMap());
				for (TabCanvas canvas : new TabCanvas[] {bassCanvas,acousticCanvas,guitarCanvas}) {
					for (int row = 0 ; row < canvas.getRowCount(); row++) {
						InputVal inputVal = canvas.getValueAt(t,row).orElse(InputVal.NIL);
						if (inputVal == InputVal.HOLD || inputVal == InputVal.VIB) {
							//do nothing, midi event keeps going				 
						} else if (inputVal == InputVal.NIL) {
							canvas.noteOff(row);						
						} else if (inputVal.getEdoSteps().isPresent()) {
							canvas.noteOn(row,inputVal);							
							}
						}
				}

				Map<Integer, DrumVal> drumMap = drumCanvas.data.getOrDefault(playT.get(), Collections.emptyMap());
				for (int row = 0; row < drumCanvas.getRowCount(); row++) {
					DrumVal inputVal = drumMap.getOrDefault(row, DrumVal.NIL);
					
					if (openDrumNotes.containsKey(row)) {
						//send close midi message
						openDrumNotes.remove(row);
						processDrumInput(null);
					}
					if (inputVal != DrumVal.NIL) {
						openDrumNotes.put(row, inputVal);						
						processDrumInput(inputVal);
					}
				}
				
				long bpm = tempo.get();
				Duration sixteenth = Duration.ofMinutes(1).dividedBy(bpm).dividedBy(4);  
				playbackDaemon.schedule(()->playbackDaemonFunction(),
						sixteenth.toMillis(),TimeUnit.MILLISECONDS);
				playT.updateAndGet(i->i+1==repeatT.get()?stopT0.get():i+1);
				return;
			}
		}
	}
	
	void repaintCanvases() {
		Arrays.asList(navigationBar,guitarCanvas,acousticCanvas,drumCanvas,bassCanvas,eventCanvas)
		.forEach(a->a.repaint()); 
	}
	
	void adjustRepeatT() {
		repeatT.updateAndGet(i->i==cursorT.get()?-1:cursorT.get());
		repaintCanvases();
	}
	
	void setPlayT() {
		playT.set(cursorT.get());
		repaintCanvases();
	}
	
	void togglePlayStatus() {
		if (isPlaying.get()) {
			stopPlayback();
		} else {
			startPlayback();
		}		
	}
	
	void returnToStopT0() {
		
	}
	
	void startPlayback() {
		if (!playbackDaemonIsStarted.get()) {
			playbackDaemonIsStarted.set(true);
			playbackDaemon.schedule(()->playbackDaemonFunction(),0, TimeUnit.SECONDS);
		}
		isPlaying.set(true);
		playStatusPanel.setPlayStatus(PlayStatus.PLAY);
		playStatusPanel.repaint();
	}
		
	
	void stopPlayback() {		
		isPlaying.set(false);
		repaintCanvases();
		playStatusPanel.setPlayStatus(PlayStatus.STOP);
		playStatusPanel.repaint();	
	}
	
	
	void setStopT0() {
		stopT0.set(cursorT.get());
		repaintCanvases();
	}
	
	void toggleNoteSelectionMode() {
		System.out.println("toggle note selection mode");
	}
	
	public int getCursorT() {
		return cursorT.get();
	}
	
	public void setCursorT(int t) {
		cursorT.set(t);
	}
	public void incrementCursorT() {
		
		cursorT.getAndIncrement();
		if (cursorT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void decrementCursorT() {
		cursorT.set(Math.max(0, cursorT.get()-1));
		if (cursorT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void updateMeasureLinePositions() {
		
		AtomicReference<TimeSignatureEvent> timeSignature = new AtomicReference<>(new TimeSignatureEvent(4,TimeSignatureDenominator._4));
		AtomicInteger counter = new AtomicInteger(0);
		AtomicInteger measure = new AtomicInteger(1);
		Map<Integer,Integer> toReturn = new TreeMap<>();
		toReturn.put(0,measure.getAndIncrement());
		for (int t = 0; t < 1000*16; t++) {
			Optional<TimeSignatureEvent> tsO = 
					eventCanvas.data.getOrDefault(t,Collections.emptyMap()).values().stream()
					.filter(a->a.getType()==ControlEventType.TIME_SIGNATURE)
					.map(a->(TimeSignatureEvent) a)
					.findFirst();
			if (tsO.isPresent()) {				
				timeSignature.set(tsO.get());				
				counter.set(0);
				toReturn.put(t,measure.getAndIncrement());	
			}
			if (counter.get() == timeSignature.get().get16ths()) {
				counter.set(0);
				toReturn.put(t,measure.getAndIncrement());								
			}			
			counter.incrementAndGet();
		}
		cachedMeasurePositions.clear();
		cachedMeasurePositions.putAll(toReturn);
		//return toReturn.stream().sorted().toList();
	}
	
	public void backspace() {
		System.out.println("backspace");		
	}
	
	public void handleNumericInput(int i) {
		if (selectedCanvas.get() == eventCanvas) {
			
		} else if (selectedCanvas.get() instanceof TabCanvas) {
			TabCanvas canvas = (TabCanvas) selectedCanvas.get();
			Optional<InputVal> inputValO = canvas.getSelectedValue();
			if (inputValO.isPresent()) {
				inputValO.flatMap(a->InputVal.lookup(a.getToken()+i))
				.ifPresentOrElse(a->{
					canvas.setSelectedValue(a);
					canvas.repaint();
				},()->{
					InputVal.lookup(""+i).ifPresent(a -> {
						canvas.setSelectedValue(a);
						canvas.repaint();
					});
				});
						
			} else {
				InputVal.lookup(""+i).ifPresent(a -> {
					canvas.setSelectedValue(a);
					canvas.repaint();
				});
			}
		}
	}
	
	public void handleABCInput(char c) {
		if (selectedCanvas.get() == eventCanvas) {
			switch (c) {
			case 'T':
				addTimeSignature();
				return;
			case 'S':
				addTempo();
				return;
			case 'N':
				addStickyNote();
				return;
			default:
				
				break;
			}

		} else if (selectedCanvas.get() instanceof TabCanvas) {
			TabCanvas canvas = (TabCanvas) selectedCanvas.get();
			switch (c) {
			case 'B':
				canvas.setSelectedValue(InputVal._b);
				canvas.repaint();
				return;
			case 'D':
				canvas.setSelectedValue(InputVal._d);
				canvas.repaint();
				return;
			case 'E':
				canvas.setSelectedValue(InputVal._e);
				canvas.repaint();
				return;
			}
			
		} else if (selectedCanvas.get() instanceof DrumTabCanvas) {
			
			DrumTabCanvas canvas = (DrumTabCanvas) selectedCanvas.get();
			DrumVal.lookup(String.valueOf(c))
			.ifPresent(val -> {
				canvas.setSelectedValue(val);
				canvas.repaint();
			});
			
		}
	}
	
	public void cursorToStart() {
		cursorT.set(0);
		while (cursorT.get() < viewT0.get()) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	
	public void cursorToEnd() {
		//TODO finish this...will move the cursor to
		//the last input note
	}
	
	public void playTToPreviousMeasure() {
		NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(playT.get(), false);
		if (!headMap.isEmpty()) {
			Entry<Integer, Integer> last= headMap.lastEntry();			
			playT.set(last.getKey());			
		}
		while (playT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void playTToNextMeasure() {
		NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(playT.get(), false);
		if (!tailMap.isEmpty()) {
			Entry<Integer, Integer> next = tailMap.firstEntry();			
			playT.set(next.getKey());			
		}
		while (playT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void incrementPlayT() {
		playT.incrementAndGet();
		while (playT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void decrementPlayT() {
		playT.getAndUpdate(i->Math.max(0,i-1));
		while (playT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void shiftArrowUp() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(drumCanvas);
			drumCanvas.setSelectedRow(drumCanvas.getRowCount()-1);
			drumCanvas.repaint();
		} else {			
			
			Map<KiteTabCanvas<?>,KiteTabCanvas<?>> prevMaps = new HashMap<>();
			prevMaps.put(drumCanvas, acousticCanvas);
			prevMaps.put(acousticCanvas, guitarCanvas);
			prevMaps.put(guitarCanvas, bassCanvas);
			prevMaps.put(bassCanvas, eventCanvas);
			prevMaps.put(eventCanvas, drumCanvas);
			selectedCanvas.get().repaint();
			selectedCanvas.updateAndGet(prevMaps::get);
			selectedCanvas.get().setSelectedRow(selectedCanvas.get().getRowCount()-1);
			selectedCanvas.get().repaint();							
		}
		
	}
	
	public void shiftArrowDown() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(eventCanvas);
			eventCanvas.setSelectedRow(0);
			eventCanvas.repaint();
		} else {			
			Map<KiteTabCanvas<?>,KiteTabCanvas<?>> nextMaps = new HashMap<>();
			nextMaps.put(acousticCanvas,drumCanvas);
			nextMaps.put(guitarCanvas,acousticCanvas);
			nextMaps.put(bassCanvas,guitarCanvas);
			nextMaps.put(eventCanvas,bassCanvas);
			nextMaps.put(drumCanvas,eventCanvas);				
			selectedCanvas.get().repaint();
			selectedCanvas.updateAndGet(nextMaps::get);				
			selectedCanvas.get().setSelectedRow(0);
			selectedCanvas.get().repaint();					
		}	
	}
	
	public void arrowUp() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(drumCanvas);
			drumCanvas.setSelectedRow(3);
			drumCanvas.repaint();
		} else {			
			if (selectedCanvas.get().getSelectedRow() == 0) {
				Map<KiteTabCanvas<?>,KiteTabCanvas<?>> prevMaps = new HashMap<>();
				prevMaps.put(drumCanvas, acousticCanvas);
				prevMaps.put(acousticCanvas, guitarCanvas);
				prevMaps.put(guitarCanvas, bassCanvas);
				prevMaps.put(bassCanvas, eventCanvas);
				prevMaps.put(eventCanvas, drumCanvas);
				selectedCanvas.get().repaint();
				selectedCanvas.updateAndGet(prevMaps::get);
				selectedCanvas.get().setSelectedRow(selectedCanvas.get().getRowCount()-1);
				selectedCanvas.get().repaint();				
			} else {
				selectedCanvas.get().setSelectedRow(selectedCanvas.get().getSelectedRow()-1);
				selectedCanvas.get().repaint();
			}
		}
		
	}
	
	public void arrowDown() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(eventCanvas);
			eventCanvas.setSelectedRow(0);
			eventCanvas.repaint();
		} else {
			if (selectedCanvas.get().getSelectedRow() == selectedCanvas.get().getRowCount()-1) {
				Map<KiteTabCanvas<?>,KiteTabCanvas<?>> nextMaps = new HashMap<>();
				nextMaps.put(acousticCanvas,drumCanvas);
				nextMaps.put(guitarCanvas,acousticCanvas);
				nextMaps.put(bassCanvas,guitarCanvas);
				nextMaps.put(eventCanvas,bassCanvas);
				nextMaps.put(drumCanvas,eventCanvas);				
				selectedCanvas.get().repaint();
				selectedCanvas.updateAndGet(nextMaps::get);				
				selectedCanvas.get().setSelectedRow(0);
				selectedCanvas.get().repaint();
			} else {
				selectedCanvas.get().setSelectedRow(selectedCanvas.get().getSelectedRow()+1);
				selectedCanvas.get().repaint();
			}
		
		}
	}
	
	public void cursorTToNextMeasure() {
		
		NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(cursorT.get(), false);
		if (!tailMap.isEmpty()) {
			Entry<Integer, Integer> next = tailMap.firstEntry();			
			cursorT.set(next.getKey());			
		}
		while (cursorT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
		
	}
	
	public void cursorTToPrevMeasure() {
		NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(cursorT.get(), false);
		if (!headMap.isEmpty()) {
			Entry<Integer, Integer> last= headMap.lastEntry();			
			cursorT.set(last.getKey());			
		}
		while (cursorT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
		
	
	public static KiteTabSequencer getInstance() {
		if (instance == null) {
			instance = new KiteTabSequencer();
		}
		return instance;
	}
	
	private KiteTabSequencer() {
		initializeMIDI();
		createGui();		
	}
	
	
	void initializeMIDI() {
		try {		
			synth = MidiSystem.getSynthesizer();
			synth.open();
			synth2 = MidiSystem.getSynthesizer();
			synth2.open();			
			
			File sfFile = new File("C:/Program Files (x86)/Image-Line/FL Studio 2024/Data/Patches/Soundfonts/SM64SF V2.sf2");
			
			Soundbank soundbank = MidiSystem.getSoundbank(sfFile);
			synth.unloadAllInstruments(synth.getDefaultSoundbank());
			synth2.unloadAllInstruments(synth2.getDefaultSoundbank());
			synth.loadAllInstruments(soundbank);
			synth2.loadAllInstruments(soundbank);
			Instrument[] instruments = soundbank.getInstruments();
			for (int i = 0; i < instruments.length; i++) {
				System.out.printf("%d %s\n",i,instruments[i]);
			}
			Instrument drumInstrument = Arrays.asList(synth.getLoadedInstruments())
					.stream().filter(a-> a.toString().contains("Drumkit: Power"))
					.findFirst().get();
			Arrays.asList(guitarCanvas,bassCanvas,drumCanvas,acousticCanvas).forEach(i -> {
				channelMappingMap.put(i, new HashMap<>());
			});
			Arrays.asList(0,1,2,3,4,5,6).forEach(i ->{
				channelMappingMap.get(guitarCanvas).put(i, 
						new SynthChannelCombo(synth,i));
			});
			Arrays.asList(0,1,2,3,4,5).forEach(i ->{
				channelMappingMap.get(acousticCanvas).put(i, 
						new SynthChannelCombo(synth2,i));
			});
			channelMappingMap.get(bassCanvas).put(0, new SynthChannelCombo(synth,7));
			channelMappingMap.get(bassCanvas).put(1, new SynthChannelCombo(synth,8));
			channelMappingMap.get(bassCanvas).put(2, new SynthChannelCombo(synth,10));
			channelMappingMap.get(bassCanvas).put(3, new SynthChannelCombo(synth,11));
			channelMappingMap.get(bassCanvas).put(4, new SynthChannelCombo(synth,12));
			channelMappingMap.get(bassCanvas).put(5, new SynthChannelCombo(synth,13));
			
						
			
			channelMappingMap.get(drumCanvas).put(9, 
					new SynthChannelCombo(synth,9));

			int acousticInstrument = 24;
			int guitarInstrument = 30;
			int bassInstrument = 32;

			channelMappingMap.get(guitarCanvas).entrySet().forEach(e -> {
				e.getValue().getChannel().programChange(
						instruments[guitarInstrument].getPatch().getBank(),
						instruments[guitarInstrument].getPatch().getProgram());				
			});
			channelMappingMap.get(bassCanvas).entrySet().forEach(e -> {
				e.getValue().getChannel().programChange(
						instruments[bassInstrument].getPatch().getBank(),
						instruments[bassInstrument].getPatch().getProgram());				
			});
			
			channelMappingMap.get(acousticCanvas).entrySet().forEach(e -> {
				e.getValue().getChannel().programChange(
						instruments[acousticInstrument].getPatch().getBank(),
						instruments[acousticInstrument].getPatch().getProgram());				
			});
						
			channelMappingMap.get(drumCanvas).get(9).getChannel().programChange(
					drumInstrument.getPatch().getBank(),
					drumInstrument.getPatch().getProgram());
			
		} catch (Exception ex) {
			ex.printStackTrace();
			System.exit(1);
		}
	}
					


	Set<Integer> openDrumVals = new HashSet<>();
	int kick = 36;
	int snare = 38;
	int closedHat= 42;
	int crash = 49;
	int openHat = 46;
	int pedalHat = 44;
	int hiTom =48;
	int midTom = 47;
	int loTom = 41;
	int ride = 51;
	//https://musescore.org/sites/musescore.org/files/General%20MIDI%20Standard%20Percussion%20Set%20Key%20Map.pdf
	void processDrumInput(DrumVal drumVal) {
		
		MidiChannel drumChannel = channelMappingMap.get(drumCanvas).get(9).getChannel();
		//if drumVal is null, treat as note off
		
		if (drumVal == null) {
			openDrumVals.forEach(i->drumChannel.noteOff(i));
			openDrumVals.clear();
			return;
		}
		switch (drumVal) {
		case CLOSED_HAT:
			openDrumVals.add(closedHat);
			drumChannel.noteOn(closedHat, 100);
			break;
		case CRASH:
			openDrumVals.add(crash);
			drumChannel.noteOn(crash, 100);
			break;
		case HI_TOM:
			openDrumVals.add(hiTom);
			drumChannel.noteOn(hiTom, 100);
			break;
		case KICK:
			openDrumVals.add(kick);
			drumChannel.noteOn(kick, 100);

			break;
		case LOW_TOM:
			openDrumVals.add(loTom);
			drumChannel.noteOn(loTom, 100);
			break;
		case MID_TOM:
			openDrumVals.add(midTom);
			drumChannel.noteOn(midTom, 100);
			break;
		case NIL:
			break;
		case OPEN_HAT:
			openDrumVals.add(openHat);
			drumChannel.noteOn(openHat, 100);
			break;
		case PEDAL_HAT:
			openDrumVals.add(pedalHat);
			drumChannel.noteOn(pedalHat, 100);
			break;
		case RIDE:
			openDrumVals.add(ride);
			drumChannel.noteOn(ride, 100);
			break;
		case SNARE:
			openDrumVals.add(snare);
			drumChannel.noteOn(snare, 100);
			break;
		default:
			break;		
		}
	}
	
	abstract class KiteTabCanvas<A> extends JPanel {
				
		final boolean isSelected() {
			return this == selectedCanvas.get(); 
		}

		public final int getMaxVisibleTime() {
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			return t1;
		}
		
		protected final Map<Integer,Map<Integer,A>> data = new HashMap<>();
		
				
		public final Optional<A> getValueAt(int t, int row) {
			return Optional.ofNullable(data.getOrDefault(t,Collections.emptyMap()).get(row));
		}
		public final Optional<A> getPrecedingValue() {
			return getValueAt(cursorT.get()-1, getSelectedRow());					
		}

		public final Optional<A> getSelectedValue() {
			return getValueAt(cursorT.get(), getSelectedRow());			
		}

		public final void setSelectedValue(A inputVal) {
			if (!data.containsKey(cursorT.get())) {
				data.put(cursorT.get(), new HashMap<>());
			}
			data.get(cursorT.get()).put(getSelectedRow(), inputVal);
		}					
		
		public final void removeSelectedValue() {
			data.getOrDefault(cursorT.get(),new HashMap<>()).remove(selectedRow.get());
		}
				
		Map<KeyStroke,Set<Runnable>> keyEventFunctions = new HashMap<>();
		public final void addKeyEventFunction(KeyStroke stroke, Runnable r) {
			keyEventFunctions.putIfAbsent(stroke,new HashSet<>());
			keyEventFunctions.get(stroke).add(r);					
		}
		
		AtomicInteger selectedRow = new AtomicInteger(0);
		public final int getSelectedRow() {
			return selectedRow.get();
		}
		
		public final void setSelectedRow(int row) {
			selectedRow.set(row);
		}
		
		public abstract int getRowCount();
		
		public final void drawGrid(Graphics2D g) {
			g.setFont(font);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(Color.WHITE);
			g.fillRect(0,0,getWidth(),getHeight());
			if (isSelected()) {				
				g.setPaint(Color.getHSBColor(0.9f,0.2f,1f)); 
				g.fillRect(0,0,getWidth(),getHeight());
			}  
			g.setStroke(new BasicStroke(1));
			
			g.setPaint(Color.BLACK);
			g.drawString(getName(),2,fontMetrics.getMaxAscent());
			final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			
			AtomicInteger timeSignatureT0 = new AtomicInteger(0);
			AtomicReference<TimeSignatureEvent> timeSignature = new AtomicReference<>(new TimeSignatureEvent(4,TimeSignatureDenominator._4));
													
			{
				int y = infoPanelHeight;
			
				for (int stringNumber = 0; stringNumber < getRowCount(); stringNumber++) {
					g.setPaint(Color.getHSBColor(0f,0f,stringNumber%2==0?0.95f:0.9f));
					g.fillRect(0, y, getWidth(), rowHeight);						
					y+=rowHeight;
				}
				
			}
			AtomicInteger x = new AtomicInteger(0);
			
			IntStream.range(t0, t1).forEach(t_ -> {
				
				eventCanvas.getTimeSignatureEventForTime(t_).ifPresent(timeSig -> {					
					timeSignature.set(timeSig);
					timeSignatureT0.set(t_);					
				});
				
				if (t_ == playT.get()) {
					g.setPaint(new Color(0,255,0,50));
					g.fillRect(x.get(), infoPanelHeight, cellWidth, getHeight());
				}
				int y = rowHeight+infoPanelHeight;
				
				g.setPaint(Color.BLUE);
				g.setFont(font.deriveFont(Font.PLAIN));
								
				if (cachedMeasurePositions.containsKey(t_)) {
					int measure = cachedMeasurePositions.get(t_);
					g.drawString(""+measure,x.get(),infoPanelHeight);					
				}
				
				
				
				g.setStroke(new BasicStroke(1));
				g.setFont(font);
				for (int rowNumber = 0; rowNumber < getRowCount(); rowNumber++) {

					g.setPaint(Color.getHSBColor(0f,0f,rowNumber%2==1?0.85f:0.8f));
					if (cachedMeasurePositions.containsKey(t_)) {
						g.setStroke(new BasicStroke(2));
					} else {
						g.setStroke(new BasicStroke(1));
					}
					g.drawLine(x.get(), y-rowHeight, x.get(), y);
					if (isSelected() && rowNumber == getSelectedRow() && 
							t_ == cursorT.get()) {
						g.setPaint(Color.RED);
						g.setStroke(new BasicStroke(1));
						g.drawRect(x.get(), y-rowHeight, cellWidth-1,rowHeight-1);
					} 
					y+=rowHeight;
				}
				if (stopT0.get() == t_) {
					g.setStroke(new BasicStroke(2));
					g.setPaint(Color.RED);
					g.drawLine(x.get(), infoPanelHeight, x.get(), infoPanelHeight+getRowCount()*rowHeight);					
				}
				
				if (repeatT.get() == t_ && t_>=0) {
					g.setStroke(new BasicStroke(2));
					g.setPaint(Color.ORANGE.darker());
					g.setStroke(new BasicStroke(1));
					g.drawLine(x.get(), infoPanelHeight, x.get(), infoPanelHeight+getRowCount()*rowHeight);
					g.drawLine(x.get()-2, infoPanelHeight, x.get()-2, infoPanelHeight+getRowCount()*rowHeight);
				}
				x.addAndGet(cellWidth);
			});

		}
		
		public abstract String getName();

	}
	
	class NavigationCanvas extends JPanel implements MouseMotionListener, MouseListener {
					
		private int dataLength = 16*256;
		private final Set<JComponent> componentsToRepaint = new HashSet<>();
		public NavigationCanvas() {
			this.addMouseMotionListener(this);
			this.addMouseListener(this);
		}
		
		public void addComponentToRepaint(JComponent component) {
			componentsToRepaint.add(component);
		}
		public void removeComponentToRepaint(JComponent component) {
			componentsToRepaint.remove(component);
		}
		
		@Override
		public Dimension getSize() {				 
			return new Dimension(super.getWidth(),20);
		}
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.WHITE);
			g.fill(getBounds());				
			double t0 = viewT0.get();
			
			double tDelta = getWidth()/cellWidth;
			double t1 = t0+tDelta;
			double x0 = t0/dataLength*getWidth();
			double x1 = t1/dataLength*getWidth();
			g.setPaint(Color.BLACK);
			g.draw(new Rectangle2D.Double(x0,getBounds().y,x1-x0-1,getHeight()-1));
		}
		

		
		Integer pressedX = 0;
		
		@Override
		public void mousePressed(MouseEvent me) {
			pressedX = me.getPoint().x;
		}
		
		@Override
		public void mouseDragged(MouseEvent me) {
			if (pressedX == null) {
				System.err.println("wtf");
				return;
			}
			
			JPanel navigationBar = (JPanel) me.getSource();
			double deltaX = pressedX - me.getPoint().x;
			pressedX = me.getPoint().x;
			int deltaT= (int) (deltaX/navigationBar.getWidth()*dataLength);
			double t0 = viewT0.get();				 				
			double displayedt1 = t0+navigationBar.getWidth()/cellWidth;
			viewT0.updateAndGet(a->Math.min(dataLength-(int)(displayedt1-t0), Math.max(0,a-deltaT)));
			navigationBar.repaint();
			componentsToRepaint.forEach(a->a.repaint()); 
		}
			
		@Override
		public void mouseReleased(MouseEvent me) {
			pressedX = null;
		}
		
		@Override
		public void mouseClicked(MouseEvent e) {}
		@Override
		public void mouseEntered(MouseEvent e) {}
		@Override
		public void mouseExited(MouseEvent e) {}
		@Override
		public void mouseMoved(MouseEvent e) {}
	}

	class EventCanvas extends KiteTabCanvas<ControlEvent> {
				
		public EventCanvas() {}

		public Optional<TimeSignatureEvent> getTimeSignatureEventForTime(int t_) {
			return data.getOrDefault(t_, Collections.emptyMap()).values().stream()
					.filter(a->a instanceof TimeSignatureEvent).map(a->(TimeSignatureEvent)a)
					.findFirst();
		}

		@Override
		public int getRowCount() {
			return 3;
		}		

		@Override
		public String getName() {
			return "Events";
		}		
		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);
			final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;			
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 
						
				g.setFont(font);
				for (int rowNumber = 0; rowNumber < getRowCount(); rowNumber++) {
					
					g.setPaint(Color.black);
					
					ControlEvent event = data.getOrDefault(t_,new HashMap<>()).getOrDefault(rowNumber,NilControlEvent.get());
					switch (event.getType()) {
					case NIL:
						
						break;
					case TIME_SIGNATURE:
						g.setFont(font.deriveFont(Font.ITALIC));
						g.setPaint(Color.getHSBColor(0.8f, 1f, 0.4f));
						g.drawString(event.toString(),x+1,y-3);
						g.setFont(font);
						break;
					case TEMPO:
						g.setFont(font.deriveFont(Font.ITALIC));
						g.setPaint(Color.getHSBColor(0.2f, 1f, 0.4f));
						g.drawString(event.toString(),x+1,y-3);
						g.setFont(font);
						break;
					case STICKY_NOTE:
						g.setFont(font.deriveFont(Font.ITALIC));
						System.out.println("yo");
						g.setPaint(((StickyNote) event).getColor());
						g.drawString(((StickyNote) event).getText(),x+1,y-3);
						
					default:
						break;					
					}					
					y+=rowHeight;
				}				
				x+=cellWidth;
			}
		}
	}
	
	class TabCanvas extends KiteTabCanvas<InputVal> {		
		
		private final String name;
		private final double[] baseFrequencies;	
		private final int rows;
		private final Map<Integer,InputVal> openNotes = new ConcurrentHashMap<>();
		private final Map<MidiChannel,Integer> openMidiNums = new ConcurrentHashMap<>();
		
		public void noteOff(int row) {
			openNotes.remove(row);
			processTabCanvasEvent(row,null);
		}
		
		public void noteOn(int row,InputVal inputVal) {
			openNotes.put(row, inputVal);
			processTabCanvasEvent(row,inputVal);
		}
		
		void processTabCanvasEvent(int row,InputVal inputVal) {
			MidiChannel channel = channelMappingMap.get(this).get(row).getChannel();
			double[] stringTunings = this.getBaseFrequencies();
			if (inputVal == null) {
				if (openMidiNums.containsKey(channel)) {
					channel.noteOff(openMidiNums.get(channel));
					openMidiNums.remove(channel);
				}			
			}
			else {
				if (inputVal.getEdoSteps().isPresent()) {
					
					double baseFreq = stringTunings[row];
					double edoSteps = inputVal.getEdoSteps().getAsInt();
					
					double freq = baseFreq*Math.pow(2.0, edoSteps/41.0);
					
					double n = (12 * Math.log(freq/440)/Math.log(2) + 69);
					int midiNote = (int) Math.round(n);
					double semitoneOffset = n - midiNote;
					final double semitoneRange = 2.0;
					double bendRatio = semitoneOffset/ semitoneRange;
					int pitchBend = 8192 + (int) (bendRatio*8192);
					pitchBend = Math.max(0, Math.min(16383, pitchBend));
					int lsb = pitchBend & 0x7F;
					int msb = (pitchBend >> 7) & 0x7F;
					try {
						ShortMessage pb = new ShortMessage();					
						pb.setMessage(ShortMessage.PITCH_BEND, channelMappingMap.get(this).get(row).channelNumber, lsb, msb);
						synth.getReceiver().send(pb, -1);
						channel.noteOn(midiNote, 100);
						openMidiNums.put(channel,midiNote);
				 	} catch (Exception ex) {
				 		ex.printStackTrace();
				 	}
				}
			}
		}
		
		public TabCanvas(int strings, String name, double[] baseFrequencies) {
			this.rows = strings;
			this.name = name;
			this.baseFrequencies = baseFrequencies;
		}
		
		public double[] getBaseFrequencies() {
			return baseFrequencies;
		}
				
		@Override
		public Dimension getSize() {				 
			return new Dimension(super.getWidth(),rowHeight * rows);
		}		
		 
		@Override
		public void paint(Graphics g_) {
			
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);
			final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 
				g.setFont(font);
				for (int stringNumber = 0; stringNumber < rows; stringNumber++) {
					g.setPaint(Color.black);									
					String s = data.getOrDefault(t_,new HashMap<>()).getOrDefault(stringNumber,InputVal.NIL).getToken();					
					g.drawString(s,(int) (x+(cellWidth-fontMetrics.stringWidth(s))*0.5),y-3);
					y+=rowHeight;
				}
				x+=cellWidth;
			}
		}

		@Override
		public int getRowCount() {			
			return rows;
		}

		@Override
		public String getName() {
			return name;
		}
	}
	
	class DrumTabCanvas extends KiteTabCanvas<DrumVal> {
		
		public DrumTabCanvas() {
			
		}
				
		@Override
		public void paint(Graphics g_) {
			
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);
			final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;			
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 
								
				
				g.setFont(font);
				for (int stringNumber = 0; stringNumber < getRowCount(); stringNumber++) {
																
									
					g.setPaint(Color.black);
										
					String s = data.getOrDefault(t_,new HashMap<>()).getOrDefault(stringNumber,DrumVal.NIL).getToken();
					
					g.drawString(s,(int) (x+(cellWidth-fontMetrics.stringWidth(s))*0.5),y-3);					
					y+=rowHeight;
				}
				
				x+=cellWidth;
			}			
		}

		@Override
		public int getRowCount() {
			return 4;
		}

		@Override
		public String getName() {
			return "Drums";
		}
	}
	
	
	void createGui() {
															
		Font font = new Font("Monospaced",Font.BOLD,14);
		FontMetrics fontMetrics = new Canvas().getFontMetrics(font);
		
		final int cellWidth = fontMetrics.stringWidth("88")+5;

		JPanel panel = new JPanel();
		InputMap inputMap = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap actionMap = panel.getActionMap();
		Function<Runnable,AbstractAction> rToA = r -> 
		new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {			
				r.run();
			}			
		};
		
		KeyStroke k_ctrlR = KeyStroke.getKeyStroke("control R");
		KeyStroke k_Up = KeyStroke.getKeyStroke("UP");
		KeyStroke k_Down = KeyStroke.getKeyStroke("DOWN");
		KeyStroke k_ShiftUp = KeyStroke.getKeyStroke("shift UP");
		KeyStroke k_ShiftDown = KeyStroke.getKeyStroke("shift DOWN");
		KeyStroke k_CtrlShiftLeft = KeyStroke.getKeyStroke("control shift LEFT");
		KeyStroke k_CtrlShiftRight= KeyStroke.getKeyStroke("control shift RIGHT");
		KeyStroke k_CtrlLeft = KeyStroke.getKeyStroke("control LEFT");
		KeyStroke k_CtrlRight= KeyStroke.getKeyStroke("control RIGHT");
		KeyStroke k_Left = KeyStroke.getKeyStroke("LEFT");
		KeyStroke k_ShiftLeft = KeyStroke.getKeyStroke("shift LEFT");
		KeyStroke k_Right = KeyStroke.getKeyStroke("RIGHT");
		KeyStroke k_ShiftRight= KeyStroke.getKeyStroke("shift RIGHT");
		
		KeyStroke k_ShiftSpace = KeyStroke.getKeyStroke("shift SPACE");
		KeyStroke k_CtrlSpace = KeyStroke.getKeyStroke("ctrl SPACE");
		KeyStroke k_ShiftCtrlSpace = KeyStroke.getKeyStroke("shift ctrl SPACE");
		KeyStroke k_Space = KeyStroke.getKeyStroke("SPACE");
		KeyStroke k_Backspace = KeyStroke.getKeyStroke("BACK_SPACE");
		KeyStroke k_Period = KeyStroke.getKeyStroke("PERIOD");
				
		inputMap.put(k_ctrlR, "adjustRepeatT");
		actionMap.put("adjustRepeatT", rToA.apply(()->this.adjustRepeatT()));		
		inputMap.put(k_ShiftSpace, "setPlayT");
		actionMap.put("setPlayT", rToA.apply(() -> this.setPlayT()));
		inputMap.put(k_CtrlSpace, "returnToStopT0");
		actionMap.put("returnToStopT0", rToA.apply(() -> this.returnToStopT0()));
		inputMap.put(k_Space, "togglePlayStatus");
		actionMap.put("togglePlayStatus", rToA.apply(() -> this.togglePlayStatus()));
		inputMap.put(k_ShiftCtrlSpace, "setStopT0");
		actionMap.put("setStopT0", rToA.apply(() -> this.setStopT0()));
		
		//inputMap.put(k_Period, "toggleNoteSelectionMode");
		//actionMap.put("toggleNoteSelectionMode", rToA.apply(()->this.toggleNoteSelectionMode()));
		inputMap.put(k_Backspace, "backspace");
		actionMap.put("backspace",rToA.apply(()->this.backspace()));
		inputMap.put(k_CtrlShiftLeft, "ctrlShiftLeft");
		actionMap.put("ctrlShiftLeft", rToA.apply(()->this.playTToPreviousMeasure()));
		inputMap.put(k_CtrlShiftRight, "ctrlShiftRight");
		actionMap.put("ctrlShiftRight", rToA.apply(()->this.playTToNextMeasure()));
		inputMap.put(k_CtrlLeft, "ctrlLeft");
		actionMap.put("ctrlLeft", rToA.apply(()->this.decrementPlayT()));
		inputMap.put(k_CtrlRight, "ctrlRight");
		actionMap.put("ctrlRight", rToA.apply(()->this.incrementPlayT()));
		inputMap.put(k_Up, "up");
		actionMap.put("up", rToA.apply(()->this.arrowUp()));
		inputMap.put(k_Down, "down");
		actionMap.put("down", rToA.apply(()->this.arrowDown()));
		inputMap.put(k_ShiftUp, "shiftUp");
		actionMap.put("shiftUp", rToA.apply(()->this.shiftArrowUp()));
		inputMap.put(k_ShiftDown, "shiftDown");
		actionMap.put("shiftDown", rToA.apply(()->this.shiftArrowDown()));
		inputMap.put(k_Right, "right");
		actionMap.put("right", rToA.apply(()->this.incrementCursorT()));	
		inputMap.put(k_Left, "left");
		actionMap.put("left", rToA.apply(()->this.decrementCursorT()));
		inputMap.put(k_ShiftRight, "shiftRight");;
		actionMap.put("shiftRight", rToA.apply(()->this.cursorTToNextMeasure()));
		inputMap.put(k_ShiftLeft, "shiftLeft");;
		actionMap.put("shiftLeft", rToA.apply(()->this.cursorTToPrevMeasure()));

		
		for (int i = 'A'; i <= 'Z'; i++) {
			final int i_ = i;
			String s = String.valueOf((char) i_);
			KeyStroke k = KeyStroke.getKeyStroke(s);
			inputMap.put(k, ""+i_);
			actionMap.put(""+i_,rToA.apply(()->this.handleABCInput((char) i_)));
		}
		for (int i = 0; i < 10; i++) {
			final int i_ = i;
			KeyStroke k = KeyStroke.getKeyStroke(""+i);
			inputMap.put(k, ""+i_);
			actionMap.put(""+i_,rToA.apply(()->this.handleNumericInput(i_)));
		}
		
		Arrays.asList(eventCanvas,bassCanvas,guitarCanvas,drumCanvas).forEach(canvas -> {
			canvas.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent me) {
					int t_ = (int) Math.floor(me.getPoint().x/cellWidth)+viewT0.get();
					final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
					int row = (int) Math.floor((me.getPoint().y-infoPanelHeight)/rowHeight);
					if (selectedCanvas.get() != null) {
				
						selectedCanvas.get().repaint();
					}
					selectedCanvas.set(canvas);
				
					selectedCanvas.get().setSelectedRow(row);
					if (t_ >= 0) {
						setCursorT(t_);
					}
					selectedCanvas.get().repaint();
				}
			});
		});
		
		JPanel controlBar = new JPanel();
		controlBar.setLayout(null);
		
		playButton = new LeftClickablePanelButton(this::startPlayback) {
			@Override
			public void paint(Graphics g_) {
				
				Graphics2D g = (Graphics2D) g_;
				
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				g.setPaint(Color.green);
				g.fillRoundRect(0,0,getWidth(),getHeight(),10,10);
				g.setPaint(Color.black);
				
				double r = 0.3*(Math.min(getHeight(),getWidth()));
				double cx = getWidth()/2.0;
				double cy = getHeight()/2.0;
				
				Path2D.Double p2d = new Path2D.Double();
				p2d.moveTo(cx+r*Math.cos(0),cy+Math.sin(0));				
				p2d.lineTo(cx+r*Math.cos(Math.toRadians(120)),cy+r*Math.sin(Math.toRadians(120)));
				p2d.lineTo(cx+r*Math.cos(Math.toRadians(240)),cy+r*Math.sin(Math.toRadians(240)));				
				p2d.closePath();
				
				g.fill(p2d);
			}
		};
		
				
		stopButton = new LeftClickablePanelButton(this::stopPlayback) {
			@Override
			public void paint(Graphics g_) {
				Graphics2D g = (Graphics2D) g_;
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				g.setPaint(Color.RED);
				g.fillRoundRect(0,0,getWidth(),getHeight(),10,10);
				g.setPaint(Color.black);				
				g.fill(new Rectangle2D.Double(getWidth()*0.25, getHeight()*0.25, getWidth()*0.5, getHeight()*0.5));				
			}
		};
		
		
		{
			int x = 0;
			for (Component c : Arrays.asList(playStatusPanel,playButton,stopButton)) {
				controlBar.add(c);
				c.setBounds(new Rectangle(x,0,20,20));
				x+=25;
			}
		}
		controlBar.setSize(new Dimension(Toolkit.getDefaultToolkit().getScreenSize().width,20));
		panel.setLayout(null);
		int y = 0;		
		panel.add(navigationBar);
		navigationBar.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,20));
		y+=navigationBar.getBounds().height;
		panel.add(eventCanvas);
		eventCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,80));
		y+=eventCanvas.getBounds().height;
		panel.add(bassCanvas);						
		bassCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,130));
		y+=bassCanvas.getBounds().height;		
		panel.add(guitarCanvas);						
		guitarCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,150));
		y+=guitarCanvas.getBounds().height;
		panel.add(acousticCanvas);						
		acousticCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,130));
		y+=acousticCanvas.getBounds().height;
		panel.add(drumCanvas);						
		drumCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,100));
		y+=drumCanvas.getBounds().height;
		panel.add(controlBar);
		controlBar.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,20));
		y+=controlBar.getBounds().height;
		y+=80;
		
		JMenuBar menuBar = new JMenuBar();
		JMenu menu = new JMenu("Menu");
		JMenuItem save = new JMenuItem("Save");
		JMenuItem load= new JMenuItem("Load");
		menuBar.add(menu);
		menu.add(save);
		menu.add(load);
		save.addActionListener(ae -> saveXML());
		load.addActionListener(ae -> loadXML());
		updateMeasureLinePositions();
		
		frame.setJMenuBar(menuBar);
		frame.setSize(Toolkit.getDefaultToolkit().getScreenSize().width,y);		
		frame.getContentPane().add(panel,BorderLayout.CENTER);
		//frame.getContentPane().add(controlBar,BorderLayout.SOUTH);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}
	
	
	void addTempo() {
		JDialog dialog = new JDialog(frame,String.format("Adding tempo (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		Box outerBox = Box.createVerticalBox();
		Box innerBox0 = Box.createHorizontalBox();
		JLabel tempoLabel = new JLabel("Tempo");
		JSpinner tempoSpinner = new JSpinner(new SpinnerNumberModel(120,1,1000,1));
		innerBox0.add(tempoLabel);
		innerBox0.add(tempoSpinner);
		innerBox0.add(Box.createHorizontalGlue());
		outerBox.add(innerBox0);
		JButton load = new JButton("Load");
		outerBox.add(load);
		load.addActionListener(ae -> {
			TempoEvent tempo = new TempoEvent((int) tempoSpinner.getValue());
			eventCanvas.setSelectedValue(tempo);
			dialog.dispose();
		});
		dialog.getContentPane().add(outerBox,BorderLayout.CENTER);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);
	}
	
	void processGeneralTabCanvasEvent(int row, InputVal inputVal, TabCanvas canvas,Map<MidiChannel,Integer> activeNoteMap) {
		MidiChannel channel = channelMappingMap.get(canvas).get(row).getChannel();
		double[] stringTunings = canvas.getBaseFrequencies();
		if (inputVal == null) {
			if (activeNoteMap.containsKey(channel)) {
				channel.noteOff(activeNoteMap.get(channel));
				activeNoteMap.remove(channel);
			}			
		}
		else {
			if (inputVal.getEdoSteps().isPresent()) {
				
				double baseFreq = stringTunings[row];
				double edoSteps = inputVal.getEdoSteps().getAsInt();
				
				double freq = baseFreq*Math.pow(2.0, edoSteps/41.0);
				
				double n = (12 * Math.log(freq/440)/Math.log(2) + 69);
				int midiNote = (int) Math.round(n);
				double semitoneOffset = n - midiNote;
				final double semitoneRange = 2.0;
				double bendRatio = semitoneOffset/ semitoneRange;
				int pitchBend = 8192 + (int) (bendRatio*8192);
				pitchBend = Math.max(0, Math.min(16383, pitchBend));
				int lsb = pitchBend & 0x7F;
				int msb = (pitchBend >> 7) & 0x7F;
				try {
					ShortMessage pb = new ShortMessage();					
					pb.setMessage(ShortMessage.PITCH_BEND, channelMappingMap.get(canvas).get(row).channelNumber, lsb, msb);
					synth.getReceiver().send(pb, -1);
					channel.noteOn(midiNote, 100);
					activeNoteMap.put(channel,midiNote);
			 	} catch (Exception ex) {
			 		ex.printStackTrace();
			 	}
			}
		}
	}
	
	void addTimeSignature() {
		if (selectedCanvas.get() != eventCanvas) {
			return;
		}
		JDialog dialog = new JDialog(frame,String.format("Adding time signature (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		Box outerBox = Box.createVerticalBox();
		Box innerBox0 = Box.createHorizontalBox();
		Box innerBox1 = Box.createHorizontalBox();
		JLabel numeratorLabel = new JLabel("Numer");
		JLabel denominatorLabel = new JLabel("Denom");
		JSpinner numeratorSpinner = new JSpinner(new SpinnerNumberModel(4,1,Integer.MAX_VALUE,1));
		JComboBox<TimeSignatureDenominator> denominatorComboBox = new JComboBox<>(TimeSignatureDenominator.values());
		denominatorComboBox.setSelectedItem(TimeSignatureDenominator._4);
		innerBox0.add(numeratorLabel);
		innerBox0.add(numeratorSpinner);
		innerBox0.add(Box.createHorizontalGlue());
		innerBox1.add(denominatorLabel);
		innerBox1.add(denominatorComboBox);
		innerBox1.add(Box.createHorizontalGlue());
		JButton addButton = new JButton("Add");
		outerBox.add(innerBox0);
		outerBox.add(innerBox1);
		outerBox.add(addButton);
		dialog.getContentPane().add(outerBox,BorderLayout.CENTER);
		addButton.addActionListener(ae -> {
			TimeSignatureEvent event = new TimeSignatureEvent((int) numeratorSpinner.getValue(),(TimeSignatureDenominator) denominatorComboBox.getSelectedItem());
			eventCanvas.setSelectedValue(event);
			updateMeasureLinePositions();
			repaintCanvases();
			dialog.dispose();
		});
		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);

	}
	
	void addStickyNote() {
		JDialog dialog = new JDialog(frame,String.format("Adding sticky note (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		Box outerBox = Box.createVerticalBox();
		Box innerBox0 = Box.createHorizontalBox();
		Box innerBox1 = Box.createHorizontalBox();
		JLabel textLabel = new JLabel("Text");
		JLabel colorLabel = new JLabel("Color");
		JTextField textField = new JTextField("New note");
		eventCanvas.getSelectedValue().filter(a->a instanceof StickyNote).map(a->(StickyNote)a).ifPresent(stickyNote -> {
			textField.setText(stickyNote.getText());
		});
 
		JColorChooser colorChooser = new JColorChooser();
		
		colorChooser.setColor(Color.WHITE);
		
		innerBox0.add(textLabel);
		innerBox0.add(textField);
		innerBox0.add(Box.createHorizontalGlue());
		innerBox1.add(colorLabel);
		innerBox1.add(colorChooser);
		innerBox1.add(Box.createHorizontalGlue());
		JButton addButton = new JButton("Add");
		outerBox.add(innerBox0);
		outerBox.add(innerBox1);
		outerBox.add(addButton);
		dialog.getContentPane().add(outerBox,BorderLayout.CENTER);
		addButton.addActionListener(ae -> {
			
			StickyNote event = new StickyNote(textField.getText().trim(),colorChooser.getColor());
			eventCanvas.setSelectedValue(event);
			eventCanvas.repaint();
			dialog.dispose();
		});
		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);

	}
	
	void loadXML() {
		JFileChooser chooser = new JFileChooser(".");
		
		if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			try {
				
				DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
				DocumentBuilder db = dbf.newDocumentBuilder();
				Document doc = db.parse(chooser.getSelectedFile());
				Element root = doc.getDocumentElement();
				guitarCanvas.data.clear();
				acousticCanvas.data.clear();
				bassCanvas.data.clear();
				drumCanvas.data.clear();
				eventCanvas.data.clear();
				NodeList bassNodes = root.getElementsByTagName("bass");
				
				Element bassNode = (Element) bassNodes.item(0);
				NodeList bassTNodes = bassNode.getElementsByTagName("t");
				for (int i = 0; i < bassTNodes.getLength(); i++) {
					Element tNode = (Element) bassTNodes.item(i);
					int t = Integer.parseInt(tNode.getAttribute("v"));
					setCursorT(t);
					NodeList sNodes = tNode.getElementsByTagName("s");
					for (int j = 0; j < sNodes.getLength(); j++) {
						Element sNode = (Element) sNodes.item(j);
						InputVal inputVal = InputVal.lookup(sNode.getAttribute("v")).orElse(InputVal.NIL);
						int stringNum = Integer.parseInt(sNode.getAttribute("n"));
						if (inputVal != InputVal.NIL) {
							bassCanvas.setSelectedRow(stringNum);
							bassCanvas.setSelectedValue(inputVal);
						}
					}
				}
				
				NodeList acousticNodes = root.getElementsByTagName("acoustic");
				
				Element acousticNode = (Element) acousticNodes.item(0);
				NodeList acousticTNodes = acousticNode.getElementsByTagName("t");
				for (int i = 0; i < acousticTNodes.getLength(); i++) {
					Element tNode = (Element) acousticTNodes.item(i);
					int t = Integer.parseInt(tNode.getAttribute("v"));
					setCursorT(t);
					NodeList sNodes = tNode.getElementsByTagName("s");
					for (int j = 0; j < sNodes.getLength(); j++) {
						Element sNode = (Element) sNodes.item(j);
						InputVal inputVal = InputVal.lookup(sNode.getAttribute("v")).orElse(InputVal.NIL);
						int stringNum = Integer.parseInt(sNode.getAttribute("n"));
						if (inputVal != InputVal.NIL) {
							acousticCanvas.setSelectedRow(stringNum);
							acousticCanvas.setSelectedValue(inputVal);
						}
					}
				}
				
				NodeList guitarNodes = root.getElementsByTagName("guitar");
				Element guitarNode = (Element) guitarNodes.item(0);
				NodeList guitarTNodes = guitarNode.getElementsByTagName("t");
				for (int i = 0; i < guitarTNodes.getLength(); i++) {
					Element tNode = (Element) guitarTNodes.item(i);
					int t = Integer.parseInt(tNode.getAttribute("v"));
					setCursorT(t);
					NodeList sNodes = tNode.getElementsByTagName("s");
					for (int j = 0; j < sNodes.getLength(); j++) {
						Element sNode = (Element) sNodes.item(j);
						InputVal inputVal = InputVal.lookup(sNode.getAttribute("v")).orElse(InputVal.NIL);
						int stringNum = Integer.parseInt(sNode.getAttribute("n"));
						if (inputVal != InputVal.NIL) {
							guitarCanvas.setSelectedRow(stringNum);
							guitarCanvas.setSelectedValue(inputVal);
						}
					} 
				}
				
				NodeList drumNodes = root.getElementsByTagName("drum");
				Element drumNode = (Element) drumNodes.item(0);
				NodeList drumTNodes = drumNode.getElementsByTagName("t");
				for (int i = 0; i < drumTNodes.getLength(); i++) {
					Element tNode = (Element) drumTNodes.item(i);
					int t = Integer.parseInt(tNode.getAttribute("v"));
					setCursorT(t);
					NodeList sNodes = tNode.getElementsByTagName("s");
					for (int j = 0; j < sNodes.getLength(); j++) {
						Element sNode = (Element) sNodes.item(j);
						DrumVal inputVal = DrumVal.lookup(sNode.getAttribute("v")).orElse(DrumVal.NIL);
						int stringNum = Integer.parseInt(sNode.getAttribute("n"));
						if (inputVal != DrumVal.NIL) {
							drumCanvas.setSelectedRow(stringNum);
							drumCanvas.setSelectedValue(inputVal);
						}
					} 
				}
				NodeList eventNodes = root.getElementsByTagName("events");
				Element eventNode = (Element) eventNodes.item(0);
				NodeList eventTNodes = eventNode.getElementsByTagName("t");
				for (int i = 0; i < eventTNodes.getLength(); i++) {
					Element tNode = (Element) eventTNodes.item(i);
					int t = Integer.parseInt(tNode.getAttribute("v"));
					setCursorT(t);
					NodeList sNodes = tNode.getElementsByTagName("s");
					for (int j = 0; j < sNodes.getLength(); j++) {
						Element sNode = (Element) sNodes.item(j);
						//DrumVal inputVal = DrumVal.lookup(sNode.getAttribute("v")).orElse(DrumVal.NIL);
						int row= Integer.parseInt(sNode.getAttribute("n"));
						eventCanvas.setSelectedRow(row);
						NodeList timeSignatureNodes = sNode.getElementsByTagName("timeSignature");
						IntStream.range(0, timeSignatureNodes.getLength()).mapToObj(k->(Element) timeSignatureNodes.item(k))
						.map(TimeSignatureEvent::fromXMLElement).forEach(timeSignature -> {
							eventCanvas.setSelectedValue(timeSignature);
						});
						NodeList tempoNodes = sNode.getElementsByTagName("tempo");
						IntStream.range(0, tempoNodes.getLength()).mapToObj(k->(Element) tempoNodes.item(k))
						.map(TempoEvent::fromXMLElement).forEach(tempo-> {
							eventCanvas.setSelectedValue(tempo);
						});
						NodeList stickyNodes = sNode.getElementsByTagName("note");
						IntStream.range(0, stickyNodes.getLength()).mapToObj(k->(Element) stickyNodes.item(k))
						.map(StickyNote::fromXMLElement).forEach(stickyNote-> {
							eventCanvas.setSelectedValue(stickyNote);
						});
					}
				}
				repaintCanvases();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}
	
	void saveXML() {
		JFileChooser chooser = new JFileChooser(".");
		String date = LocalDateTime.now(ZoneId.of("Z")).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
		chooser.setSelectedFile(new File(date+".xml"));
		if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
			
			try {
				DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
				DocumentBuilder db = dbf.newDocumentBuilder();
				Document doc = db.newDocument();
				Element root = doc.createElement("tabData");
				doc.appendChild(root);
				for (Object[] a : Arrays.asList(
						new Object[] {"bass",bassCanvas},
						new Object[] {"guitar",guitarCanvas},
						new Object[] {"acoustic",acousticCanvas},
						new Object[] {"drum",drumCanvas},
						new Object[] {"events",eventCanvas})) {
					Element e = doc.createElement(a[0].toString());
					root.appendChild(e);
					if (a[1] == drumCanvas) {
						for (Entry<Integer, Map<Integer, DrumVal>> entry : ((DrumTabCanvas) a[1]).data.entrySet()) {
							Element tElement = doc.createElement("t");
							e.appendChild(tElement);
							tElement.setAttribute("v", ""+entry.getKey());
							entry.getValue().entrySet().stream().forEach(entry2 -> {
								Element sElement = doc.createElement("s");
								sElement.setAttribute("n", ""+entry2.getKey());
								sElement.setAttribute("v", ""+entry2.getValue().getToken());
								tElement.appendChild(sElement);
							});
						}
					} else if (a[1] == eventCanvas) {
						for (Entry<Integer,Map<Integer,ControlEvent>> entry : ((EventCanvas) a[1]).data.entrySet()) {
							Element tElement = doc.createElement("t");
							e.appendChild(tElement);
							tElement.setAttribute("v", ""+entry.getKey());
							entry.getValue().entrySet().stream().forEach(entry2 -> {
								Element sElement = doc.createElement("s");
								sElement.setAttribute("n", ""+entry2.getKey());
								sElement.appendChild(entry2.getValue().toXMLElement(doc));								
								tElement.appendChild(sElement);
							});
						}
					} else {
						for (Entry<Integer, Map<Integer, InputVal>> entry : ((TabCanvas) a[1]).data.entrySet()) {
							Element tElement = doc.createElement("t");
							e.appendChild(tElement);
							tElement.setAttribute("v", ""+entry.getKey());
							entry.getValue().entrySet().stream().forEach(entry2 -> {
								Element sElement = doc.createElement("s");
								sElement.setAttribute("n", ""+entry2.getKey());
								sElement.setAttribute("v", ""+entry2.getValue().getToken());
								tElement.appendChild(sElement);
							});
						}
					}
				}
				TransformerFactory tf = TransformerFactory.newDefaultInstance();
				Transformer t  = tf.newTransformer();
				t.setOutputProperty(OutputKeys.INDENT,"yes");
				t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount","4");
				t.transform(new DOMSource(doc), new StreamResult(chooser.getSelectedFile()));
				t.transform(new DOMSource(doc), new StreamResult(System.out));
			} catch (Exception ex) {
				ex.printStackTrace();
			}
			
		}
		
	}
}