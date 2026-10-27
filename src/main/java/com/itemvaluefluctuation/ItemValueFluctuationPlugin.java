/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.Keybind;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Item Value Fluctuation",
	description = "Heatmaps your bank by how much each item's price has moved over a chosen"
		+ " time window, so sharp gains or drops stand out at a glance",
	tags = {"bank", "price", "value", "ge", "grand exchange", "fluctuation", "flip", "merch"}
)
public class ItemValueFluctuationPlugin extends Plugin
{
	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ItemValueFluctuationOverlay overlay;

	@Inject
	private ItemValueFluctuationTooltipOverlay tooltipOverlay;

	@Inject
	private ItemValueFluctuationConfig config;

	@Inject
	private PriceService priceService;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private KeyManager keyManager;

	/**
	 * The latched TOGGLE state. Only meaningful in {@link VisibilityMode#TOGGLE}. Mutated on the
	 * AWT/EDT thread by {@link #visibilityListener} and read on the client thread by the overlays,
	 * so it is an {@link AtomicBoolean} for safe cross-thread publication. Session-only: reset to
	 * shown on login/startup and on a mode change; never persisted across logins.
	 */
	private final AtomicBoolean toggleOn = new AtomicBoolean(true);

	/**
	 * Whether the visibility hotkey is currently held down. Used only by the hold modes. Mutated on
	 * the AWT/EDT thread by {@link #visibilityListener}, read on the client thread by the overlays.
	 */
	private final AtomicBoolean keyHeld = new AtomicBoolean(false);

	/**
	 * The single bindable visibility key. {@link HotkeyListener} does nothing while the bound key is
	 * {@link net.runelite.client.config.Keybind#NOT_SET}, so the feature is inert when unbound.
	 * {@code hotkeyPressed()}/{@code hotkeyReleased()} run on the AWT/EDT thread and only mutate the
	 * two {@link AtomicBoolean} flags above — they never touch the client or config directly.
	 */
	private final HotkeyListener visibilityListener =
		new HotkeyListener(() -> config.visibilityHotkey())
		{
			@Override
			public void hotkeyPressed()
			{
				if (config.visibilityMode() == VisibilityMode.TOGGLE)
				{
					toggleOn.set(!toggleOn.get());
				}
				else
				{
					keyHeld.set(true);
				}
			}

			@Override
			public void hotkeyReleased()
			{
				keyHeld.set(false);
			}
		};

	/**
	 * The panel is a Swing {@link javax.swing.JComponent}; its construction must happen on the EDT.
	 * Guice does not guarantee the resolving thread, so we inject a {@link Provider} and resolve the
	 * singleton inside an EDT task (see {@link #startUp()}) rather than injecting the panel directly.
	 */
	@Inject
	private Provider<ItemValueFluctuationPanel> panelProvider;

	/** The resolved singleton panel. Assigned on the EDT in startUp; read by refresh triggers. */
	private volatile ItemValueFluctuationPanel panel;

	/**
	 * The sidebar nav button. Assigned on the EDT in the queued startUp task and read in shutDown
	 * on the client thread, so it MUST be volatile for safe publication — otherwise shutDown could
	 * observe a stale null and skip {@code removeNavigation}, leaking a duplicate toolbar entry.
	 */
	private volatile NavigationButton navButton;

	/**
	 * Lifecycle token bumped by {@link #startUp()} and invalidated by {@link #shutDown()}. Nav-button
	 * construction/registration is queued onto the EDT, so a disable can race ahead of that queued
	 * task. {@code shutDown()} increments this synchronously; the queued startup task captures the
	 * value it was scheduled under and bails if it no longer matches, so it can never register or
	 * activate a panel after the plugin has been shut down (which would leak an untracked button).
	 */
	private final AtomicInteger lifecycle = new AtomicInteger();

	@Override
	protected void startUp()
	{
		priceService.clear();
		priceService.start();
		// Price callbacks arrive off the client thread; nudge a repaint for the overlay AND ask the
		// side panel to refresh. refresh() is thread-safe (it hops to the client thread then EDT),
		// and no-ops until the panel has been resolved on the EDT below.
		priceService.setUpdateListener(() -> clientThread.invoke(this::refreshPanel));
		overlayManager.add(overlay);
		overlayManager.add(tooltipOverlay);

		// Start each session in the right visibility state for the selected mode (shown for TOGGLE
		// and HOLD_TO_HIDE, hidden for HOLD_TO_SHOW) and register the single bindable key. The
		// listener is inert while the key is NOT_SET, so the default (unbound) behaves as before.
		resetVisibilityState();
		keyManager.registerKeyListener(visibilityListener);

		// The icon is not Swing, so it is safe to load here off the EDT.
		final BufferedImage icon = ImageUtil.loadImageResource(
			ItemValueFluctuationPlugin.class, "/com/itemvaluefluctuation/panel_icon.png");

		// Capture the lifecycle token this start-up is scheduled under. If a shutDown() runs before
		// the queued EDT task below executes, it bumps the token and this task bails without
		// registering or activating anything (otherwise it would leak an untracked nav button).
		final int startToken = lifecycle.incrementAndGet();

		// Resolve + construct the Swing PluginPanel on the EDT (Guice does not guarantee the EDT),
		// then build the nav button (which holds the panel) and register it from the same EDT task.
		// Non-blocking: startUp returns immediately.
		SwingUtilities.invokeLater(() ->
		{
			if (lifecycle.get() != startToken)
			{
				// A shutDown() (or a newer startUp) superseded this queued task; do nothing.
				return;
			}
			panel = panelProvider.get();
			navButton = NavigationButton.builder()
				.tooltip("Item Value Fluctuation")
				.icon(icon)
				.priority(7)
				.panel(panel)
				.build();
			clientToolbar.addNavigation(navButton);
			// Activate the (restartable) singleton panel: builds its Swing on the EDT if needed and
			// re-arms every refresh trigger after a prior disable.
			panel.activate();
		});
		log.debug("Item Value Fluctuation started");
	}

