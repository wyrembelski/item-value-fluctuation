/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

/**
 * A sortable/filterable sidebar list of the user's biggest bank movers, with a per-item detail
 * view and a "?" help view. It READS the existing pricing engine ({@link PriceService}) — the same
 * singletons the overlay uses — and never re-fetches or re-architects.
 *
 * <p>Strict thread discipline: all {@code client} / {@link ItemManager} / {@link ItemResolver}
 * reads happen inside {@link ClientThread#invoke}; an immutable {@link PanelRowData} snapshot then
 * crosses to the EDT via {@link SwingUtilities#invokeLater}, where all Swing mutation happens.
 *
 * <p>{@code @Singleton}: the plugin attaches one instance to one navigation button, and every
 * refresh trigger (activation, bank change, price nudge) plus teardown must address that same
 * instance's debounce timer and snapshot state.
 *
 * <p>Lifecycle: RuneLite reuses the same singleton instance across disable/re-enable cycles, so the
 * panel is restartable. A monotonic {@code generation} token is bumped on both {@link #activate()}
 * and {@link #teardown()}; refresh nudges and the debounce callback no-op unless the generation they
 * captured is still the active one, and {@link #applySnapshot} rejects snapshots from a stale
 * generation. This both lets a re-enabled panel repopulate and prevents an in-flight gather from
 * mutating Swing after shutdown.
 */
@Singleton
class ItemValueFluctuationPanel extends PluginPanel
{
	/** Matches the tooltip overlay's movement text colours so the panel reads consistently. */
	private static final Color GAIN_TEXT = new Color(0x55, 0xDD, 0x55);
	private static final Color LOSS_TEXT = new Color(0xFF, 0x5B, 0x5B);

	/** Debounce window: coalesce refresh nudges so bank/price churn can't rebuild many times/sec. */
	private static final int REFRESH_DEBOUNCE_MS = 350;

	// --- Non-Swing dependencies (safe to read from any thread) ---
	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final ItemResolver itemResolver;
	private final PriceService priceService;
	private final ItemValueFluctuationConfig config;
	private final ConfigManager configManager;
	private final ItemValueFluctuationOverlay overlay;

	/**
	 * Lifecycle generation. Even = inactive (torn down / never activated), odd = active. Bumped on
	 * {@link #activate()} (to active) and {@link #teardown()} (to inactive). Checked by every
	 * refresh path so stale work from a prior enable can't touch the UI. An {@link AtomicInteger}
	 * so the value published by the EDT is visible to the client thread that reads it.
	 */
	private final AtomicInteger generation = new AtomicInteger(0);

	// --- Swing state: all created inside initUi() on the EDT (see activate()). Null until then. ---
	private JComboBox<MoverSort.Mode> sortCombo;
	private JComboBox<MoverView.Mode> viewCombo;
	private JComboBox<TimeFrame> timeFrameCombo;
	private JCheckBox ringToggle;
	private JCheckBox highlightsToggle;
	private JCheckBox placeholdersToggle;
	private JButton sortDirButton;

	/** Sort direction: true = natural (descending for the gp/%/value modes), false = reversed. */
	private boolean sortDescending = true;
	private JPanel listView;
	private JPanel listContainer;
	private JPanel detailView;
	private JPanel detailBody;
	private JPanel helpView;
	private Timer debounceTimer;

	/**
	 * A small, greyed "last updated" line in the header, shown whenever there is bank data (live or
	 * snapshot): "Updated &lt;time&gt;" while the bank is open, "Bank as of &lt;time&gt;" once it is
	 * closed and we render the remembered snapshot. Hidden only when there is no data yet. EDT only.
	 */
	private JLabel snapshotHint;

	/**
	 * The one-line aggregate-movement summary of all currently-shown (tracked) items, e.g.
	 * "Tracked: +4.2M (+1.8%)". Computed on the client thread (pure arithmetic over the gathered
	 * rows), carried across in the snapshot, rendered here. Hidden when there are no rows. EDT only.
	 */
	private JLabel trackedSummary;

	/** The latest aggregate-movement totals carried from the client thread; rendered on the EDT. */
	private MoverSort.TotalMovement trackedTotal = MoverSort.totalMovement(Collections.emptyList());

	/** True once the Swing skeleton exists (EDT only). Used to retain refresh demand before init. */
	private boolean uiReady;

	/**
	 * A refresh requested before the UI was built (EDT only). When {@link #initUi()} finishes it
	 * fires one gather so the panel populates even if a nudge beat construction.
	 */
	private boolean pendingRefresh;

	/** Icons fetched on the client thread, keyed by raw id, consumed when (re)building rows (EDT). */
	private final Map<Integer, AsyncBufferedImage> iconsByRawId = new HashMap<>();

	/** The latest snapshot applied on the EDT; read by the sort/view listeners. */
	private List<PanelRowData> rows = Collections.emptyList();
	private boolean loading;

	/**
	 * The raw contents (itemId + quantity per slot) of the LAST bank the user had open this session,
	 * captured on the client thread inside {@link #rebuildOnClientThread}. When the bank is closed
	 * the container is null, so we re-run the same resolution/pricing/filtering pipeline over this
	 * remembered list instead of showing an empty panel, letting prices (and time-frame cycling)
	 * keep updating off-bank from the still-valid {@link PriceService} caches.
	 *
	 * <p>CLIENT-THREAD ONLY: written and read exclusively inside {@code rebuildOnClientThread} on the
	 * client thread. The one off-client-thread touch is {@link #clearBankSnapshot()} (logout/teardown),
	 * which replaces the reference atomically; the field is {@code volatile} so that clear is safely
	 * published to the client thread. IN-MEMORY ONLY — never persisted (stale bank data shown as
	 * current would mislead).
	 */
	private volatile List<BankSlot> bankSnapshot;

	/**
	 * When {@link #bankSnapshot} was captured, in wall-clock time, so the panel can show the user
	 * WHEN their last bank visit was ("Bank as of 3:42 PM"). Follows the exact same lifecycle and
	 * threading discipline as {@link #bankSnapshot}: written on the client thread in
	 * {@link #rebuildOnClientThread} right where the snapshot is built, cleared together with the
	 * snapshot in {@link #clearBankSnapshot()}; {@code volatile} so that clear publishes safely.
	 * IN-MEMORY ONLY — never persisted.
	 */
	private volatile Instant bankSnapshotCapturedAt;

