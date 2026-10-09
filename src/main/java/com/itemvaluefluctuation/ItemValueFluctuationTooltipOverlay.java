/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.List;
import javax.annotation.Nullable;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * Shows a detail tooltip (current price, window-start price, percent change, 1-year high) when
 * the mouse hovers a highlighted bank item. Reads only cached fluctuations; does no network work.
 */
class ItemValueFluctuationTooltipOverlay extends Overlay
{
	private static final Color GAIN_TEXT = new Color(0x55, 0xDD, 0x55);
	private static final Color LOSS_TEXT = new Color(0xFF, 0x5B, 0x5B);

	private final Client client;
	private final ItemValueFluctuationConfig config;
	private final PriceService priceService;
	private final ItemManager itemManager;
	private final ItemResolver itemResolver;
	private final TooltipManager tooltipManager;
	private final ColorScale colorScale;

	@Inject
	ItemValueFluctuationTooltipOverlay(Client client, ItemValueFluctuationConfig config,
		PriceService priceService, ItemManager itemManager, ItemResolver itemResolver,
		TooltipManager tooltipManager)
	{
		this.client = client;
		this.config = config;
		this.priceService = priceService;
		this.itemManager = itemManager;
		this.itemResolver = itemResolver;
		this.tooltipManager = tooltipManager;
		this.colorScale = new ColorScale(config);
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showTooltip())
		{
			return null;
		}

		final Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (container == null || container.isHidden())
		{
			return null;
		}

		final Widget[] items = container.getDynamicChildren();
		if (items == null)
		{
			return null;
		}

		final net.runelite.api.Point mouse = client.getMouseCanvasPosition();
		if (mouse == null)
		{
			return null;
		}
		final Point mousePoint = new Point(mouse.getX(), mouse.getY());

		// Only consider hovers inside the bank item viewport (scrolled-out slots report bounds
		// that can sit elsewhere on screen).
		final Rectangle viewport = container.getBounds();
		if (viewport == null || !viewport.contains(mousePoint))
		{
			return null;
		}

		final TimeFrame timeFrame = config.timeFrame();

		for (Widget item : items)
		{
			if (item == null || item.isHidden() || item.getItemId() <= 0)
			{
				continue;
			}

			final Rectangle bounds = item.getBounds();
			if (bounds == null || !viewport.intersects(bounds) || !bounds.contains(mousePoint))
			{
				continue;
			}

			final int rawId = item.getItemId();
			final int quantity = item.getItemQuantity();
			final List<Component> components = itemResolver.resolve(rawId);
			final PriceFluctuation f = priceService.getCachedCombined(rawId, components, timeFrame);
			final boolean highlighted = f != null && colorScale.colorFor(f, quantity) != null;

			// Near-1-year-high state (only when the toggle is on). A flat-but-at-peak item has no
			// movement colour, so we must render a tooltip when EITHER it is movement-highlighted
			// OR it is ringed near/at its year high.
			final NearHighLevel nearHigh = nearHighLevel(rawId, components, quantity);
			final boolean ringed = nearHigh != NearHighLevel.NONE;

			if (!highlighted && !ringed)
			{
				return null; // only annotate items we actually highlighted or ringed
			}

			tooltipManager.add(new Tooltip(
				buildPanel(f, rawId, components, timeFrame, quantity, nearHigh)));
			return null;
		}

