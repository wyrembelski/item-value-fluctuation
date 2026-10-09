/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Washes each bank slot with the heatmap colour for its item's price movement, and optionally
 * draws a small percent-change label. Kept deliberately dumb and fast: it reads already-computed
 * fluctuations from {@link PriceService}'s cache and asks the service to (re)fetch any it is
 * missing. It never does network or heavy work itself.
 */
@Singleton
class ItemValueFluctuationOverlay extends Overlay
{
	private static final int LABEL_PADDING = 2;
	private static final int STATUS_PADDING = 4;

	/**
	 * Max off-screen (not currently visible) items a single render pass will enqueue for prefetch.
	 * Bounds queue growth on a huge "view all" bank; the service's per-minute rate cap and cache
	 * do the real pacing, and visible slots are always requested regardless of this ceiling.
	 */
	private static final int MAX_PREFETCH_PER_PASS = 500;

	private final Client client;
	private final ItemValueFluctuationConfig config;
	private final PriceService priceService;
	private final ItemResolver itemResolver;
	private final ItemManager itemManager;
	private final ColorScale colorScale;

	/** Time frame seen on the last render, and the one before it (for dimmed stale colours). */
	private TimeFrame lastTimeFrame;
	private TimeFrame previousTimeFrame;

	@Inject
	ItemValueFluctuationOverlay(Client client, ItemValueFluctuationConfig config,
		PriceService priceService, ItemResolver itemResolver, ItemManager itemManager)
	{
		this.client = client;
		this.config = config;
		this.priceService = priceService;
		this.itemResolver = itemResolver;
		this.itemManager = itemManager;
		this.colorScale = new ColorScale(config);
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		trackTimeFrame(config.timeFrame());

		final Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null || container.isHidden())
		{
			return null;
		}

		// The item container scrolls: children that are scrolled out of the viewport still report
		// bounds (sometimes far outside the bank, e.g. over the chatbox). Clip everything to the
		// container's own visible rectangle and skip any slot that falls outside it, so we never
		// paint stray highlights elsewhere on screen.
		final Rectangle viewport = container.getBounds();
		if (viewport == null || viewport.width <= 0 || viewport.height <= 0)
		{
			return null;
		}

		final Shape originalClip = graphics.getClip();
		graphics.setClip(viewport);
		try
		{
			final TimeFrame timeFrame = config.timeFrame();
			final Widget[] items = container.getDynamicChildren();
			if (items == null)
			{
				return null;
			}

			final boolean latestLoaded = priceService.isLatestLoaded();
			priceService.ensureLatestFresh();
			// Two bulk calls price the whole bank for the active timeframe: /latest (above) and the
			// window-start anchor (here). For 6h/24h/7d/30d this replaces the per-item /timeseries
			// burst entirely; 6m/1y have no bulk anchor and still fall back to per-item requests.
			priceService.ensureAnchorFresh(timeFrame);

			// COUNT + PREFETCH from the full bank ItemContainer, not the widgets. The bank only
			// materialises item WIDGETS near the visible scroll region (large banks are
			// virtualised), so iterating widgets would miss most of a 700-item bank. The item
			// container holds every bank item as data regardless of scroll, which is the real
			// "whole bank" the status line and prefetch must reflect.
			final int[] counts = countAndPrefetch(timeFrame, latestLoaded);
			final int expected = counts[0];
			final int resolved = counts[1];

			// RENDER only the visible widgets (we can only paint slots that exist on screen).
			for (Widget item : items)
			{
				if (item == null || item.isHidden() || item.getItemId() <= 0)
				{
					continue;
				}

				final Rectangle bounds = item.getBounds();
				if (bounds == null || bounds.width <= 0 || bounds.height <= 0
					|| !viewport.intersects(bounds))
				{
					continue;
				}

				final int itemId = item.getItemId();
				final int quantity = item.getItemQuantity();
				final List<Component> components = itemResolver.resolve(itemId);
				final PriceFluctuation f = priceService.getCachedCombined(itemId, components, timeFrame);

				if (f == null)
				{
					drawStale(graphics, bounds, itemId, components, quantity);
					// Even without active-timeframe movement data the item may be at/near its
					// 1-year high (the target "flat-but-at-peak" case), so still try the ring.
					drawNearYearHighRing(graphics, bounds, itemId, components, quantity);
					continue;
				}

				final Color tier = colorScale.colorFor(f, quantity);
				if (tier != null)
				{
					graphics.setColor(colorScale.withOverlayAlpha(tier));
					graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);

					if (config.showPercentLabel())
					{
						drawLabel(graphics, bounds, labelText(f, quantity));
					}
				}

				// Layered OVER the movement fill: a gold ring when the item is at/near its 1-year
				// high. Composes with the fill, and is drawn even when there is no movement colour.
				drawNearYearHighRing(graphics, bounds, itemId, components, quantity);
			}