	/** True on the EDT while the panel is rendering from {@link #bankSnapshot} (bank closed). */
	private boolean showingSnapshot;

	/**
	 * The last-data-gather time carried across to the EDT, so {@link #updateSnapshotHint()} can
	 * render a locale/zone-aware "last updated" line for either render mode. EDT only; null only
	 * when there is no bank data yet this session (hint hidden).
	 */
	private Instant snapshotHintTime;

	@Inject
	ItemValueFluctuationPanel(Client client, ClientThread clientThread, ItemManager itemManager,
		ItemResolver itemResolver, PriceService priceService, ItemValueFluctuationConfig config,
		ConfigManager configManager, ItemValueFluctuationOverlay overlay)
	{
		super(false);
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.itemResolver = itemResolver;
		this.priceService = priceService;
		this.config = config;
		this.configManager = configManager;
		this.overlay = overlay;
		// NO Swing is created here: Guice does not guarantee EDT construction. Everything Swing
		// (controls, panels, the debounce timer) is built in initUi() on the EDT via activate().
	}

	// ------------------------------------------------------------------
	// Lifecycle (called by the plugin; see activate/teardown)
	// ------------------------------------------------------------------

	/**
	 * Activates the panel for a (re)enabled plugin. Marks the generation active and, on the EDT,
	 * builds the Swing skeleton if it does not exist yet, then kicks a refresh. Safe to call from
	 * any thread; non-blocking.
	 */
	void activate()
	{
		// Move to the next odd (active) generation.
		int g = generation.get();
		generation.set((g % 2 == 0) ? g + 1 : g + 2);

		runOnEdt(() ->
		{
			if (!uiReady)
			{
				initUi();
			}
			refresh();
		});
	}

	/**
	 * Marks the panel inactive and stops the debounce timer, both on the EDT. Bumping the generation
	 * makes any refresh nudge already queued, any pending debounce callback, and any in-flight
	 * client-thread gather no-ops for the old generation, so none can mutate Swing after shutdown.
	 * Cheap, non-blocking. Called from the plugin's {@code shutDown()}.
	 */
	void teardown()
	{
		// Move to the next even (inactive) generation.
		int g = generation.get();
		generation.set((g % 2 == 0) ? g + 2 : g + 1);

		// Drop the remembered bank contents so nothing leaks across a disable/re-enable or lingers
		// in memory after shutdown. In-memory only; never persisted.
		clearBankSnapshot();

		runOnEdt(() ->
		{
			if (debounceTimer != null)
			{
				debounceTimer.stop();
			}
		});
	}

	/**
	 * Forgets the last-seen bank contents. Called on logout/hop (from the plugin) and on teardown.
	 * The field is {@code volatile}, so this is safe to call from any thread; the next client-thread
	 * rebuild then sees an empty source and the panel returns to its placeholder state.
	 */
	void clearBankSnapshot()
	{
		bankSnapshot = null;
		bankSnapshotCapturedAt = null;
		runOnEdt(() ->
		{
			showingSnapshot = false;
			snapshotHintTime = null;
			updateSnapshotHint();
		});
	}

	@Override
	public void onActivate()
	{
		// Sidebar opened: refresh if we are active. (The plugin's activate() handles enable.)
		refresh();
	}

	/**
	 * Requests a (debounced) rebuild. Safe to call from any thread: it only schedules work onto the
	 * EDT, which in turn schedules the client-thread gather. No-op while the panel is inactive, so a
	 * late price/bank nudge after shutdown does nothing. If the UI is not built yet the demand is
	 * retained and fired once {@link #initUi()} completes.
	 */
	void refresh()
	{
		if (!isActive())
		{
			return;
		}
		runOnEdt(() ->
		{
			if (!isActive())
			{
				return;
			}
			if (!uiReady)
			{
				pendingRefresh = true;
				return;
			}
			// Bounded coalescing (NOT a trailing-edge restart): if a gather is already scheduled,
			// let it stand so a steady stream of price-completion nudges can't keep pushing the
			// deadline out and starve the panel until the whole queue drains. Each burst produces
			// one gather after at most REFRESH_DEBOUNCE_MS; the next nudge after that gather starts
			// a fresh one-shot window, giving the list incremental updates during a long load.
			if (!debounceTimer.isRunning())
			{
				debounceTimer.start();
			}
		});
	}

	/** Active == odd generation. Read on either thread. */
	private boolean isActive()
	{
		return (generation.get() % 2) == 1;
	}

	private static void runOnEdt(Runnable r)
	{
		if (SwingUtilities.isEventDispatchThread())
		{
			r.run();
		}
		else
		{
			SwingUtilities.invokeLater(r);
		}
	}

	// ------------------------------------------------------------------
	// UI construction (EDT ONLY — invoked from activate())
	// ------------------------------------------------------------------

	/** Builds the Swing skeleton and the debounce timer. EDT only. */
	private void initUi()
	{
		sortCombo = new JComboBox<>(MoverSort.Mode.values());
		viewCombo = new JComboBox<>(MoverView.Mode.values());
		timeFrameCombo = new JComboBox<>(TimeFrame.values());
		ringToggle = new JCheckBox();
		highlightsToggle = new JCheckBox();
		placeholdersToggle = new JCheckBox();
		sortDirButton = new JButton();
		listView = new JPanel(new BorderLayout());
		listContainer = new JPanel();
		detailView = new JPanel(new BorderLayout());
		detailBody = new JPanel();
		helpView = new JPanel(new BorderLayout());

		// "Last updated" line: subtle, greyed. Text/tooltip are set per-render in updateSnapshotHint
		// ("Updated <time>" live vs "Bank as of <time>" off-bank); hidden until there is bank data.
		snapshotHint = new JLabel();
		snapshotHint.setForeground(ColorScheme.LIGHT_GRAY_COLOR.darker());
		snapshotHint.setFont(FontManager.getRunescapeSmallFont());
		snapshotHint.setAlignmentX(LEFT_ALIGNMENT);
		snapshotHint.setVisible(false);

		// Aggregate-movement summary of the tracked items, shown at the top of the controls stack.
		// Bold so it reads as the headline number; colour + text set in updateTrackedSummary().
		trackedSummary = new JLabel();
		trackedSummary.setFont(FontManager.getRunescapeBoldFont());
		trackedSummary.setToolTipText("Combined movement of the items shown below, over the selected"
			+ " time frame (value-weighted).");
		trackedSummary.setAlignmentX(LEFT_ALIGNMENT);
		trackedSummary.setVisible(false);

		// The timer fires on the EDT and only schedules the client-thread gather; it never reads
		// client. It bails if the plugin was torn down between the restart() and the fire.
		debounceTimer = new Timer(REFRESH_DEBOUNCE_MS, e ->
		{
			if (!isActive())
			{
				return;
			}
			final int g = generation.get();
			clientThread.invoke(() -> rebuildOnClientThread(g));
		});
		debounceTimer.setRepeats(false);

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		add(buildHeader(), BorderLayout.NORTH);

		buildListView();
		buildDetailView();
		buildHelpView();

		add(listView, BorderLayout.CENTER);

		uiReady = true;
		if (pendingRefresh)
		{
			pendingRefresh = false;
			refresh();
		}
	}