		return null;
	}

	private PanelComponent buildPanel(@Nullable PriceFluctuation f, int rawId,
		List<Component> components, TimeFrame timeFrame, int quantity, NearHighLevel nearHigh)
	{
		final int displayId = f != null ? f.getItemId() : rawId;
		final ItemComposition comp = itemManager.getItemComposition(displayId);
		final String name = comp != null ? comp.getName() : "Item " + displayId;

		final long qty = Math.max(1, quantity);

		final PanelComponent panel = new PanelComponent();
		// A FULLY OPAQUE dark background so the busy bank behind the tooltip can't bleed through and
		// muddy the text (the earlier translucent panel was still hard to read, especially on the
		// left column). Fixed width keeps the label/value columns aligned.
		panel.setBackgroundColor(new Color(0x1A, 0x1A, 0x1A, 255));
		panel.setPreferredSize(new Dimension(180, 0));
		panel.getChildren().add(TitleComponent.builder()
			.text(name)
			.build());

		// Transparency line: when the item is priced via other items (a multi-part combo, or a
		// single remapped tradeable such as degraded Barrows or a toxic blowpipe), say so, so the
		// combined figures below are not mistaken for the item's own GE price. A plain tradeable
		// item (its own only component) keeps the original layout unchanged.
		final String pricedVia = pricedViaText(rawId, components);
		if (pricedVia != null)
		{
			panel.getChildren().add(LineComponent.builder()
				.left(pricedVia)
				.build());
		}

		if (f != null)
		{
			final boolean gain = f.getPercentChange() > 0;
			final Color changeColor = gain ? GAIN_TEXT : LOSS_TEXT;
			final String changeText =
				ItemValueFluctuationOverlay.formatPercent(f.getPercentChange())
				+ "  (" + signedGp(f.getGpChange()) + ")";
			final long stackValue = f.getCurrentPrice() * qty;

			panel.getChildren().add(LineComponent.builder()
				.left(timeFrame.getDisplayName() + " change")
				.right(changeText)
				.rightColor(changeColor)
				.build());
			panel.getChildren().add(LineComponent.builder()
				.left("Current")
				.right(QuantityFormatter.formatNumber(f.getCurrentPrice()) + " gp")
				.build());
			panel.getChildren().add(LineComponent.builder()
				.left("Was")
				.right(QuantityFormatter.formatNumber(f.getAnchorPrice()) + " gp")
				.build());
			panel.getChildren().add(LineComponent.builder()
				.left(timeFrame.getDisplayName() + " high")
				.right(QuantityFormatter.formatNumber(f.getPeriodHigh()) + " gp")
				.build());
			panel.getChildren().add(LineComponent.builder()
				.left(timeFrame.getDisplayName() + " low")
				.right(QuantityFormatter.formatNumber(f.getPeriodLow()) + " gp")
				.build());
			if (qty > 1)
			{
				panel.getChildren().add(LineComponent.builder()
					.left("Stack (" + QuantityFormatter.formatNumber(qty) + ")")
					.right(QuantityFormatter.formatNumber(stackValue) + " gp")
					.build());
				panel.getChildren().add(LineComponent.builder()
					.left("Stack change")
					.right(ItemValueFluctuationOverlay.formatSignedGp(f.getGpChange() * qty))
					.rightColor(changeColor)
					.build());
			}
		}

		// Near-1-year-high line, matching the ring. Only for ringed items.
		if (nearHigh != NearHighLevel.NONE)
		{
			final Long yearHigh = priceService.combinedYearHigh(components);
			final Color ringColor = nearHigh == NearHighLevel.AT
				? config.atHighColor()
				: config.nearHighColor();
			if (nearHigh == NearHighLevel.AT)
			{
				panel.getChildren().add(LineComponent.builder()
					.left("At 1-year high")
					.right(yearHigh != null
						? QuantityFormatter.formatNumber(yearHigh) + " gp" : "")
					.leftColor(ringColor)
					.rightColor(ringColor)
					.build());
			}
			else
			{
				panel.getChildren().add(LineComponent.builder()
					.left("Within " + config.nearHighThresholdPct() + "% of 1-year high")
					.right(yearHigh != null
						? QuantityFormatter.formatNumber(yearHigh) + " gp" : "")
					.leftColor(ringColor)
					.rightColor(ringColor)
					.build());
			}
		}
		return panel;
	}

	/**
	 * The near-1-year-high decision for the hovered item, or {@link NearHighLevel#NONE} if the
	 * toggle is off, prices are not loaded yet, or the item fails the value filters. Mirrors the
	 * overlay's gate (value filters only, combined current vs combined 1y high).
	 */
	private NearHighLevel nearHighLevel(int rawItemId, List<Component> components, int quantity)
	{
		if (!config.highlightNearYearHigh() || components == null || components.isEmpty()
			|| ItemResolver.isAllFixedPrice(components))
		{
			// Pure fixed-price currency (coins/platinum) is never ringed, so never annotate it
			// with a near-/at-1-year-high line (its current and 1-year high are the same value).
			return NearHighLevel.NONE;
		}

		final Long combinedCurrent = combinedLatestLow(components);
		if (combinedCurrent == null
			|| !ItemValueFluctuationOverlay.passesPreCheck(combinedCurrent, quantity,
				config.minItemValue(), config.minTotalValue()))
		{
			return NearHighLevel.NONE;
		}

		// Mirror the ring's alch-floor exclusion so the tooltip line never appears for an item the
		// ring deliberately skipped.
		if (config.excludeAlchFloorFromYearHigh()
			&& ItemResolver.isAlchFloored(combinedCurrent,
				itemResolver.highAlchValue(rawItemId), config.alchFloorThresholdPct()))
		{
			return NearHighLevel.NONE;
		}

		final Long yearHigh = priceService.combinedYearHigh(components);
		final Long yearLow = priceService.combinedYearLow(components);
		if (yearHigh == null || yearLow == null)
		{
			return NearHighLevel.NONE;
		}

		// Mirror the ring's yearly-range gate: don't annotate stable commodities that barely moved.
		if (!ColorScale.hasMeaningfulYearRange(yearHigh, yearLow, config.minYearRangePct(),
			config.minYearRangeGp()))
		{
			return NearHighLevel.NONE;
		}
		return ColorScale.nearHighLevel(combinedCurrent, yearHigh, config.nearHighThresholdPct());
	}

	/**
	 * Sum of each component's bulk /latest low times its per-item quantity, or {@code null} if any
	 * component's latest low is not yet known. Fixed-price components (currency) contribute their
	 * flat value. Mirrors the overlay's combined-current computation.
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
	 * A short line explaining how an item is priced when its value comes from something other than
	 * its own GE price: {@code "Priced from N components"} for a multi-part combo, or
	 * {@code "Priced as: <name>"} for a single remapped tradeable (e.g. a degraded or combined item
	 * priced as its tradeable base). Returns {@code null} for a plain tradeable item that is its own
	 * only component, so its tooltip is unchanged.
	 */
	@Nullable
	private String pricedViaText(int rawId, List<Component> components)
	{
		if (components == null || components.isEmpty())
		{
			return null;
		}
		if (components.size() > 1)
		{
			return "Priced from " + components.size() + " components";
		}
		final Component only = components.get(0);
		if (only.getTradeableId() == rawId)
		{
			return null; // its own only component: a plain tradeable item
		}
		final ItemComposition comp = itemManager.getItemComposition(only.getTradeableId());
		final String name = comp != null ? comp.getName() : "item " + only.getTradeableId();
		return "Priced as: " + name;
	}

	private static String signedGp(long gp)
	{
		final String sign = gp > 0 ? "+" : (gp < 0 ? "-" : "");
		return sign + QuantityFormatter.formatNumber(Math.abs(gp)) + " gp";
	}
}
