package kitesequencer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.MouseInfo;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Line2D;
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
import java.util.List;
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
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
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
	private static KiteTabSequencer instance;
	
	private Font font = new Font("Monospaced",Font.BOLD,11);
	private FontMetrics fontMetrics = new Canvas().getFontMetrics(font);
	
	int cellWidth = fontMetrics.stringWidth("88")+3;
	int rowHeight = fontMetrics.getMaxAscent()+2;
	final int scrollTimeMargin = 4;	
	final double middleC = 220.0 * Math.pow(2d, 3.0/12.0);	
	
	
	final File defaultPath = new File("scores");			
	final AtomicReference<File> activeFile = new AtomicReference<>(null);	
	final AtomicBoolean fileHasBeenModified = new AtomicBoolean(false);
	
	final AtomicBoolean isPlaying = new AtomicBoolean(false);
	final AtomicInteger playT = new AtomicInteger(0);
	final AtomicInteger viewT0 = new AtomicInteger(-scrollTimeMargin);
	final AtomicInteger cursorT = new AtomicInteger(0);
	final AtomicInteger stopT0 = new AtomicInteger(0);
	final AtomicInteger repeatT = new AtomicInteger(-1);
	final AtomicInteger tempo = new AtomicInteger(120);
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);
		 
	final TreeMap<Integer,Integer> cachedMeasurePositions = new TreeMap<>();
	final HashSet<Integer> cachedBeatMarkerPositions = new HashSet<>();
	
	final ScheduledExecutorService playbackDaemon = Executors.newSingleThreadScheduledExecutor();
	final ScheduledExecutorService midiDaemon = Executors.newSingleThreadScheduledExecutor();	
		
	final JFrame frame = new JFrame("Kite Tab Sequencer 2000");
	
	LeftClickablePanelButton playButton, stopButton;
	final PlayStatusPanel playStatusPanel = new PlayStatusPanel();
	
	final EventCanvas eventCanvas = new EventCanvas();
	
	File defaultSoundfontFile = null;//new File("sf2/SM64SF V2.sf2");
	
	final TabCanvas bassCanvas = new TabCanvas(6,"Bass",
			IntStream.of(5,4,3,2,1,0).mapToDouble(i -> 
			middleC * 0.25 * Math.pow(2,(i*13d)/41d)).toArray());
	
	final TabCanvas acousticCanvas = new TabCanvas(6,"AcousticG",
			IntStream.of(6,5,4,3,2,1).mapToDouble(i -> 
			middleC * 0.5 *Math.pow(2,(i*13d)/41d)).toArray());
			
	
	final TabCanvas guitarCanvas = new TabCanvas(7,"ElectricG",
			IntStream.of(6,5,4,3,2,1,0).mapToDouble(i -> 
			middleC * 0.5 *Math.pow(2,(i*13d)/41d)).toArray());
			
	
	final DrumTabCanvas drumCanvas = new DrumTabCanvas();
	final NavigationCanvas navigationBar = new NavigationCanvas();
	final List<KiteTabCanvas<?>> allCanvases = Arrays.asList(
			eventCanvas,bassCanvas,guitarCanvas,acousticCanvas,drumCanvas);
	final AtomicReference<KiteTabCanvas<?>> selectedCanvas = new AtomicReference<>(eventCanvas);
	
	void playbackDaemonFunction() {
		//DO NOT CALL THIS ON MASTER THREAD
		while (true) {
			if (!isPlaying.get()) {
				continue;
			}
			else {
				int t = playT.getAndUpdate(i->i+1==repeatT.get()?stopT0.get():i+1);
				try {
					midiDaemon.execute(() -> {
						allCanvases.forEach(a->a.handleEvents(t));	
					});
					SwingUtilities.invokeLater(() -> {
						frame.repaint();
						//allCanvases.forEach(a->a.repaint());
					});
				} catch (Exception e) {					
					e.printStackTrace();
				}
				
				long bpm = tempo.get();
				Duration sixteenth = Duration.ofMinutes(1).dividedBy(bpm).dividedBy(4);  
				playbackDaemon.schedule(()->playbackDaemonFunction(),
						sixteenth.toMillis(),TimeUnit.MILLISECONDS);
				
				return;
			}
		}
	}
	
	void repaintCanvases() {
		Arrays.asList(navigationBar,guitarCanvas,acousticCanvas,drumCanvas,bassCanvas,eventCanvas)
		.forEach(a->a.repaint()); 
	}
	
	
	void addStaccato() {
		
	}
	
	void addAccent() {
		
	}
	
	void configureCanvasInstrument() {
		if (selectedCanvas.get() instanceof TabCanvas) {
			((TabCanvas)selectedCanvas.get()).displayInstrumentDialog();
		}
	}
	
	void saveActiveFile() {
		if (!defaultPath.exists()) {
			defaultPath.mkdir();
		}
		if (activeFile.get() != null) {
			if (fileHasBeenModified.get()) {
				try {
					saveXML(activeFile.get());
					fileHasBeenModified.set(false);
					updateWindowTitle();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		} else {
			JFileChooser chooser = new JFileChooser(defaultPath);			
			String date = LocalDateTime.now(ZoneId.of("Z")).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
			chooser.setSelectedFile(new File(date+".xml"));
			if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
				
				try {
					saveXML(chooser.getSelectedFile());
					activeFile.set(chooser.getSelectedFile());
					fileHasBeenModified.set(false);
					updateWindowTitle();
				} catch (Exception ex) {
					ex.printStackTrace();					
				}
			}
			
		}
	}
	
	void openFileDialog() {
		File defaultPath = new File("scores");
		if (!defaultPath.exists()) {
			defaultPath.mkdir();
		}
		JFileChooser fileChooser = new JFileChooser(defaultPath);

		if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
			//back up in case load fails or whatever
			/*
			Map<Integer,Map<Integer,ControlEvent>> backupEventData = 
					eventCanvas.cloneDataMap();
			Map<Integer,Map<Integer,InputVal>> backupBassData = 
					bassCanvas.cloneDataMap();
			Map<Integer,Map<Integer,InputVal>> backupGuitarData = 
					guitarCanvas.cloneDataMap();
			Map<Integer,Map<Integer,InputVal>> backupAcousticData = 
					acousticCanvas.cloneDataMap();
			Map<Integer,Map<Integer,DrumVal>> backupDrumData = 
					drumCanvas.cloneDataMap();
					*/
			try {
				loadXML(fileChooser.getSelectedFile());
				activeFile.set(fileChooser.getSelectedFile());
				fileHasBeenModified.set(false);
				updateWindowTitle();
			} catch (Exception ex) {
				ex.printStackTrace();
				/*
				Arrays.asList(eventCanvas,bassCanvas,guitarCanvas,acousticCanvas,drumCanvas)
				.forEach(a->a.data.clear());
				eventCanvas.data.putAll(backupEventData);
				bassCanvas.data.putAll(backupBassData);
				guitarCanvas.data.putAll(backupGuitarData);
				acousticCanvas.data.putAll(backupAcousticData);
				drumCanvas.data.putAll(backupDrumData);
				*/
			}
		}
	}
	
	void updateWindowTitle() {
		StringBuilder sb = new StringBuilder();
		sb.append("KiteTabSequencer2000");
		if (activeFile.get() == null) {
			if (fileHasBeenModified.get()) {
				sb.append(" (unsaved)");
			} 
		} else {
			sb.append(" (");
			sb.append(activeFile.get().getName());
			if (fileHasBeenModified.get()) {
				sb.append(" * ");				
			}
			sb.append(")");
		}
		frame.setTitle(sb.toString());
		
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
		Map<Integer,Integer> measures = new TreeMap<>();
		Set<Integer> markers = new HashSet<>();
		measures.put(0,measure.getAndIncrement());
		for (int t = 0; t < 1000*16; t++) {
			Optional<TimeSignatureEvent> tsO = 
					eventCanvas.data.getOrDefault(t,Collections.emptyMap()).values().stream()
					.filter(a->a.getType()==ControlEventType.TIME_SIGNATURE)
					.map(a->(TimeSignatureEvent) a)
					.findFirst();
			if (tsO.isPresent()) {				
				timeSignature.set(tsO.get());				
				counter.set(0);
				measures.put(t,measure.getAndIncrement());	
			}
			if (counter.get() == timeSignature.get().get16ths()) {
				counter.set(0);
				measures.put(t,measure.getAndIncrement());								
			}			
			if (counter.get() > 0 && counter.get()%(16/timeSignature.get().denominator.getValue()) == 0) {
				markers.add(t);
			}
			counter.incrementAndGet();
		}
		cachedMeasurePositions.clear();
		cachedBeatMarkerPositions.clear();
		cachedMeasurePositions.putAll(measures);
		cachedBeatMarkerPositions.addAll(markers);
		//return toReturn.stream().sorted().toList();
	}
	
	public void backspace() {
		Optional<?> removed = selectedCanvas.get().removeSelectedValue();
		
		removed.filter(a->a instanceof TimeSignatureEvent).ifPresent(object ->{
			updateMeasureLinePositions();
			repaintCanvases();
		});
			
		if (!fileHasBeenModified.get()) {
			fileHasBeenModified.set(true);
			updateWindowTitle();
		}
		selectedCanvas.get().repaint();
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
					if (!fileHasBeenModified.get()) {
						fileHasBeenModified.set(true);
						updateWindowTitle();
					}
					canvas.repaint();
				},()->{
					InputVal.lookup(""+i).ifPresent(a -> {
						canvas.setSelectedValue(a);
						if (!fileHasBeenModified.get()) {
							fileHasBeenModified.set(true);
							updateWindowTitle();
						}
						canvas.repaint();
					});
				});
						
			} else {
				InputVal.lookup(""+i).ifPresent(a -> {
					canvas.setSelectedValue(a);
					if (!fileHasBeenModified.get()) {
						fileHasBeenModified.set(true);
						updateWindowTitle();
					}
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
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				return;
			case 'S':
				addTempo();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				return;
			case 'N':
				addStickyNote();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				return;
			default:
				
				break;
			}

		} else if (selectedCanvas.get() instanceof TabCanvas) {
			TabCanvas canvas = (TabCanvas) selectedCanvas.get();
			switch (c) {
			case 'B':
				canvas.setSelectedValue(InputVal._b);
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				canvas.repaint();
				return;
			case 'D':
				canvas.setSelectedValue(InputVal._d);
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				canvas.repaint();
				return;
			case 'E':
				canvas.setSelectedValue(InputVal._e);
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				canvas.repaint();
				return;
			}
			
		} else if (selectedCanvas.get() instanceof DrumTabCanvas) {
			
			DrumTabCanvas canvas = (DrumTabCanvas) selectedCanvas.get();
			DrumVal.lookup(String.valueOf(c))
			.ifPresent(val -> {				
				canvas.setSelectedValue(cursorT.get(),val);				
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
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
		loadSoundfonts();
		createGui();		
	}


	abstract class KiteTabCanvas<A> extends JPanel {
		
		protected final Map<Integer,Map<Integer,A>> data = new HashMap<>();
		AtomicInteger selectedRow = new AtomicInteger(0);		
		
		public abstract int getRowCount();
		public abstract void handleEvents(int t);
		public abstract String getName();
		
		KiteTabCanvas() {
			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent me) {
									
					int t_ = (int) Math.floor(me.getPoint().getX()/cellWidth)+viewT0.get();					
					final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
					int row = (int) Math.floor((me.getPoint().y-infoPanelHeight)/rowHeight);
					if (selectedCanvas.get() != null) {						
						selectedCanvas.get().repaint();
					}
					selectedCanvas.set(KiteTabCanvas.this);					
					selectedCanvas.get().setSelectedRow(row);
					if (t_ >= 0) {
												
						setCursorT(t_);
						
						if (me.isShiftDown()) {
							setPlayT();
						} else if(me.isControlDown()) {
							setStopT0();							
						} else if (me.isAltDown()) {
							adjustRepeatT();
						}
						selectedCanvas.get().repaint();
					}
				}
				
			});
		}
		
		public final boolean isSelected() {
			return this == selectedCanvas.get(); 
		}
		
		private Color selectedLabelTextColor = Color.yellow;
		private Color unselectedLabelTextColor = Color.white;
		private Color gridColor = new Color(100,100,100);
		private Color selectedHeaderBackgroundColor = Color.black;
		private Color unselectedHeaderBackgroundColor = Color.black;
		private Color evenGridBackground = new Color(20,20,20);
		private Color oddGridBackground = new Color(30,30,30);
		private Color playTColor = new Color(50,50,50);
		private Color selectedMeasureColor = Color.LIGHT_GRAY;
		private Color unselectedMeasureColor = Color.LIGHT_GRAY;
		private Color selectedCellColor = Color.red;
		private Color repeatColor = Color.orange.darker();
							
		public final void drawGrid(Graphics2D g) {
			g.setFont(font);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(isSelected()?selectedHeaderBackgroundColor:unselectedHeaderBackgroundColor);			
			g.fillRect(0,0,getWidth(),getHeight());			  
			g.setStroke(new BasicStroke(1));			
			g.setPaint(isSelected()?selectedLabelTextColor:unselectedLabelTextColor);
			g.drawString(getName(),2,fontMetrics.getMaxAscent());
			final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			
			//AtomicInteger timeSignatureT0 = new AtomicInteger(0);
			//AtomicReference<TimeSignatureEvent> timeSignature = new AtomicReference<>(new TimeSignatureEvent(4,TimeSignatureDenominator._4));
													
			{
				int y = infoPanelHeight;
			
				for (int stringNumber = 0; stringNumber < getRowCount(); stringNumber++) {
					g.setPaint(stringNumber%2==0?evenGridBackground:oddGridBackground);
					g.fillRect(0, y, getWidth(), rowHeight);						
					y+=rowHeight;
				}
				
			}
			int x = 0;
			Path2D.Double p2d = new Path2D.Double();
			IntStream.range(0, getRowCount()+1).map(a->infoPanelHeight+a*rowHeight).forEach(y ->{
				p2d.append(new Line2D.Double(0,y,getWidth(),y),false);
			});
			for (int t = t0; t < t1; t++) { 			
				p2d.append(new Line2D.Double(x,infoPanelHeight,x,infoPanelHeight+rowHeight*getRowCount()),false);
				x+=cellWidth;
			}
			
			x = 0;
			for (int t = t0; t < t1; t++) {
				if (t == playT.get()) {
					g.setPaint(playTColor);
					g.fillRect(x, infoPanelHeight, cellWidth, rowHeight*getRowCount());
				}
				g.setPaint(isSelected()?selectedMeasureColor:unselectedMeasureColor);
				g.setFont(font.deriveFont(Font.PLAIN));
								
				if (cachedMeasurePositions.containsKey(t)) {
					int measure = cachedMeasurePositions.get(t);
					g.drawString(""+measure,x,infoPanelHeight);					
				}
				if (cachedBeatMarkerPositions.contains(t)) {					
					g.drawString(".",x,infoPanelHeight);					
				}
				x+=cellWidth;
			}
			g.setPaint(gridColor);
			g.draw(p2d);
			x=0;
			
			for (int t = t0; t < t1; t++) {
				g.setPaint(gridColor);
				if (cachedMeasurePositions.containsKey(t)) {
					g.setStroke(new BasicStroke(2));
					//g.drawLine(x, y-rowHeight, x, y);
				} else {
					g.setStroke(new BasicStroke(1));
				}
				int y = rowHeight+infoPanelHeight;
				for (int row= 0; row < getRowCount(); row++) {
					if (isSelected() && row == getSelectedRow() && 
							t == cursorT.get()) {
						g.setPaint(selectedCellColor);
						g.setStroke(new BasicStroke(1));
						g.drawRect(x, y-rowHeight, cellWidth,rowHeight);
						
					}
					y+=rowHeight;
				}
				if (stopT0.get() == t) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight+getRowCount()*rowHeight);					
					g.drawLine(x+3, infoPanelHeight, x+3, infoPanelHeight+getRowCount()*rowHeight);
				}
				
				if (repeatT.get() == t && t>=0) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);					
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight+getRowCount()*rowHeight);					
					g.drawLine(x-3, infoPanelHeight, x-3, infoPanelHeight+getRowCount()*rowHeight);
				}
				x+=cellWidth;
			}
			/*
			IntStream.range(t0, t1).forEach(t_ -> {

				
				
				for (int rowNumber = 0; rowNumber < getRowCount(); rowNumber++) {

					g.setPaint(gridColor);
					if (cachedMeasurePositions.containsKey(t_)) {
						g.setStroke(new BasicStroke(2));
					} else {
						g.setStroke(new BasicStroke(1));
					}
					//g.drawLine(x.get(), y-rowHeight, x.get(), y);
					if (isSelected() && rowNumber == getSelectedRow() && 
							t_ == cursorT.get()) {
						g.setPaint(selectedCellColor);
						g.setStroke(new BasicStroke(1));
						g.drawRect(x.get(), y-rowHeight, cellWidth-1,rowHeight-1);
					} 
					y+=rowHeight;
				}
				if (stopT0.get() == t_) {
					g.setStroke(new BasicStroke(2));
					g.setPaint(repeatColor);
					g.drawLine(x.get(), infoPanelHeight, x.get(), infoPanelHeight+getRowCount()*rowHeight);
					g.setStroke(new BasicStroke(1));
					g.drawLine(x.get()-2, infoPanelHeight, x.get()+2, infoPanelHeight+getRowCount()*rowHeight);
				}
				
				if (repeatT.get() == t_ && t_>=0) {
					g.setStroke(new BasicStroke(2));
					g.setPaint(repeatColor);					
					g.drawLine(x.get(), infoPanelHeight, x.get(), infoPanelHeight+getRowCount()*rowHeight);
					g.setStroke(new BasicStroke(1));
					g.drawLine(x.get()-2, infoPanelHeight, x.get()-2, infoPanelHeight+getRowCount()*rowHeight);
				}
				x.addAndGet(cellWidth);
			})
			*/;
			
		}
		
		public final int getMaxVisibleTime() {
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			return t1;
		}		
		
				
		public final Optional<A> getValueAt(int t, int row) {
			return Optional.ofNullable(data.getOrDefault(t,Collections.emptyMap()).get(row));
		}
		
		public final Optional<A> getPrecedingValue() {
			return getValueAt(cursorT.get()-1, getSelectedRow());					
		}

		public final Optional<A> getSelectedValue() {
			return getValueAt(cursorT.get(), getSelectedRow());			
		}

		public final void setSelectedValue(int t, A inputVal) {
			if (!data.containsKey(t)) {
				data.put(t, new HashMap<>());
			}
			data.get(cursorT.get()).put(getSelectedRow(), inputVal);
			
		}
		
		public final void setSelectedValue(A inputVal) {
			setSelectedValue(cursorT.get(),inputVal);		
		}					
		
		public final Optional<A> removeSelectedValue() {
			Optional<A> toReturn = Optional.ofNullable(data.getOrDefault(cursorT.get(), new HashMap<>()).get(selectedRow.get()));
			data.getOrDefault(cursorT.get(),new HashMap<>()).remove(selectedRow.get());
			return toReturn;
		}							
		
		public final int getSelectedRow() {
			return selectedRow.get();
		}
		
		public final void setSelectedRow(int row) {
			selectedRow.set(row);
		}

	}
	
	class NavigationCanvas extends JPanel implements MouseMotionListener, MouseListener {
					
		private int dataLength = 16*256;
		
		public NavigationCanvas() {
			this.addMouseMotionListener(this);
			this.addMouseListener(this);
		}
		
		
		@Override
		public Dimension getSize() {				 
			return new Dimension(super.getWidth(),20);
		}
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.BLACK);
			g.fill(getBounds());				
			double t0 = viewT0.get();
			
			double tDelta = getWidth()/cellWidth;
			double t1 = t0+tDelta;
			double x0 = t0/dataLength*getWidth();
			double x1 = t1/dataLength*getWidth();
			g.setPaint(Color.WHITE);
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
			repaintCanvases();			 
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
						g.setPaint(Color.YELLOW);
						g.drawString(event.toString(),x+1,y-3);
						g.setFont(font);
						break;
					case TEMPO:
						g.setFont(font.deriveFont(Font.ITALIC));
						g.setPaint(Color.pink);
						g.drawString(event.toString(),x+1,y-3);
						g.setFont(font);
						break;
					case STICKY_NOTE:
						g.setFont(font.deriveFont(Font.ITALIC));						
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

		@Override
		public void handleEvents(int t) {
			for (int row = 0; row < getRowCount(); row++) {
				Optional<ControlEvent> controlEvent= this.getValueAt(t, row);
				controlEvent.filter(a->a.getType()==ControlEventType.TEMPO)
				.ifPresent(event -> {
					tempo.set(((TempoEvent) event).getTempo());
				});
			}			
		}
	}
	
	class TabCanvas extends KiteTabCanvas<InputVal> implements SoundfontPlayer {		
		
		private final String name;
		private final double[] baseFrequencies;	
		private final int rows;
		
		private final Map<Integer,InputVal> openNotes = new ConcurrentHashMap<>();
		private final Map<MidiChannel,Integer> openMidiNums = new ConcurrentHashMap<>();
		
		Synthesizer synth = null;
		private File soundfontFile;
		private final AtomicReference<Instrument> loadedInstrument = new AtomicReference<>(null);
		
		public TabCanvas(int strings, String name, double[] baseFrequencies) {
			
			this.rows = strings;
			this.name = name;
			this.baseFrequencies = baseFrequencies;
			
		}
				
		public void displayInstrumentDialog() {
			JDialog dialog = new JDialog(frame,String.format("Configuring instruments for [%s]",
					getName()));
			dialog.setModalityType(ModalityType.APPLICATION_MODAL);
			JButton loadSoundfontButton = new JButton("Load Soundbank:");
			JTextField soundbankTextField = new JTextField(20);
			
			JList<Instrument> instrumentList = new JList<>();
			JScrollPane instrumentScrollPane = new JScrollPane(instrumentList,JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			
			soundbankTextField.setEditable(false);
			
			ListCellRenderer<? super Instrument> originalRenderer = instrumentList.getCellRenderer();
			
			instrumentList.setCellRenderer((list,value,index,isSelected,hasFocus) ->{
				JLabel label = (JLabel) originalRenderer.getListCellRendererComponent(list, value, index, isSelected, hasFocus);				
				 label.setText(String.format("%s (bank %d, program %d)",
						value.getName(),
						value.getPatch().getBank(),
						value.getPatch().getProgram()));
				if (value == loadedInstrument.get()) {
					label.setFont(label.getFont().deriveFont(Font.BOLD | Font.ITALIC));
				}
				return label;

			});
				
			
			if (soundfontFile != null) {
				soundbankTextField.setText(soundfontFile.getName());
				try {
					DefaultListModel<Instrument> model = new DefaultListModel<>();
					model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
					instrumentList.setModel(model);
				} catch (Exception ex) {
					ex.printStackTrace();
				}				
			} else {
				soundbankTextField.setText("<default>");
				try {
					DefaultListModel<Instrument> model = new DefaultListModel<>();
					model.addAll(Arrays.asList(synth.getLoadedInstruments()));					
					instrumentList.setModel(model);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
			
			loadSoundfontButton.addActionListener(ae -> {
				JFileChooser chooser = new JFileChooser("sf2");
				if (soundfontFile != null) {
					chooser.setSelectedFile(soundfontFile);
					if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
						soundfontFile = chooser.getSelectedFile();
						soundbankTextField.setText(soundfontFile.getName());
						try {
							DefaultListModel<Instrument> model = new DefaultListModel<>();
							model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
							instrumentList.setModel(model);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
				}
			});
			
			JButton loadInstrumentButton = new JButton("Load instrument");
			loadInstrumentButton.addActionListener(ae ->{
				Instrument instrument = instrumentList.getSelectedValue();
				loadedInstrument.set(instrument);
				
				IntStream.range(0,getRowCount()).forEach(row -> {
					synth.getChannels()[row].programChange(instrument.getPatch().getBank(),instrument.getPatch().getProgram());
				});
				repaint();
			});
			
			JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
			topPanel.add(loadSoundfontButton);
			topPanel.add(soundbankTextField);
			 
			dialog.getContentPane().add(topPanel,BorderLayout.NORTH);
			dialog.getContentPane().add(instrumentScrollPane,BorderLayout.CENTER);
			dialog.getContentPane().add(loadInstrumentButton,BorderLayout.SOUTH);
			
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setLocationRelativeTo(null);
			dialog.pack();
			dialog.setVisible(true);	
		}


		public void noteOff(int row) {
			openNotes.remove(row);
			processTabCanvasEvent(row,null);
		}
		
		public void noteOn(int row,InputVal inputVal) {
			openNotes.put(row, inputVal);
			processTabCanvasEvent(row,inputVal);
		}
		
		void processTabCanvasEvent(int row,InputVal inputVal) {
			MidiChannel channel = synth.getChannels()[row]; 
			//MidiChannel channel = channelMappingMap.get(this).get(row).getChannel();
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
						pb.setMessage(ShortMessage.PITCH_BEND, row, lsb, msb);
						synth.getReceiver().send(pb, -1);
						channel.noteOn(midiNote, 100);
						openMidiNums.put(channel,midiNote);
				 	} catch (Exception ex) {
				 		ex.printStackTrace();
				 	}
				}
			}
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
			
			g.setFont(font.deriveFont(Font.ITALIC));
			g.setPaint(Color.WHITE);
			g.drawString(String.format("(loaded instrument: '%s')",
					loadedInstrument.get() == null ? "<null>" : loadedInstrument.get().getName()),					
					12+fontMetrics.stringWidth(getName()),fontMetrics.getMaxAscent());
			final int infoPanelHeight = fontMetrics.getMaxAscent()+10;
			g.setPaint(Color.BLACK);
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 
				g.setFont(font);
				for (int stringNumber = 0; stringNumber < rows; stringNumber++) {
					g.setPaint(Color.WHITE);									
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

		@Override
		public void handleEvents(int t) {
			
			IntStream.range(0, getRowCount()).forEach(row -> {	
				this.getValueAt(t, row).ifPresentOrElse(inputVal -> {
					inputVal.getEdoSteps().ifPresentOrElse(steps -> {
						noteOn(row,inputVal);
					},
							() -> {
								System.out.println(inputVal);
							});							
				}, () -> {
					noteOff(row);
				});
			});
			
		}

		
		
		@Override
		public void initializeMidi(File file, int bank, int program) {
			try {
				synth = MidiSystem.getSynthesizer();
				synth.open();
				if (file != null) {
					Soundbank soundbank = MidiSystem.getSoundbank(file);
					synth.unloadAllInstruments(synth.getDefaultSoundbank());
					synth.loadAllInstruments(soundbank);
					Stream.of(synth.getLoadedInstruments()).filter(a->
						a.getPatch().getBank() == bank && a.getPatch().getProgram() == program)
					.findFirst().ifPresent(loadedInstrument::set);					
				}
				
				for (int row = 0; row < getRowCount(); row++) {
					
					synth.getChannels()[row].programChange(bank,program);
				}
				
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		
			
		}
	}
	
	interface SoundfontPlayer {
		public void initializeMidi(File file, int bank, int program);
			
		
	}
	
	class DrumTabCanvas extends KiteTabCanvas<DrumVal> implements SoundfontPlayer {

		Synthesizer synth = null;
		
		private File soundfontFile = null;
		private final AtomicReference<Instrument> loadedInstrument = new AtomicReference<>(null);
		
		public DrumTabCanvas() {
			initializeMidi(defaultSoundfontFile,0,0);
		}
		
		@Override
		public void initializeMidi(File file, int bank, int program) {
			try {
				synth = MidiSystem.getSynthesizer();
				synth.open();
				if (file != null) {				
					Soundbank soundbank = MidiSystem.getSoundbank(file);
					synth.unloadAllInstruments(synth.getDefaultSoundbank());
					synth.loadAllInstruments(soundbank);
					Stream.of(synth.getLoadedInstruments()).filter(a->
					a.getPatch().getBank() == bank && a.getPatch().getProgram() == program)
				.findFirst().ifPresent(loadedInstrument::set);
				}
				synth.getChannels()[9].programChange(bank, program);
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private final Set<Integer> openNotes = new HashSet<>();
		
		@Override
		public void handleEvents(int t) {
			MidiChannel channel = synth.getChannels()[9];
			openNotes.forEach(channel::noteOff);
			openNotes.clear();
			drumCanvas.data.getOrDefault(t,Collections.emptyMap())
			.values().stream().distinct().forEach(drumVal -> {				
				channel.noteOn(drumVal.midiNote, 100);
				openNotes.add(drumVal.midiNote);
			});			
			
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
					g.setPaint(Color.WHITE);
					int y_ = y;
					int x_ = x;
					this.getValueAt(t_, stringNumber).map(a->a.getToken()).ifPresent(s->{
						g.drawString(s,(int) (x_+(cellWidth-fontMetrics.stringWidth(s))*0.5),y_-3);	
					});

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

		JPanel panel = new JPanel();
		InputMap inputMap = panel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap actionMap = panel.getActionMap();
		
		KeyStroke k_CtrlI = KeyStroke.getKeyStroke("control I");
		KeyStroke k_ctrlO = KeyStroke.getKeyStroke("control O");
		KeyStroke k_ctrlS = KeyStroke.getKeyStroke("control S");
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
		KeyStroke k_Delete= KeyStroke.getKeyStroke("DELETE");
		KeyStroke k_Period = KeyStroke.getKeyStroke("PERIOD");
		KeyStroke k_Comma= KeyStroke.getKeyStroke("COMMA");
		
		
		inputMap.put(k_CtrlI, "configureCanvasInstrument");
		actionMap.put("configureCanvasInstrument",rToA(this::configureCanvasInstrument));
		
		inputMap.put(k_Period, "addStaccato");
		actionMap.put("addStaccato", rToA(this::addStaccato));
		inputMap.put(k_Comma, "addAccent");
		actionMap.put("addAccent", rToA(this::addAccent));
			
		inputMap.put(k_ctrlO, "openFileDialog");
		actionMap.put("openFileDialog", rToA(this::openFileDialog));
		inputMap.put(k_ctrlS, "saveActiveFile");
		actionMap.put("saveActiveFile", rToA(this::saveActiveFile));
		inputMap.put(k_ctrlR, "adjustRepeatT");
		actionMap.put("adjustRepeatT", rToA(this::adjustRepeatT));		
		inputMap.put(k_ShiftSpace, "setPlayT");
		actionMap.put("setPlayT", rToA(() -> this.setPlayT()));
		inputMap.put(k_CtrlSpace, "returnToStopT0");
		actionMap.put("returnToStopT0", rToA(() -> this.returnToStopT0()));
		inputMap.put(k_Space, "togglePlayStatus");
		actionMap.put("togglePlayStatus", rToA(() -> this.togglePlayStatus()));
		inputMap.put(k_ShiftCtrlSpace, "setStopT0");
		actionMap.put("setStopT0", rToA(() -> this.setStopT0()));
		
		//inputMap.put(k_Period, "toggleNoteSelectionMode");
		//actionMap.put("toggleNoteSelectionMode", rToA.apply(()->this.toggleNoteSelectionMode()));
		inputMap.put(k_Backspace, "backspace");
		actionMap.put("backspace",rToA(this::backspace));
		inputMap.put(k_Delete, "delete");
		actionMap.put("delete",rToA(this::backspace));
		inputMap.put(k_CtrlShiftLeft, "ctrlShiftLeft");
		actionMap.put("ctrlShiftLeft", rToA(this::playTToPreviousMeasure));
		inputMap.put(k_CtrlShiftRight, "ctrlShiftRight");
		actionMap.put("ctrlShiftRight", rToA(this::playTToNextMeasure));
		inputMap.put(k_CtrlLeft, "ctrlLeft");
		actionMap.put("ctrlLeft", rToA(this::decrementPlayT));
		inputMap.put(k_CtrlRight, "ctrlRight");
		actionMap.put("ctrlRight", rToA(this::incrementPlayT));
		inputMap.put(k_Up, "up");
		actionMap.put("up", rToA(this::arrowUp));
		inputMap.put(k_Down, "down");
		actionMap.put("down", rToA(this::arrowDown));
		inputMap.put(k_ShiftUp, "shiftUp");
		actionMap.put("shiftUp", rToA(this::shiftArrowUp));
		inputMap.put(k_ShiftDown, "shiftDown");
		actionMap.put("shiftDown", rToA(this::shiftArrowDown));
		inputMap.put(k_Right, "right");
		actionMap.put("right", rToA(this::incrementCursorT));	
		inputMap.put(k_Left, "left");
		actionMap.put("left", rToA(this::decrementCursorT));
		inputMap.put(k_ShiftRight, "shiftRight");;
		actionMap.put("shiftRight", rToA(this::cursorTToNextMeasure));
		inputMap.put(k_ShiftLeft, "shiftLeft");;
		actionMap.put("shiftLeft", rToA(this::cursorTToPrevMeasure));
		
		for (int i = 'A'; i <= 'Z'; i++) {
			final int i_ = i;
			String s = String.valueOf((char) i_);
			KeyStroke k = KeyStroke.getKeyStroke(s);
			inputMap.put(k, ""+i_);
			actionMap.put(""+i_,rToA(()->this.handleABCInput((char) i_)));
		}
		
		for (int i = 0; i < 10; i++) {
			final int i_ = i;
			KeyStroke k = KeyStroke.getKeyStroke(""+i);
			inputMap.put(k, ""+i_);
			actionMap.put(""+i_,rToA(()->this.handleNumericInput(i_)));
		}
		
		JPanel controlBar = new JPanel();
		controlBar.setLayout(null);
		
		playButton = new LeftClickablePanelButton(this::startPlayback) {
			@Override
			public void paint(Graphics g_) {
				
				Graphics2D g = (Graphics2D) g_;
				
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				g.setPaint(Color.green);
				g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,10,10);
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
				g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,10,10);
				g.setPaint(Color.black);				
				g.fill(new Rectangle2D.Double(getWidth()*0.25, getHeight()*0.25, getWidth()*0.5, getHeight()*0.5));				
			}
		};
		
		
		{
			int x = 0;
			
			for (Component c : Arrays.asList(playStatusPanel,playButton,stopButton)) {
				controlBar.add(c);
			}
			playButton.setBounds(new Rectangle(0,0,20,20));
			x+=25;
			stopButton.setBounds(new Rectangle(x,0,20,20));
			x+=25;
			playStatusPanel.setBounds(new Rectangle(x,0,fontMetrics.stringWidth("STOP  "),20));
		
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
				
		updateMeasureLinePositions();		
		panel.setPreferredSize(new Dimension(
				(int) controlBar.getBounds().getMaxX(),
				(int)controlBar.getBounds().getMaxY()));
		
		frame.getContentPane().add(panel,BorderLayout.CENTER);
		frame.pack();
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}
	
	
	void addTempo() {
		JDialog dialog = new JDialog(frame,String.format("Adding tempo (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		JLabel tempoLabel = new JLabel("Tempo");
		JSpinner tempoSpinner = new JSpinner(new SpinnerNumberModel(120,1,1000,1));
		
		Runnable r = () -> {
			TempoEvent tempo = new TempoEvent((int) tempoSpinner.getValue());
			eventCanvas.setSelectedValue(tempo);
			eventCanvas.repaint();
			dialog.dispose();
		};
		tempoSpinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		tempoSpinner.getActionMap().put("enterPressed",rToA(r));
		
		dialog.getContentPane().add(tempoLabel,BorderLayout.WEST);
		dialog.getContentPane().add(tempoSpinner,BorderLayout.CENTER);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocationRelativeTo(null);
		
		dialog.pack();
		dialog.setVisible(true);
	}
	
	void addTimeSignature() {
		if (selectedCanvas.get() != eventCanvas) {
			return;
		}
		JDialog dialog = new JDialog(frame,String.format("Adding time signature (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		JPanel outerBox = new JPanel(new GridLayout(2,0));
		JLabel numeratorLabel = new JLabel("Numer");
		JLabel denominatorLabel = new JLabel("Denom");
		JSpinner numeratorSpinner = new JSpinner(new SpinnerNumberModel(4,1,Integer.MAX_VALUE,1));
		JComboBox<TimeSignatureDenominator> denominatorComboBox = new JComboBox<>(TimeSignatureDenominator.values());
		denominatorComboBox.setEditable(false);
		Runnable r = () -> {
			TimeSignatureEvent event = new TimeSignatureEvent((int) numeratorSpinner.getValue(),(TimeSignatureDenominator) denominatorComboBox.getSelectedItem());
			eventCanvas.setSelectedValue(event);
			updateMeasureLinePositions();
			repaintCanvases();
			dialog.dispose();
		};
		((JSpinner.DefaultEditor) numeratorSpinner.getEditor()).getTextField().addActionListener(ae -> r.run());
		denominatorComboBox.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		denominatorComboBox.getActionMap().put("enterPressed",rToA(r));
		numeratorSpinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		numeratorSpinner.getActionMap().put("enterPressed",rToA(r));
		
		denominatorComboBox.setSelectedItem(TimeSignatureDenominator._4);
		outerBox.add(numeratorLabel);
		outerBox.add(numeratorSpinner);		
		outerBox.add(denominatorLabel);
		outerBox.add(denominatorComboBox);				
		
		dialog.getContentPane().add(outerBox,BorderLayout.CENTER);		
		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);

	}
	
	void addStickyNote() {
		JDialog dialog = new JDialog(frame,String.format("Adding sticky note (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);		
		
		JPanel topPanel = new JPanel();		
		JLabel textLabel = new JLabel("Text");

		
		JTextField textField = new JTextField("New note");
		
		eventCanvas.getSelectedValue().filter(a->a instanceof StickyNote).map(a->(StickyNote)a).ifPresent(stickyNote -> {
			textField.setText(stickyNote.getText());
		});
		AtomicReference<Color> color = new AtomicReference<>(Color.BLACK);
		
		JButton colorButton = new JButton("Color") {
			@Override
			public void paint(Graphics g_) {
				Graphics2D g = (Graphics2D) g_;
				Color c = color.get() == null ? Color.white :color.get();
				g.setPaint(c);
				g.fillRect(0,0,getWidth(),getHeight());
				float[] hsb = new float[3];
				Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), hsb);
				g.setPaint(hsb[2]>0.5?Color.BLACK:Color.WHITE);
				g.drawString("Color",2,getHeight()-1);				
			}
		};

		Runnable chooseColor = () -> {
			Color c = JColorChooser.showDialog(frame, "Choose color",color.get());
			if (c != null) {
				color.set(c);
			}
			colorButton.repaint();
		};
		colorButton.addActionListener(ae ->{
			chooseColor.run();
		});
		colorButton.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		colorButton.getActionMap().put("enterPressed",rToA(chooseColor));
		
		topPanel.add(textLabel,BorderLayout.WEST);
		topPanel.add(textField,BorderLayout.CENTER);
		topPanel.add(colorButton,BorderLayout.EAST);
				
		dialog.getContentPane().add(topPanel,BorderLayout.CENTER);
		
		Runnable r = () -> {
		
			StickyNote event = new StickyNote(textField.getText().trim(),color.get());
			eventCanvas.setSelectedValue(event);
			eventCanvas.repaint();
			dialog.dispose();
		};
		textField.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		textField.getActionMap().put("enterPressed",rToA(r));		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);

	}
	
	void loadXML(File file) throws Exception {
		
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(file);
		Element root = doc.getDocumentElement();
		if (root.hasAttribute("cursorT")) {
			cursorT.set(Integer.parseInt(root.getAttribute("cursorT")));
		}
		if (root.hasAttribute("stopT")) {
			stopT0.set(Integer.parseInt(root.getAttribute("stopT")));
		}
		if (root.hasAttribute("selectedCanvas")) {
			selectedCanvas.set(
					Arrays.asList(eventCanvas,bassCanvas,guitarCanvas,acousticCanvas,drumCanvas)
					.get(Integer.parseInt(root.getAttribute("selectedCanvas"))));			
		} else {
			selectedCanvas.set(eventCanvas);
		}
		if (root.hasAttribute("selectedRow")) {
			selectedCanvas.get().setSelectedRow(Integer.parseInt(root.getAttribute("selectedRow")));
		}
		if (root.hasAttribute("repeatT")) {
			repeatT.set(Integer.parseInt(root.getAttribute("repeatT")));
		}

		
		root.setAttribute("selectedRow",""+selectedCanvas.get().getSelectedRow());
		root.setAttribute("selectedCanvas",""+
		Arrays.asList(eventCanvas,bassCanvas,guitarCanvas,acousticCanvas,drumCanvas).indexOf(selectedCanvas.get()));
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
				DrumVal.lookup(sNode.getAttribute("v")).ifPresent(inputVal -> {
					//DrumVal inputVal = DrumVal.lookup(sNode.getAttribute("v"));
					int stringNum = Integer.parseInt(sNode.getAttribute("n"));							
					drumCanvas.setSelectedRow(stringNum);
					drumCanvas.setSelectedValue(inputVal);
					
				} );
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
		updateMeasureLinePositions();
		
		repaintCanvases();
	
	}

	
	void saveXML(File file) throws Exception {

		DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.newDocument();
		Element root = doc.createElement("tabData");
		root.setAttribute("cursorT",""+cursorT.get());
		root.setAttribute("repeatT",""+cursorT.get());
		root.setAttribute("stopT",""+stopT0.get());
		root.setAttribute("selectedRow",""+selectedCanvas.get().getSelectedRow());
		root.setAttribute("selectedCanvas",""+
		Arrays.asList(eventCanvas,bassCanvas,guitarCanvas,acousticCanvas,drumCanvas).indexOf(selectedCanvas.get()));
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
		t.transform(new DOMSource(doc), new StreamResult(file));
		t.transform(new DOMSource(doc), new StreamResult(System.out));
		
	
		
	}
	AbstractAction rToA(Runnable r) {
		return new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {			
				r.run();
			}			
		};
	}
	
	void loadSoundfonts() {
		File file = new File("config/defaultSoundfonts.xml");
		try {
			DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
			DocumentBuilder db = dbf.newDocumentBuilder();
			Document doc = db.parse(file);
			Element root = doc.getDocumentElement();
			if (root.hasAttribute("defaultSoundfont")) {
				this.defaultSoundfontFile = new File(root.getAttribute("defaultSoundfont"));
			}
			for (Object[] arr: Arrays.asList(
					new Object[] {bassCanvas,"bass"},
					new Object[] {guitarCanvas,"electric"},
					new Object[] {acousticCanvas,"acoustic"},
					new Object[] {drumCanvas,"drums"})) {
				NodeList kids = root.getElementsByTagName(arr[1].toString());
				IntStream.range(0, kids.getLength()).mapToObj(i->(Element) kids.item(i))
				.findFirst().ifPresent(element -> {
					File soundfontFile = defaultSoundfontFile;
					if (element.hasAttribute("soundfontFile")) {
						soundfontFile = new File(element.getAttribute("soundfontFile"));						
					}
					int bank = Integer.parseInt(element.getAttribute("bank"));
					int program = Integer.parseInt(element.getAttribute("program"));
					((SoundfontPlayer) arr[0]).initializeMidi(soundfontFile, bank, program);
				});
			}
					
			
		} catch (Exception ex) {
			ex.printStackTrace();
		}
	}
	
}