			// Loading indicator. The movement colours for 6h/24h/7d/30d come from the two bulk
			// calls, so the ONLY per-item work left for those is the near-high ring's 1-year data.
			// Report honestly: label it "1-year highs" when the pending work is the ring, not the
			// selected timeframe (otherwise switching to 24h looked like 24h itself was slow).
			final boolean activeFramePending = !timeFrame.hasBulkAnchor()
				&& priceService.pendingCount(timeFrame) > 0;
			final boolean yearPending = config.highlightNearYearHigh()
				&& timeFrame != TimeFrame.YEAR
				&& priceService.pendingCount(TimeFrame.YEAR) > 0;
			final boolean active = !latestLoaded
				? priceService.isLatestRetrying() || expected > 0
				: activeFramePending || yearPending;
			if (resolved < expected && active)
			{
				// Prefer the honest "1-year highs" label when the remaining work is the ring.
				final String what = (!activeFramePending && yearPending)
					? "1-year highs" : timeFrame.getDisplayName();
				drawLoadingIndicator(graphics, viewport, what, resolved, expected);
			}
		}
		finally
		{
			graphics.setClip(originalClip);
		}

		return null;
	}

	/**
	 * Walks the full bank {@link ItemContainer} (every item, independent of scroll/virtualisation),
	 * counts the filter-passing items and how many already have data, and queues fetches for the
	 * rest (de-duped + rate-capped in the service, bounded per pass by {@link #MAX_PREFETCH_PER_PASS}).
	 *
	 * @return {@code [expected, resolved]} for the status line.
	 */
	private int[] countAndPrefetch(TimeFrame timeFrame, boolean latestLoaded)
	{
		final ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null || !latestLoaded)
		{
			return new int[]{0, 0};
		}

		// First pass: collect every value-passing item with its combined value and components, so
		// we can fetch VALUE-FIRST (the expensive items you'd actually sell resolve first, instead
		// of bank order). Also tallies expected/resolved for the status line.
		final List<Eligible> eligible = new ArrayList<>();
		int expected = 0;
		int resolved = 0;

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
				continue; // empty slot / placeholder
			}

			// Resolve to tradeable components. Pure currency (coins/platinum) is never a market
			// mover, so it is skipped entirely: not counted, not queued, not coloured.
			final List<Component> components = itemResolver.resolve(itemId);
			if (components.isEmpty() || ItemResolver.isAllFixedPrice(components))
			{
				continue;
			}

			final Long combinedCurrent = combinedLatestLow(components);
			if (combinedCurrent == null || !shouldFetch(combinedCurrent, quantity))
			{
				continue;
			}

			expected++;
			if (priceService.getCachedCombined(itemId, components, timeFrame) != null)
			{
				resolved++;
			}
			eligible.add(new Eligible(itemId, quantity, components,
				combinedCurrent * Math.max(1, quantity)));
		}

		// Fetch value-first: highest total value first.
		eligible.sort((a, b) -> Long.compare(b.totalValue, a.totalValue));

		int queued = 0;
		for (Eligible e : eligible)
		{
			if (queued >= MAX_PREFETCH_PER_PASS)
			{
				break;
			}
			final boolean priced =
				priceService.getCachedCombined(e.itemId, e.components, timeFrame) != null;

			// Active-timeframe history only needed when the timeframe has NO bulk anchor (6m/1y);
			// 6h/24h/7d/30d are priced by the two bulk calls, no per-item burst.
			if (!timeFrame.hasBulkAnchor() && !priced)
			{
				queued += requestComponents(e.components, timeFrame);
			}
			// Near-1-year-high ring: fetch the YEAR history per item (no bulk shortcut exists for a
			// yearly RANGE). Value-first ordering means your valuable items ring within seconds.
			if (config.highlightNearYearHigh() && timeFrame != TimeFrame.YEAR
				&& queued < MAX_PREFETCH_PER_PASS)
			{
				queued += requestComponents(e.components, TimeFrame.YEAR);
			}
		}

		return new int[]{expected, resolved};
	}

	/** A value-passing bank item collected in the first pass, for value-first fetch ordering. */
	private static final class Eligible
	{
		private final int itemId;
		private final int quantity;
		private final List<Component> components;
		private final long totalValue;

		private Eligible(int itemId, int quantity, List<Component> components, long totalValue)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.components = components;
			this.totalValue = totalValue;
		}
	}

	private String labelText(PriceFluctuation f, int quantity)
	{
		switch (config.labelMode())
		{
			case GP_PER_ITEM:
				return formatSignedGp(f.getGpChange());
			case GP_TOTAL:
				return formatSignedGp(f.getGpChange() * Math.max(1, quantity));
			case PERCENT:
			default:
				return formatPercent(f.getPercentChange());
		}
	}

	private void drawLabel(Graphics2D graphics, Rectangle bounds, String text)
	{
		final int textWidth = graphics.getFontMetrics().stringWidth(text);
		// Bottom edge of the slot: the game draws the stack count top-left and the item icon fills
		// the centre, so the bottom is the clearest place for the label.
		final int x = bounds.x + bounds.width - textWidth - LABEL_PADDING;
		final int y = bounds.y + bounds.height - LABEL_PADDING;

		drawOutlined(graphics, text, x, y);
	}

	/**
	 * A self-contained loading "card" with its own dark background, drawn at the BOTTOM of the bank
	 * viewport (over the dead strip near the bank buttons, not over item slots where it fought the
	 * icons and the game's own text). Shows the plugin name so it is clear what is running, then the
	 * scan label + count, a filling progress bar, and the item most recently priced (value-first
	 * ordering means your valuable items tick by first).
	 */
	private void drawLoadingIndicator(Graphics2D graphics, Rectangle viewport, String whatLabel,
		int resolved, int expected)
	{
		final int lineH = graphics.getFontMetrics().getHeight();
		final int pad = 6;
		final int cardW = Math.min(190, viewport.width - 8);
		final int cardH = pad + lineH * 3 + 6 + 5 + pad; // title + status + name + bar
		// Bottom-left of the viewport, lifted clear of the very edge.
		final int cardX = viewport.x + 4;
		final int cardY = viewport.y + viewport.height - cardH - 4;

		final Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_ON);

		// Card background + border so the text always reads regardless of what is behind it.
		graphics.setColor(new Color(0x1A, 0x1A, 0x1A, 235));
		graphics.fillRoundRect(cardX, cardY, cardW, cardH, 8, 8);
		graphics.setColor(new Color(0xC6, 0x9B, 0x3A)); // muted gold edge
		graphics.drawRoundRect(cardX, cardY, cardW, cardH, 8, 8);

		final int x = cardX + pad;
		int y = cardY + pad + lineH - 3;

		// Title (plugin name) so the on-screen loader is identifiable.
		graphics.setColor(new Color(0xFF, 0xD5, 0x5A));
		graphics.setFont(graphics.getFont().deriveFont(Font.BOLD));
		graphics.drawString("Item Value Fluctuation", x, y);
		graphics.setFont(graphics.getFont().deriveFont(Font.PLAIN));

		// Scan label + count.
		y += lineH;
		graphics.setColor(Color.WHITE);
		graphics.drawString("Scanning " + whatLabel + "\u2026 " + resolved + "/" + expected, x, y);

		// Progress bar.
		final int barW = cardW - 2 * pad;
		final int barH = 5;
		final int barY = y + 4;
		final double frac = expected > 0 ? Math.min(1.0, resolved / (double) expected) : 0.0;
		graphics.setColor(new Color(0x30, 0x30, 0x30));
		graphics.fillRect(x, barY, barW, barH);
		graphics.setColor(new Color(0x4C, 0xAF, 0x50));
		graphics.fillRect(x, barY, (int) (barW * frac), barH);
		graphics.setColor(new Color(0, 0, 0, 160));
		graphics.drawRect(x, barY, barW, barH);

		// Item most recently priced (resolved on the client render thread here).
		final int lastId = priceService.getLastResolvedItemId();
		if (lastId > 0)
		{
			final ItemComposition comp = itemManager.getItemComposition(lastId);
			if (comp != null)
			{
				graphics.setColor(new Color(0xAA, 0xAA, 0xAA));
				graphics.setFont(graphics.getFont().deriveFont(
					graphics.getFont().getSize2D() - 1f));
				String name = comp.getName();
				// Keep the flashing name inside the card.
				while (name.length() > 4
					&& graphics.getFontMetrics().stringWidth(name) > barW)
				{
					name = name.substring(0, name.length() - 2);
				}
				graphics.drawString(name, x, barY + barH + lineH - 2);
				graphics.setFont(graphics.getFont().deriveFont(
					graphics.getFont().getSize2D() + 1f));
			}
		}

		if (aa != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
	}

	/** Draws white text with a full 8-direction black outline so it reads on any fill colour. */
	private static void drawOutlined(Graphics2D graphics, String text, int x, int y)
	{
		graphics.setColor(Color.BLACK);
		for (int dx = -1; dx <= 1; dx++)
		{
			for (int dy = -1; dy <= 1; dy++)
			{
				if (dx != 0 || dy != 0)
				{
					graphics.drawString(text, x + dx, y + dy);
				}
			}
		}
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, x, y);
	}

	/**
	 * Whether this slot is worth a /timeseries request: the cheap, quantity-aware filters
	 * (minItemValue, minTotalValue) are pre-checked against the combined /latest value (summed
	 * across the item's components). minGpSwing needs the anchor, so it is still applied after the
	 * fetch by {@link ColorScale}.
	 */
	private boolean shouldFetch(long combinedCurrent, int quantity)
	{
		return passesPreCheck(combinedCurrent, quantity, config.minItemValue(),
			config.minTotalValue());
	}

	/**
	 * Sum of each component's bulk /latest low times its per-item quantity, or {@code null} if any
	 * component's latest low is not yet known (so the item is not yet pricable). Fixed-price
	 * components (currency) contribute their flat value.
	 */
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

	/**
	 * Enqueues a /timeseries request for each DISTINCT non-fixed component (fixed-price currency
	 * needs no fetch). De-duped + rate-capped by the service.
	 *
	 * @return the number of components that were ACTUALLY admitted to the fetch queue by this call
	 *     (not merely attempted). Keys that are already cached-fresh, queued, in flight or backing
	 *     off count as zero, so the per-pass prefetch bound only ever reflects real new work and
	 *     cannot be exhausted by no-op re-requests for items whose data is already loaded.
	 */
	private int requestComponents(List<Component> components, TimeFrame timeFrame)
	{
		int admitted = 0;
		for (Component c : components)
		{
			if (!ItemResolver.isFixedPrice(c.getTradeableId())
				&& priceService.request(c.getTradeableId(), timeFrame))
			{
				admitted++;
			}
		}
		return admitted;
	}

	/**
	 * The canonical movement highlight colour for a priced slot, or {@code null} when the overlay
	 * would NOT paint it (fails minItemValue/minTotalValue/minGpSwing, is hidden by the highlight
	 * mode, or falls below the smallest configured gain/loss tier). Shared with the side panel so
	 * its row membership can never diverge from what the overlay actually highlights.
	 */
	@Nullable
	Color movementColor(PriceFluctuation f, int quantity)
	{
		return colorScale.colorFor(f, quantity);
	}

	/**
	 * Pure filter pre-check on the bulk /latest price. An unknown price (no entry, or the bulk map
	 * has not loaded) never passes, so an unfiltered item can never enter the /timeseries queue.
	 */
	static boolean passesPreCheck(@Nullable Long latest, int quantity, long minItemValue,
		long minTotalValue)
	{
		return latest != null
			&& latest >= minItemValue
			&& latest * Math.max(1, quantity) >= minTotalValue;
	}

	/**
	 * Tracks the time frame the user just left. Called every render, so a switch is noticed even
	 * if the bank was closed in between.
	 */
	private void trackTimeFrame(TimeFrame current)
	{
		if (current != lastTimeFrame)
		{
			if (lastTimeFrame != null)
			{
				previousTimeFrame = lastTimeFrame;
			}
			lastTimeFrame = current;
		}
	}

	/**
	 * While the active time frame loads, shows the PREVIOUS time frame's cached colour dimmed (no
	 * label) so the bank does not blank out on a switch.
	 */
	private void drawStale(Graphics2D graphics, Rectangle bounds, int itemId,
		List<Component> components, int quantity)
	{
		final TimeFrame previous = previousTimeFrame;
		if (previous == null || previous == lastTimeFrame)
		{
			return;
		}
		final PriceFluctuation stale = priceService.getCachedCombined(itemId, components, previous);
		if (stale == null)
		{
			return;
		}
		final Color tier = colorScale.colorFor(stale, quantity);
		if (tier != null)
		{
			graphics.setColor(colorScale.dimmed(tier));
			graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
		}
	}

	/**
	 * Draws the near-1-year-high ring over a slot when the toggle is on and the item is at/near its
	 * 1-year high. Gated by the value filters only (minItemValue AND minTotalValue), NOT by
	 * minGpSwing or the highlight mode. A 2px inset rectangle outline layered over any movement
	 * fill. If the item's combined current price or combined 1y high is not available yet, nothing
	 * is drawn (it fills in later) — never block or guess.
	 */
	private void drawNearYearHighRing(Graphics2D graphics, Rectangle bounds,
		int rawItemId, List<Component> components, int quantity)
	{
		if (!config.highlightNearYearHigh())
		{
			return;
		}

		final NearHighData data = nearHighFor(rawItemId, components, quantity);
		if (data == null || data.level == NearHighLevel.NONE)
		{
			return;
		}

		final Color ringColor = data.level == NearHighLevel.AT
			? config.atHighColor()
			: config.nearHighColor();

		drawSparkle(graphics, bounds, ringColor, data.level == NearHighLevel.AT);
	}

	/**
	 * Draws a small gold "sparkle" accent in the TOP-RIGHT corner of the slot (the one corner free
	 * of the game's stack count (top-left) and our percent label (bottom-right)). It composes over
	 * the red/green movement fill without a border that would fight the corner numbers. A gentle
	 * alpha pulse (driven by the wall clock, no animation infra) makes it catch the eye; the
	 * "at/above high" tier is drawn larger and brighter than the "near" tier.
	 */
	private void drawSparkle(Graphics2D graphics, Rectangle bounds, Color baseColor, boolean atHigh)
	{
		// Pulse on a ~1.4s cycle with a HIGH floor so it stays clearly visible at its dimmest
		// (never fades to near-nothing) while still "breathing" to catch the eye.
		final double phase = (System.currentTimeMillis() % 1400L) / 1400.0 * 2 * Math.PI;
		final int alpha = (int) (228 + 27 * Math.sin(phase));
		final int clamped = Math.max(0, Math.min(255, alpha));
		final Color glow = new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(),
			clamped);

		// Bigger and brighter than the first pass (it was easy to miss): larger radius, a soft
		// coloured halo behind the star, thicker arms.
		final int radius = atHigh ? 8 : 6;
		final int cx = bounds.x + bounds.width - radius - 1;
		final int cy = bounds.y + radius + 1;

		final Object aa = graphics.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
			RenderingHints.VALUE_ANTIALIAS_ON);
		final Stroke originalStroke = graphics.getStroke();

		// Soft coloured glow halo so the sparkle pops against any item icon / fill.
		final int haloR = radius + 3;
		final int pulseAlpha = (int) (70 + 40 * (0.5 + 0.5 * Math.sin(phase)));
		graphics.setColor(new Color(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(),
			Math.max(0, Math.min(120, pulseAlpha))));
		graphics.fillOval(cx - haloR, cy - haloR, haloR * 2, haloR * 2);
		// Dark backing ring to separate the halo from a bright background.
		graphics.setColor(new Color(0, 0, 0, 90));
		graphics.setStroke(new BasicStroke(1f));
		graphics.drawOval(cx - haloR, cy - haloR, haloR * 2, haloR * 2);

		// Four-point star arms.
		graphics.setColor(glow);
		graphics.setStroke(new BasicStroke(atHigh ? 2.8f : 2.2f));
		graphics.drawLine(cx - radius, cy, cx + radius, cy);
		graphics.drawLine(cx, cy - radius, cx, cy + radius);
		final int d = radius - 2;
		if (d > 0)
		{
			graphics.setStroke(new BasicStroke(1.4f));
			graphics.drawLine(cx - d, cy - d, cx + d, cy + d);
			graphics.drawLine(cx - d, cy + d, cx + d, cy - d);
		}
		// Bright white core.
		graphics.setColor(Color.WHITE);
		graphics.fillOval(cx - 2, cy - 2, 4, 4);

		graphics.setStroke(originalStroke);
		if (aa != null)
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
		}
	}

	/**
	 * Computes the near-1-year-high decision for an item (shared by the overlay ring and the
	 * tooltip line), or {@code null} if it cannot be decided yet (prices not loaded) or the item
	 * fails the value filters. Uses the combined current price and the combined 1y high.
	 */
	@Nullable
	NearHighData nearHighFor(int rawItemId, List<Component> components, int quantity)
	{
		if (components == null || components.isEmpty()
			|| ItemResolver.isAllFixedPrice(components))
		{
			// Pure fixed-price currency (coins/platinum) has no market history: current and
			// 1-year high are the same flat value, which would always read as AT. Currency never
			// participates in the heatmap, so it must never be ringed either.
			return null;
		}

		final Long combinedCurrent = combinedLatestLow(components);
		if (combinedCurrent == null || !shouldFetch(combinedCurrent, quantity))
		{
			return null; // not pricable yet, or fails the value filters
		}

		// Alch-floored items (GE price pinned just above high-alch) sit flat all year, so they are
		// permanently at their "1-year high" without being a real sell signal. Exclude them from
		// the ring when the toggle is on. Scoped to the ring only; movement colours are unaffected.
		if (config.excludeAlchFloorFromYearHigh()
			&& ItemResolver.isAlchFloored(combinedCurrent,
				itemResolver.highAlchValue(rawItemId), config.alchFloorThresholdPct()))
		{
			return null;
		}

		final Long yearHigh = priceService.combinedYearHigh(components);
		final Long yearLow = priceService.combinedYearLow(components);
		if (yearHigh == null || yearLow == null)
		{
			return null; // 1y data not loaded yet
		}

		// Stable commodities (runes, ores, bars) barely swing over the year, so being "at the high"
		// is noise. Require a meaningful yearly range (both % and gp) before ringing.
		if (!ColorScale.hasMeaningfulYearRange(yearHigh, yearLow, config.minYearRangePct(),
			config.minYearRangeGp()))
		{
			return null;
		}

		final NearHighLevel level = ColorScale.nearHighLevel(combinedCurrent, yearHigh,
			config.nearHighThresholdPct());
		return new NearHighData(level, yearHigh);
	}

	/** The near-high decision plus the combined 1-year high it was decided against. */
	static final class NearHighData
	{
		final NearHighLevel level;
		final long yearHigh;

		NearHighData(NearHighLevel level, long yearHigh)
		{
			this.level = level;
			this.yearHigh = yearHigh;
		}
	}

	static String formatPercent(double pct)
	{
		final long rounded = Math.round(pct);
		return (rounded > 0 ? "+" : "") + rounded + "%";
	}

	/**
	 * Signed gp change shortened to the RuneScape K/M/B style, e.g. {@code +1.65M}, {@code -950K}.
	 * Long-safe: a whole-stack gp swing can exceed the signed 32-bit range, so this does not use
	 * {@link QuantityFormatter#quantityToRSDecimalStack(int)} (which takes an int).
	 */
	static String formatSignedGp(long gp)
	{
		final String sign = gp > 0 ? "+" : (gp < 0 ? "-" : "");
		return sign + formatShort(Math.abs(gp));
	}

	private static String formatShort(long value)
	{
		if (value < 1_000L)
		{
			return Long.toString(value);
		}
		if (value < 1_000_000L)
		{
			return trim(value / 1_000d) + "K";
		}
		if (value < 1_000_000_000L)
		{
			return trim(value / 1_000_000d) + "M";
		}
		return trim(value / 1_000_000_000d) + "B";
	}

	/** Rounds to 2 decimals and drops trailing zeros (e.g. {@code 1.65}, {@code 2}, {@code 1.5}). */
	private static String trim(double scaled)
	{
		final double rounded = Math.round(scaled * 100d) / 100d;
		if (rounded == Math.floor(rounded))
		{
			return Long.toString((long) rounded);
		}
		String s = String.format(Locale.US, "%.2f", rounded);
		// strip a single trailing zero (e.g. 1.50 -> 1.5)
		if (s.endsWith("0"))
		{
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}
}