	@Override
	protected void shutDown()
	{
		// Invalidate the lifecycle token synchronously so any queued-but-not-yet-run startup EDT
		// task bails instead of registering/activating a panel after shutdown.
		lifecycle.incrementAndGet();
		// Nav button + panel are EDT-owned; remove/teardown on the EDT. Non-blocking.
		final NavigationButton nav = navButton;
		final ItemValueFluctuationPanel p = panel;
		navButton = null;
		panel = null;
		SwingUtilities.invokeLater(() ->
		{
			if (nav != null)
			{
				clientToolbar.removeNavigation(nav);
			}
			if (p != null)
			{
				p.teardown();
			}
		});
		keyManager.unregisterKeyListener(visibilityListener);
		overlayManager.remove(overlay);
		overlayManager.remove(tooltipOverlay);
		// The cache is deliberately NOT cleared on a time frame change (the overlay dims the
		// previous time frame's colours until new values arrive); it is fully reset only here.
		priceService.setUpdateListener(null);
		priceService.shutdown();
		log.debug("Item Value Fluctuation stopped");
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.BANK)
		{
			refreshPanel();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		// Closing/walking away from the bank does NOT change the bank ItemContainer and produces no
		// price update, so none of the other refresh triggers fire. Nudge the panel exactly once on
		// the bank-close transition: rebuildOnClientThread then sees a null bank container, reuses
		// the remembered snapshot (fromSnapshot=true) and flips the "last updated" line to its
		// "Bank as of <time>" wording. One-shot per close (WidgetClosed fires once); NOT polling.
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			refreshPanel();
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		// Drop the side panel's remembered bank snapshot when the player leaves the game world so it
		// never persists across accounts/sessions. The snapshot is in-memory only and is rebuilt the
		// next time the bank is opened.
		final GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			final ItemValueFluctuationPanel p = panel;
			if (p != null)
			{
				p.clearBankSnapshot();
			}
			// The TOGGLE state is session-only (consistent with the plugin's no-cross-session
			// stance): a new session always starts with the highlights in their mode's default
			// state rather than inheriting a hidden toggle from a previous login.
			resetVisibilityState();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!ItemValueFluctuationConfig.CONFIG_GROUP.equals(event.getGroup()))
		{
			return;
		}
		// Re-initialise the visibility state whenever the mode or the bound key changes, so
		// switching to HOLD_TO_SHOW resets to hidden, TOGGLE/HOLD_TO_HIDE reset to shown, and
		// binding/clearing the key lands on a sane state (a held-key flag can't linger either).
		if ("visibilityMode".equals(event.getKey()) || "visibilityHotkey".equals(event.getKey()))
		{
			resetVisibilityState();
		}
	}

	/**
	 * Resets the session visibility flags for the currently-selected mode: the toggle latch to its
	 * mode default (shown for TOGGLE/HOLD_TO_HIDE, hidden for HOLD_TO_SHOW) and the key-held flag to
	 * false. Called on startUp, on login/hop, and on a visibility config change.
	 */
	private void resetVisibilityState()
	{
		toggleOn.set(VisibilityState.initialToggleOn(config.visibilityMode()));
		keyHeld.set(false);
	}

	/**
	 * The single source of truth for whether the BANK overlay/tooltip should paint right now, read
	 * by both {@link ItemValueFluctuationOverlay} and {@link ItemValueFluctuationTooltipOverlay} so
	 * they can never diverge. The side panel does NOT read this. Safe to call from the client thread
	 * (it only reads volatile/atomic flags and config).
	 */
	boolean isBankOverlayVisible()
	{
		final boolean keyBound = config.visibilityHotkey() != Keybind.NOT_SET;
		return VisibilityState.shouldShow(config.visibilityMode(), keyBound, keyHeld.get(),
			toggleOn.get());
	}

	/** Nudges the panel if it has been resolved. {@code refresh()} is itself thread-safe. */
	private void refreshPanel()
	{
		final ItemValueFluctuationPanel p = panel;
		if (p != null)
		{
			p.refresh();
		}
	}

	@Provides
	ItemValueFluctuationConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ItemValueFluctuationConfig.class);
	}
}