	// ------------------------------------------------------------------
	// Data gather (CLIENT THREAD ONLY)
	// ------------------------------------------------------------------

	/**
	 * Walks the full bank {@link ItemContainer} once, mirroring the overlay's exact gates, builds an
	 * immutable {@link PanelRowData} list, and hands it to the EDT. Client API only — no Swing here.
	 * Carries the generation that scheduled it so a snapshot from a torn-down panel is rejected.
	 */
	private void rebuildOnClientThread(int gen)
	{
		if (gen != generation.get())
		{
			return;
		}

		priceService.ensureLatestFresh();

		final TimeFrame timeFrame = config.timeFrame();
		final boolean latestLoaded = priceService.isLatestLoaded();
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);

		// Capture the raw bank contents (BEFORE any filtering) as this session's "last seen" snapshot
		// whenever the bank is actually open. When it is closed (container null) we fall back to the
		// remembered snapshot so the panel keeps showing the user's movers off-bank. The pricing /
		// filtering pipeline below runs identically over whichever source we pick.
		final List<BankSlot> source;
		final boolean fromSnapshot;
		if (bank != null)
		{
			final List<BankSlot> captured = new ArrayList<>();
			for (Item item : bank.getItems())
			{
				if (item == null)
				{
					continue;
				}
				final int itemId = item.getId();
				final int quantity = item.getQuantity();
				if (itemId <= 0 || quantity <= 0)
				{
					continue;
				}
				if (!config.includePlaceholders() && isPlaceholder(itemId))
				{
					continue; // placeholder the user does not actually own
				}
				captured.add(new BankSlot(itemId, quantity));
			}
			bankSnapshot = Collections.unmodifiableList(captured);
			// Stamp WHEN this snapshot was taken so the off-bank hint can say "Bank as of <time>".
			// Same client-thread-confined write as the snapshot itself.
			bankSnapshotCapturedAt = Instant.now();
			source = bankSnapshot;
			fromSnapshot = false;
		}
		else
		{
			// Bank closed: reuse the last-seen snapshot. If there is none yet (never opened the bank
			// this session) this is empty, so the panel keeps its existing placeholder state.
			final List<BankSlot> remembered = bankSnapshot;
			source = remembered;
			fromSnapshot = remembered != null && !remembered.isEmpty();
		}

		// The capture time to display with the off-bank hint (only meaningful when fromSnapshot).
		final Instant capturedAt = bankSnapshotCapturedAt;

		final List<PanelRowData> gathered = new ArrayList<>();
		final List<AsyncBufferedImage> icons = new ArrayList<>();

		if (source != null && latestLoaded)
		{
			for (BankSlot slot : source)
			{
				final int itemId = slot.itemId;
				final int quantity = slot.quantity;

				// Belt-and-suspenders: never surface a placeholder (quantity 0, an item you do not
				// actually own). The capture loop already filters these, but passesPreCheck below
				// treats quantity via Math.max(1, quantity), so a 0 here would read as owning one.
				if (itemId <= 0 || quantity <= 0)
				{
					continue;
				}

				final List<Component> components = itemResolver.resolve(itemId);
				if (components.isEmpty() || ItemResolver.isAllFixedPrice(components))
				{
					continue;
				}

				final Long combinedCurrent = combinedLatestLow(components);
				if (combinedCurrent == null
					|| !ItemValueFluctuationOverlay.passesPreCheck(combinedCurrent, quantity,
						config.minItemValue(), config.minTotalValue()))
				{
					continue;
				}

				// Single warm-up pass (mirrors the overlay): request each non-fixed component for the
				// active frame, and YEAR too when the ring is on. De-duped + rate-capped by the
				// service, so this never fights the overlay's own prefetch.
				requestComponents(components, timeFrame);
				if (config.highlightNearYearHigh() && timeFrame != TimeFrame.YEAR)
				{
					requestComponents(components, TimeFrame.YEAR);
				}

				final PriceFluctuation f =
					priceService.getCachedCombined(itemId, components, timeFrame);
				if (f == null)
				{
					// Value-worthy but not priced yet: omit the row (no placeholder); it fills in on
					// a later refresh once the warm-up fetches above land.
					continue;
				}

				// Share the overlay's canonical near-high gate so panel markers and bank rings can
				// never disagree (alch-floor, yearly-range, value and currency rules live in one
				// place). Runs on the client thread, as required.
				final ItemValueFluctuationOverlay.NearHighData nh = config.highlightNearYearHigh()
					? overlay.nearHighFor(itemId, components, quantity)
					: null;
				final NearHighLevel nearHigh = nh == null ? NearHighLevel.NONE : nh.level;
				final long yearHigh = nh == null ? 0L : nh.yearHigh;

				// Admit a row only when the overlay would ACTUALLY mark the item: either it paints a
				// movement colour (minGpSwing, highlight mode and the smallest gain/loss tier all
				// pass) or the near-high ring applies. A big % on a sub-swing move, a gain while
				// "Losses only" is set, or a tiny tier-floor miss is omitted here just as it is from
				// the bank, so the panel and the overlay can never show different sets of items.
				final boolean highlighted = overlay.movementColor(f, quantity) != null;
				if (!highlighted && nearHigh == NearHighLevel.NONE)
				{
					continue;
				}

				final String name = resolveName(f.getItemId());
				final boolean pricedFromComponents = components.size() > 1;
				final String pricedViaName = pricedViaName(itemId, components);

				gathered.add(new PanelRowData(itemId, name, quantity, f, highlighted, nearHigh,
					yearHigh, pricedFromComponents, pricedViaName, components.size()));
				icons.add(itemManager.getImage(itemId, quantity, false));
			}
		}

		final boolean stillLoading = !latestLoaded
			|| priceService.pendingCount(timeFrame) > 0;
		final List<PanelRowData> snapshot = Collections.unmodifiableList(gathered);
		final List<AsyncBufferedImage> iconSnapshot = Collections.unmodifiableList(icons);
		final boolean renderingFromSnapshot = fromSnapshot;
		// Carry the capture time for BOTH render modes: when the bank is open this is the instant we
		// just (re)captured the snapshot above (the live "Updated <time>"); when closed it is the
		// last bank visit's capture time (the "Bank as of <time>"). Either way it reflects the LAST
		// ACTUAL DATA GATHER - we never poll in the background, so off-bank it stays frozen there
		// (an honest staleness signal), and null only when there is no bank data at all this session.
		final Instant renderingCapturedAt = capturedAt;
		// Aggregate movement of the tracked (gathered) items: pure arithmetic over the data already
		// in each row, computed here on the client thread so the EDT never walks widgets for it.
		final MoverSort.TotalMovement total = MoverSort.totalMovement(snapshot);
		SwingUtilities.invokeLater(() -> applySnapshot(gen, snapshot, iconSnapshot, timeFrame,
			stillLoading, renderingFromSnapshot, renderingCapturedAt, total));
	}

	/** Sum of each component's bulk /latest low times its quantity, or {@code null} if unknown. */
	@Nullable
	private Long combinedLatestLow(List<Component> components)
	{
		long sum = 0L;
		for (Component c : components)
		{
			final long perItem;
			if (ItemResolver.isFixedPrice(c.getTradeableId()))
			{
				perItem = ItemResolver.fixedPrice(c.getTradeableId());
			}
			else
			{
				final Long low = priceService.getLatestLow(c.getTradeableId());
				if (low == null)
				{
					return null;
				}
				perItem = low;
			}
			sum += perItem * c.getPerItemQuantity();
		}
		return sum;
	}

	private void requestComponents(List<Component> components, TimeFrame timeFrame)
	{
		for (Component c : components)
		{
			if (!ItemResolver.isFixedPrice(c.getTradeableId()))
			{
				priceService.request(c.getTradeableId(), timeFrame);
			}
		}
	}

	private String resolveName(int itemId)
	{
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null ? comp.getName() : "Item " + itemId;
	}

	/**
	 * Whether an item id is a bank placeholder (empty "remembered" slot). A real item's composition
	 * returns a placeholder template id of -1; a placeholder returns a non-negative value. Client
	 * thread only (called from the snapshot capture in {@link #rebuildOnClientThread}).
	 */
	private boolean isPlaceholder(int itemId)
	{
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null && comp.getPlaceholderTemplateId() != -1;
	}

	/**
	 * The display name of a single remapped component (e.g. a degraded/combined item priced as its
	 * tradeable base), or {@code null} for a plain tradeable item or a multi-component item.
	 */
	@Nullable
	private String pricedViaName(int rawId, List<Component> components)
	{
		if (components.size() != 1)
		{
			return null;
		}
		final Component only = components.get(0);
		if (only.getTradeableId() == rawId)
		{
			return null;
		}
		return resolveName(only.getTradeableId());
	}

	// ------------------------------------------------------------------
	// UI construction (EDT)
	// ------------------------------------------------------------------

	private JPanel buildHeader()
	{
		final JPanel header = new JPanel(new BorderLayout());
		header.setBackground(ColorScheme.DARK_GRAY_COLOR);
		header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

		final JLabel title = new JLabel("Item Value Fluctuation");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);

		final JButton help = new JButton("?");
		help.setFocusable(false);
		help.setToolTipText("How these values are calculated");
		help.setMargin(new Insets(0, 6, 0, 6));
		help.addActionListener(e -> showHelp());

		header.add(title, BorderLayout.CENTER);
		header.add(help, BorderLayout.EAST);

		// Controls stack under the title.
		final JPanel controls = new JPanel();
		controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
		controls.setBackground(ColorScheme.DARK_GRAY_COLOR);

		sortCombo.setSelectedItem(MoverSort.Mode.PERCENT_DESC);
		viewCombo.setSelectedItem(MoverView.Mode.ALL);
		sortCombo.addActionListener(e -> renderRows());
		viewCombo.addActionListener(e -> renderRows());

		// Sort-direction toggle: flips the selected sort between its natural order and reversed,
		// e.g. to list Losers biggest-drop-first instead of the default biggest-gainer-first.
		sortDirButton.setFocusable(false);
		sortDirButton.setMargin(new Insets(0, 6, 0, 6));
		updateSortDirButton();
		sortDirButton.addActionListener(e ->
		{
			sortDescending = !sortDescending;
			updateSortDirButton();
			renderRows();
		});
		final JPanel sortField = new JPanel(new BorderLayout(4, 0));
		sortField.setBackground(ColorScheme.DARK_GRAY_COLOR);
		sortField.add(sortCombo, BorderLayout.CENTER);
		sortField.add(sortDirButton, BorderLayout.EAST);

		// Inline quick-settings: the two most-toggled options, editable here without the wrench.
		// They write straight to config via ConfigManager; the overlay + panel pick the change up.
		timeFrameCombo.setSelectedItem(config.timeFrame());
		timeFrameCombo.addActionListener(e ->
		{
			final TimeFrame tf = (TimeFrame) timeFrameCombo.getSelectedItem();
			if (tf != null && tf != config.timeFrame())
			{
				configManager.setConfiguration(ItemValueFluctuationConfig.CONFIG_GROUP,
					"timeFrame", tf);
				refresh();
			}
		});

		ringToggle.setText("Mark items near their 1-year high");
		ringToggle.setSelected(config.highlightNearYearHigh());
		ringToggle.setFocusable(false);
		ringToggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		ringToggle.setForeground(Color.WHITE);
		ringToggle.setFont(FontManager.getRunescapeSmallFont());
		ringToggle.setAlignmentX(LEFT_ALIGNMENT);
		// A boxed, full-width strip so this toggle is obvious and not mistaken for a bare line.
		ringToggle.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 4, 3, 4)));
		ringToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		ringToggle.addActionListener(e ->
		{
			configManager.setConfiguration(ItemValueFluctuationConfig.CONFIG_GROUP,
				"highlightNearYearHigh", ringToggle.isSelected());
			refresh();
		});

		// Bank-highlights on/off: colour the bank slots or not. The side panel stays fully
		// populated either way (it gathers independently of the overlay's drawing).
		highlightsToggle.setText("Show bank highlights");
		highlightsToggle.setSelected(config.showBankHighlights());
		highlightsToggle.setFocusable(false);
		highlightsToggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		highlightsToggle.setForeground(Color.WHITE);
		highlightsToggle.setFont(FontManager.getRunescapeSmallFont());
		highlightsToggle.setAlignmentX(LEFT_ALIGNMENT);
		highlightsToggle.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 4, 3, 4)));
		highlightsToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		highlightsToggle.addActionListener(e ->
		{
			configManager.setConfiguration(ItemValueFluctuationConfig.CONFIG_GROUP,
				"showBankHighlights", highlightsToggle.isSelected());
			refresh();
		});

		// Include-placeholders quick-toggle: whether empty "remembered" bank slots (items you no
		// longer own) are shown. Off by default. Writes the same config key the wrench panel uses.
		placeholdersToggle.setText("Include placeholders");
		placeholdersToggle.setSelected(config.includePlaceholders());
		placeholdersToggle.setFocusable(false);
		placeholdersToggle.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		placeholdersToggle.setForeground(Color.WHITE);
		placeholdersToggle.setFont(FontManager.getRunescapeSmallFont());
		placeholdersToggle.setAlignmentX(LEFT_ALIGNMENT);
		placeholdersToggle.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(3, 4, 3, 4)));
		placeholdersToggle.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
		placeholdersToggle.addActionListener(e ->
		{
			configManager.setConfiguration(ItemValueFluctuationConfig.CONFIG_GROUP,
				"includePlaceholders", placeholdersToggle.isSelected());
			refresh();
		});

		// One tidy vertical stack of labeled rows: label on its own line, full-width control under
		// it, uniform gaps. Nothing truncates in the narrow (~225px) panel.
		controls.add(trackedSummary);
		controls.add(verticalGap());
		controls.add(labeledRow("Time frame", timeFrameCombo));
		controls.add(verticalGap());
		controls.add(labeledRow("Sort", sortField));
		controls.add(verticalGap());
		controls.add(labeledRow("Filter", viewCombo));
		controls.add(verticalGap());
		controls.add(ringToggle);
		controls.add(verticalGap());
		controls.add(highlightsToggle);
		controls.add(verticalGap());
		controls.add(placeholdersToggle);
		controls.add(verticalGap());
		controls.add(snapshotHint);

		final JPanel north = new JPanel(new BorderLayout());
		north.setBackground(ColorScheme.DARK_GRAY_COLOR);
		north.add(header, BorderLayout.NORTH);
		north.add(controls, BorderLayout.CENTER);
		return north;
	}

	private void buildListView()
	{
		listView.setBackground(ColorScheme.DARK_GRAY_COLOR);
		listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
		listContainer.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JScrollPane scroll = new JScrollPane(listContainer,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		listView.add(scroll, BorderLayout.CENTER);
	}

	private void buildDetailView()
	{
		detailView.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final JButton back = new JButton("\u2190 Back");
		back.setFocusable(false);
		back.addActionListener(e -> showList());
		detailBody.setLayout(new BoxLayout(detailBody, BoxLayout.Y_AXIS));
		detailBody.setBackground(ColorScheme.DARK_GRAY_COLOR);

		final JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		top.add(back, BorderLayout.WEST);

		detailView.add(top, BorderLayout.NORTH);
		detailView.add(detailBody, BorderLayout.CENTER);
	}

	private void buildHelpView()
	{
		helpView.setBackground(ColorScheme.DARK_GRAY_COLOR);
		final JButton back = new JButton("\u2190 Back");
		back.setFocusable(false);
		back.addActionListener(e -> showList());

		final JPanel top = new JPanel(new BorderLayout());
		top.setBackground(ColorScheme.DARK_GRAY_COLOR);
		top.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		top.add(back, BorderLayout.WEST);

		final JTextArea text = new JTextArea(HELP_TEXT);
		text.setEditable(false);
		text.setLineWrap(true);
		text.setWrapStyleWord(true);
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.setFont(FontManager.getRunescapeSmallFont());
		text.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

		final JScrollPane scroll = new JScrollPane(text,
			ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
			ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.getVerticalScrollBar().setUnitIncrement(16);

		helpView.add(top, BorderLayout.NORTH);
		helpView.add(scroll, BorderLayout.CENTER);
	}

	// ------------------------------------------------------------------
	// Snapshot apply + render (EDT)
	// ------------------------------------------------------------------

	private void applySnapshot(int gen, List<PanelRowData> snapshot, List<AsyncBufferedImage> icons,
		TimeFrame timeFrame, boolean stillLoading, boolean renderingFromSnapshot,
		@Nullable Instant capturedAt, MoverSort.TotalMovement total)
	{
		// Reject a snapshot from a torn-down / superseded generation, or before the UI exists.
		if (gen != generation.get() || !uiReady)
		{
			return;
		}
		this.rows = snapshot;
		this.trackedTotal = total;
		this.loading = stillLoading;
		this.showingSnapshot = renderingFromSnapshot;
		// Keep the capture time for either render mode so the "last updated" line can show for live
		// data too (not only off-bank). Null when there is no bank data at all this session.
		this.snapshotHintTime = capturedAt;
		updateSnapshotHint();
		this.iconsByRawId.clear();
		for (int i = 0; i < snapshot.size(); i++)
		{
			iconsByRawId.put(snapshot.get(i).getRawItemId(), icons.get(i));
		}
		renderRows();
	}

	private void renderRows()
	{
		// Keep the inline quick-settings in sync if the user changed them via the wrench instead.
		// Guard with a value check so setting them here does not re-fire their action listeners
		// into a loop (setSelected/setSelectedItem fire events).
		if (timeFrameCombo.getSelectedItem() != config.timeFrame())
		{
			timeFrameCombo.setSelectedItem(config.timeFrame());
		}
		if (ringToggle.isSelected() != config.highlightNearYearHigh())
		{
			ringToggle.setSelected(config.highlightNearYearHigh());
		}
		if (placeholdersToggle.isSelected() != config.includePlaceholders())
		{
			placeholdersToggle.setSelected(config.includePlaceholders());
		}
		if (highlightsToggle.isSelected() != config.showBankHighlights())
		{
			highlightsToggle.setSelected(config.showBankHighlights());
		}

		updateTrackedSummary();

		listContainer.removeAll();

		final MoverSort.Mode sortMode = (MoverSort.Mode) sortCombo.getSelectedItem();
		final MoverView.Mode viewMode = (MoverView.Mode) viewCombo.getSelectedItem();

		final List<PanelRowData> filtered = new ArrayList<>();
		for (PanelRowData r : rows)
		{
			if (MoverView.predicate(viewMode).test(r))
			{
				filtered.add(r);
			}
		}
		// Natural order for the gp/%/value modes is descending; the toggle reverses it.
		filtered.sort(MoverSort.comparator(sortMode, !sortDescending));

		if (filtered.isEmpty())
		{
			final String msg = loading
				? "Loading prices\u2026"
				: (rows.isEmpty() ? "Open your bank to see movers" : "No items match this filter");
			final JLabel empty = new JLabel(msg);
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setBorder(BorderFactory.createEmptyBorder(10, 4, 10, 4));
			listContainer.add(empty);
		}
		else
		{
			for (PanelRowData r : filtered)
			{
				listContainer.add(buildRow(r));
			}
		}

		listContainer.revalidate();
		listContainer.repaint();
	}

	private JPanel buildRow(PanelRowData r)
	{
		final JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE,
			r.getQuantity() > 1 ? 52 : 42));

		final JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(32, 32));
		final AsyncBufferedImage img = iconsByRawId.get(r.getRawItemId());
		if (img != null)
		{
			img.addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);

		final JPanel text = new JPanel(new GridLayout(2, 1));
		text.setBackground(ColorScheme.DARKER_GRAY_COLOR);

		final JLabel name = new JLabel(r.getName());
		name.setForeground(Color.WHITE);
		name.setFont(FontManager.getRunescapeSmallFont());

		final PriceFluctuation f = r.getFluctuation();
		// Line 2: percent + signed gp change (the key number the gp-change sort uses, now always
		// visible so the sort reads sensibly) + per-item price. e.g. "+12%  +15.3M  (800k ea)".
		final JLabel sub = new JLabel(ItemValueFluctuationOverlay.formatPercent(f.getPercentChange())
			+ "   " + ItemValueFluctuationOverlay.formatSignedGp(r.stackGpChange())
			+ "   (" + QuantityFormatter.quantityToStackSize(f.getCurrentPrice()) + " ea)");
		sub.setForeground(f.isGain() ? GAIN_TEXT : (f.isLoss() ? LOSS_TEXT
			: ColorScheme.LIGHT_GRAY_COLOR));
		sub.setFont(FontManager.getRunescapeSmallFont());

		text.add(name);
		text.add(sub);
		// Line 3 (only for stacks): the whole-stack value and quantity, so both numbers are present.
		if (r.getQuantity() > 1)
		{
			text.setLayout(new GridLayout(3, 1));
			final JLabel stack = new JLabel(
				QuantityFormatter.quantityToStackSize(r.stackValue()) + " GP"
				+ " (\u00d7" + QuantityFormatter.formatNumber(r.getQuantity()) + ")");
			stack.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			stack.setFont(FontManager.getRunescapeSmallFont());
			text.add(stack);
		}
		row.add(text, BorderLayout.CENTER);

		if (r.getNearHigh() != NearHighLevel.NONE)
		{
			final Color ring = r.getNearHigh() == NearHighLevel.AT
				? config.atHighColor() : config.nearHighColor();
			final JLabel marker = new JLabel("\u25c6");
			marker.setForeground(ring);
			marker.setToolTipText(r.getNearHigh() == NearHighLevel.AT
				? "At 1-year high" : "Near 1-year high");
			row.add(marker, BorderLayout.EAST);
		}

		// Swing delivers a click to the deepest component under the cursor and does NOT bubble to
		// the parent, so a listener on the row panel alone would only fire on the background gaps.
		// Attach the same handler to every child (icon, name, value/change text, near-high marker)
		// so a click anywhere on the visible row opens the detail view.
		final MouseAdapter open = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				showDetail(r);
			}
		};
		addClickRecursively(row, open);
		return row;
	}

	/** Attaches {@code listener} to {@code component} and all of its descendants (EDT). */
	private static void addClickRecursively(java.awt.Component component, MouseAdapter listener)
	{
		component.addMouseListener(listener);
		if (component instanceof java.awt.Container)
		{
			for (java.awt.Component child : ((java.awt.Container) component).getComponents())
			{
				addClickRecursively(child, listener);
			}
		}
	}

	// ------------------------------------------------------------------
	// Detail view (EDT)
	// ------------------------------------------------------------------

	private void showDetail(PanelRowData r)
	{
		detailBody.removeAll();

		final PriceFluctuation f = r.getFluctuation();
		final Color changeColor = f.isGain() ? GAIN_TEXT : (f.isLoss() ? LOSS_TEXT
			: ColorScheme.LIGHT_GRAY_COLOR);
		final long qty = Math.max(1, r.getQuantity());

		detailBody.add(detailTitle(r.getName()));

		// "View on Wiki" opens the clicked item's own OSRS Wiki article (which carries the GE price
		// graph) in the browser. Skipped when the name is somehow absent. No network call here -
		// LinkBrowser just hands the URL to the user's browser.
		final JLabel wikiLink = buildWikiLink(r.getName());
		if (wikiLink != null)
		{
			detailBody.add(wikiLink);
		}

		if (r.isPricedFromComponents())
		{
			detailBody.add(detailLine("Priced from " + r.getComponentCount() + " components",
				null, ColorScheme.LIGHT_GRAY_COLOR));
		}
		else if (r.getPricedViaName() != null)
		{
			detailBody.add(detailLine("Priced as: " + r.getPricedViaName(), null,
				ColorScheme.LIGHT_GRAY_COLOR));
		}

		detailBody.add(detailLine("Change",
			ItemValueFluctuationOverlay.formatPercent(f.getPercentChange())
				+ "  (" + signedGp(f.getGpChange()) + ")", changeColor));
		detailBody.add(detailLine("Current",
			QuantityFormatter.formatNumber(f.getCurrentPrice()) + " GP", null));
		detailBody.add(detailLine("Was",
			QuantityFormatter.formatNumber(f.getAnchorPrice()) + " GP", null));
		detailBody.add(detailLine("Window high",
			QuantityFormatter.formatNumber(f.getPeriodHigh()) + " GP", null));
		detailBody.add(detailLine("Window low",
			QuantityFormatter.formatNumber(f.getPeriodLow()) + " GP", null));

		if (qty > 1)
		{
			detailBody.add(detailLine("Stack (" + QuantityFormatter.formatNumber(qty) + ")",
				QuantityFormatter.formatNumber(r.stackValue()) + " GP", null));
			detailBody.add(detailLine("Stack change",
				ItemValueFluctuationOverlay.formatSignedGp(r.stackGpChange()), changeColor));
		}

		if (r.getNearHigh() != NearHighLevel.NONE)
		{
			final Color ring = r.getNearHigh() == NearHighLevel.AT
				? config.atHighColor() : config.nearHighColor();
			final String label = r.getNearHigh() == NearHighLevel.AT
				? "At 1-year high"
				: "Within " + config.nearHighThresholdPct() + "% of 1-year high";
			detailBody.add(detailLine("1-year high",
				QuantityFormatter.formatNumber(r.getYearHigh()) + " GP", ring));
			detailBody.add(detailLine(label, null, ring));
		}

		detailBody.revalidate();
		detailBody.repaint();
		showView(detailView);
	}

	/**
	 * A clickable "View on Wiki" link for the clicked item, styled like a hyperlink (link colour,
	 * hand cursor). Clicking opens the item's OSRS Wiki article via {@link LinkBrowser#browse} (the
	 * sanctioned helper - never {@code java.awt.Desktop}). Returns {@code null} when the name is
	 * null/blank so the caller simply omits the link. EDT only.
	 */
	@Nullable
	private JLabel buildWikiLink(@Nullable String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return null;
		}
		final String url = wikiUrl(name);

		// Styled as an obvious button (accent-coloured border + fill + padding) so it clearly reads
		// as a clickable link rather than plain text. The arrow hints that it opens externally.
		final Color accent = ColorScheme.BRAND_ORANGE;
		final Color idleBg = ColorScheme.DARKER_GRAY_COLOR;
		final Color hoverBg = ColorScheme.DARK_GRAY_HOVER_COLOR;

		final JLabel link = new JLabel("View on Wiki \u2197");
		link.setFont(FontManager.getRunescapeSmallFont());
		link.setForeground(accent);
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setOpaque(true);
		link.setBackground(idleBg);
		link.setHorizontalAlignment(SwingConstants.CENTER);
		link.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(accent, 1),
			BorderFactory.createEmptyBorder(4, 10, 4, 10)));
		link.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		link.setToolTipText("Open " + name.trim() + " on the OSRS Wiki");
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(url);
			}

			@Override
			public void mouseEntered(MouseEvent e)
			{
				link.setBackground(hoverBg);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				link.setBackground(idleBg);
			}
		});
		return link;
	}

	/**
	 * The canonical OSRS Wiki article URL for an item name: {@code
	 * https://oldschool.runescape.wiki/w/<Name>} with spaces replaced by underscores (the wiki's
	 * convention for article paths) and the remaining characters URL-encoded for a path segment.
	 */
	private static String wikiUrl(String name)
	{
		final String underscored = name.trim().replace(' ', '_');
		String encoded;
		try
		{
			// URLEncoder would turn spaces into "+", but we have already swapped them for
			// underscores (which it leaves untouched), so the result is a valid /w/ path segment.
			encoded = URLEncoder.encode(underscored, StandardCharsets.UTF_8.name());
		}
		catch (UnsupportedEncodingException e)
		{
			// UTF-8 is always supported; fall back to the raw underscored name just in case.
			encoded = underscored;
		}
		return "https://oldschool.runescape.wiki/w/" + encoded;
	}

	private JLabel detailTitle(String name)
	{
		final JLabel title = new JLabel(name);
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		title.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		title.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
		return title;
	}

	private JPanel detailLine(String left, @Nullable String right, @Nullable Color color)
	{
		final JPanel line = new JPanel(new BorderLayout());
		line.setBackground(ColorScheme.DARK_GRAY_COLOR);
		line.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
		line.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));
		line.setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);

		final JLabel leftLabel = new JLabel(left);
		leftLabel.setFont(FontManager.getRunescapeSmallFont());
		leftLabel.setForeground(color != null ? color : ColorScheme.LIGHT_GRAY_COLOR);
		line.add(leftLabel, BorderLayout.WEST);

		if (right != null)
		{
			final JLabel rightLabel = new JLabel(right);
			rightLabel.setFont(FontManager.getRunescapeSmallFont());
			rightLabel.setForeground(color != null ? color : Color.WHITE);
			rightLabel.setHorizontalAlignment(SwingConstants.RIGHT);
			line.add(rightLabel, BorderLayout.EAST);
		}
		return line;
	}

	// ------------------------------------------------------------------
	// View swapping (EDT)
	// ------------------------------------------------------------------

	private void showList()
	{
		showView(listView);
	}

	private void showHelp()
	{
		showView(helpView);
	}

	private void showView(JPanel view)
	{
		remove(listView);
		remove(detailView);
		remove(helpView);
		add(view, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	/**
	 * Shows a persistent "last updated" line stamped with WHEN the data was last gathered, in the
	 * user's local time. The wording follows the data source: "Updated &lt;time&gt;" while the bank
	 * is open (live), "Bank as of &lt;time&gt;" once the bank is closed and we are rendering the
	 * remembered snapshot. Hidden entirely when there is no bank data yet this session (nothing to
	 * timestamp). The time reflects the LAST ACTUAL DATA GATHER and does NOT tick in the background
	 * (we never poll off-bank) - a frozen off-bank time is an intentional, honest staleness signal.
	 * EDT only.
	 */
	private void updateSnapshotHint()
	{
		if (snapshotHint == null)
		{
			return;
		}
		if (snapshotHintTime == null)
		{
			// No bank data this session yet: nothing to timestamp.
			snapshotHint.setVisible(false);
			return;
		}
		final String prefix = showingSnapshot ? "Bank as of " : "Updated ";
		snapshotHint.setToolTipText(showingSnapshot
			? "The bank is closed, so these are the movers from the last time you opened your bank"
				+ " this session - prices still update live."
			: "When this list was last gathered from your open bank. It does not refresh in the"
				+ " background; reopen your bank to update it.");
		snapshotHint.setText(formatUpdatedHint(prefix, snapshotHintTime));
		snapshotHint.setVisible(true);
	}

	/**
	 * Builds the "last updated" line text: {@code prefix} followed by a locale/zone-aware time
	 * (short time style; a short date prepended when the capture was not today). EDT only.
	 */
	private static String formatUpdatedHint(String prefix, Instant capturedAt)
	{
		final ZoneId zone = ZoneId.systemDefault();
		final LocalDate capturedDate = capturedAt.atZone(zone).toLocalDate();
		final boolean today = capturedDate.equals(LocalDate.now(zone));
		final DateTimeFormatter formatter = (today
			? DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
			: DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT, FormatStyle.SHORT))
			.withZone(zone);
		return prefix + formatter.format(capturedAt);
	}

	/**
	 * Renders the aggregate-movement summary line from {@link #trackedTotal} (computed on the client
	 * thread). Hidden when there are no tracked rows; coloured green for a net gain, red for a net
	 * loss, grey for ~zero, consistent with the row colouring. EDT only.
	 */
	private void updateTrackedSummary()
	{
		if (trackedSummary == null)
		{
			return;
		}
		if (rows.isEmpty())
		{
			trackedSummary.setVisible(false);
			return;
		}
		final long gp = trackedTotal.getGpChange();
		final StringBuilder text = new StringBuilder("Tracked: ")
			.append(ItemValueFluctuationOverlay.formatSignedGp(gp));
		if (trackedTotal.hasPercent())
		{
			text.append(" (")
				.append(ItemValueFluctuationOverlay.formatPercent(trackedTotal.getPercentChange()))
				.append(')');
		}
		trackedSummary.setText(text.toString());
		trackedSummary.setForeground(gp > 0 ? GAIN_TEXT
			: (gp < 0 ? LOSS_TEXT : ColorScheme.LIGHT_GRAY_COLOR));
		trackedSummary.setVisible(true);
	}

	/** Updates the sort-direction button's glyph/tooltip to reflect the current direction. */
	private void updateSortDirButton()
	{
		sortDirButton.setText(sortDescending ? "\u2193" : "\u2191");
		sortDirButton.setToolTipText(sortDescending
			? "Descending (biggest first) - click for ascending"
			: "Ascending (smallest first) - click for descending");
	}

	private static JLabel smallLabel(String text)
	{
		final JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(FontManager.getRunescapeSmallFont());
		return label;
	}

	/** A left-aligned "label on top, full-width control below" group for the controls stack. */
	private static JPanel labeledRow(String labelText, java.awt.Component control)
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setAlignmentX(LEFT_ALIGNMENT);

		final JLabel label = smallLabel(labelText);
		label.setAlignmentX(LEFT_ALIGNMENT);
		if (control instanceof JComponent)
		{
			((JComponent) control).setAlignmentX(LEFT_ALIGNMENT);
		}
		// Keep controls from stretching tall in the BoxLayout.
		final Dimension pref = control.getPreferredSize();
		control.setMaximumSize(new Dimension(Integer.MAX_VALUE, pref.height));

		row.add(label);
		row.add(control);
		return row;
	}

	/** A small fixed vertical gap between control rows. */
	private static java.awt.Component verticalGap()
	{
		return javax.swing.Box.createVerticalStrut(6);
	}

	private static String signedGp(long gp)
	{
		final String sign = gp > 0 ? "+" : (gp < 0 ? "-" : "");
		return sign + QuantityFormatter.formatNumber(Math.abs(gp)) + " GP";
	}

	/**
	 * Help copy, mirroring the README's "How values are calculated" and "Things to keep in mind".
	 * Always says "1-year", never "all-time" (the wiki history maxes out at one year).
	 */
	private static final String HELP_TEXT =
		"How these values are calculated\n\n"
		+ "This panel shows the instant-sell price now versus the instant-sell price at the start"
		+ " of your chosen time window, as a percent.\n\n"
		+ "Data source: the OSRS Wiki real-time prices feed (the same data RuneLite's own Grand"
		+ " Exchange features use). Values use the instant-sell (low) price - the honest \"what"
		+ " could I actually get for this right now\" number.\n\n"
		+ "Start-of-window price: the anchor the percent is measured against is the first history"
		+ " point in the window, so a 7-day window means roughly 7 days ago, not to the exact"
		+ " second.\n\n"
		+ "Component pricing: untradeable, combination and variant items (degraded Barrows,"
		+ " ornament-kitted gear, a toxic blowpipe, crystal gear, noted items) are valued as the"
		+ " sum of the tradeable parts they are made from, using RuneLite's own item mapping. A"
		+ " \"Priced as\" or \"Priced from N components\" line in the detail view shows when a value"
		+ " is derived this way. Coins and platinum tokens have a fixed value and never"
		+ " fluctuate.\n\n"
		+ "1-year high ring (optional): the gold marker flags an item trading at or near its"
		+ " highest instant-sell price over the past year - the window is 1-year, since the price"
		+ " history only reaches back one year. By default this ignores alch-floored items (price"
		+ " pinned just above their high-alch"
		+ " value) and stable commodities with a tight yearly range, since those sit at their"
		+ " \"high\" permanently without being a real sell signal.\n\n"
		+ "Loading: to stay polite to the free wiki API, history is fetched gradually and cached,"
		+ " so values fill in over a few seconds when you first open a large bank, and history is"
		+ " cached for up to about 30 minutes.\n\n"
		+ "Thin markets: if an item barely trades, a single unusual sale can swing its reported"
		+ " price, so treat big moves on low-volume items with care.";

	/**
	 * One captured bank slot: the raw item id and quantity, exactly as read from the bank container
	 * BEFORE any filtering/resolution. Immutable, Swing-free; written and read only on the client
	 * thread inside {@link #rebuildOnClientThread}.
	 */
	private static final class BankSlot
	{
		private final int itemId;
		private final int quantity;

		private BankSlot(int itemId, int quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}
}
