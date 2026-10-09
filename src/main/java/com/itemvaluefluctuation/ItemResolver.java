/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemMapping;

/**
 * Resolves a raw bank item id to the tradeable component(s) whose Grand Exchange prices, summed,
 * give the item's value. This lets the plugin price items that are not themselves tradeable but
 * are built from tradeable parts (barrows armour, ornament-kitted weapons, combined items).
 *
 * <p>The approach mirrors, in paraphrase, RuneLite's own
 * {@code net.runelite.client.game.ItemManager#getItemPriceWithSource} and its use of
 * {@link ItemMapping}: canonicalize the id (which un-notes and un-placeholders it) and then look up
 * its tradeable mapping, which already folds in item-variation mappings. Attribution for BSD-2
 * compliance; no comments or code are copied verbatim.
 */
class ItemResolver
{
	/** How many levels of mapping expansion to follow before stopping (guards against cycles). */
	private static final int MAX_DEPTH = 3;

	private final ItemManager itemManager;

	@Inject
	ItemResolver(ItemManager itemManager)
	{
		this.itemManager = itemManager;
	}

	/**
	 * Resolves {@code rawItemId} to its tradeable components. A plain tradeable item returns a
	 * single {@code [Component(canonicalId, 1)]}; coins and platinum tokens return a single
	 * fixed-price component; combined/variant items return the flattened set of their tradeable
	 * parts with per-item quantities multiplied through the mapping chain.
	 */
	List<Component> resolve(int rawItemId)
	{
		if (isFixedPrice(rawItemId))
		{
			return List.of(new Component(rawItemId, 1));
		}

		final int canon = itemManager.canonicalize(rawItemId);

		// componentId -> total per-item quantity, accumulated as the mapping chain is expanded.
		final Map<Integer, Long> totals = new LinkedHashMap<>();
		expand(canon, 1L, 0, totals);

		final List<Component> components = new ArrayList<>(totals.size());
		for (Map.Entry<Integer, Long> e : totals.entrySet())
		{
			components.add(new Component(e.getKey(), e.getValue()));
		}
		return components;
	}

	/**
	 * Expands {@code itemId} (worth {@code multiplier} of the parent per item) into {@code totals},
	 * recursing into each mapped tradeable up to {@link #MAX_DEPTH}. A mapping that returns the item
	 * unchanged, or no mapping at all, is treated as a leaf.
	 */
	private void expand(int itemId, long multiplier, int depth, Map<Integer, Long> totals)
	{
		final Collection<ItemMapping> mapped = depth < MAX_DEPTH ? ItemMapping.map(itemId) : null;
		if (mapped == null || mapped.isEmpty())
		{
			totals.merge(itemId, multiplier, Long::sum);
			return;
		}

		// AMBIGUITY GUARD: ItemMapping.map(id) returns EVERY mapping entry whose untradable list
		// contains this id. Some ids appear in more than one entry — e.g. a league/ornament cannon
		// base is listed both by its 1:1 base mapping (-> plain cannon base) AND by the multi-part
		// "ornament pack" mapping. Summing across those entries double-counts and invents a huge
		// false value (the cannon "+55% spike" bug). A single item cannot be two different things,
		// so when the mapping is ambiguous we do NOT sum: we price the item as its own (already
		// canonicalized) value, which for an ornamented piece is the sensible base-equivalent price.
		if (mapped.size() > 1)
		{
			totals.merge(itemId, multiplier, Long::sum);
			return;
		}

		for (ItemMapping m : mapped)
		{
			final int tradeable = m.getTradeableItem();
			final long childMultiplier = multiplier * m.getQuantity();
			if (tradeable == itemId)
			{
				// Self-mapping: a leaf tradeable; adding it again would recurse forever.
				totals.merge(itemId, childMultiplier, Long::sum);
			}
			else
			{
				expand(tradeable, childMultiplier, depth + 1, totals);
			}
		}
	}

	/**
	 * The item's high-alch value, or 0 if unknown. Local, free lookup (no API call).
	 */
	long highAlchValue(int rawItemId)
	{
		final int ha = itemManager.getItemComposition(rawItemId).getHaPrice();
		return Math.max(0, ha);
	}

	/**
	 * Whether an item is "alch-floored": its Grand Exchange price sits within {@code thresholdPct}
	 * of (and no lower than it would need alching to backstop) its high-alch value. Such items are
	 * propped up by alching and stay flat all year, so they are permanently at their 1-year high
	 * without being a real sell signal. Pure/testable.
	 *
	 * @param gePrice     the item's current GE (instant-sell) price
	 * @param haValue     the item's high-alch value (0 = unknown, never floored)
	 * @param thresholdPct how close to the alch value counts as floored
	 */
	static boolean isAlchFloored(long gePrice, long haValue, int thresholdPct)
	{
		if (haValue <= 0 || gePrice <= 0)
		{
			return false;
		}
		// Floored when the GE price is no more than thresholdPct above the alch value.
		return gePrice <= haValue + (haValue * thresholdPct) / 100L;
	}

	/** True for currency that has a fixed gp value rather than a Grand Exchange price. */
	static boolean isFixedPrice(int id)
	{
		return id == ItemID.COINS || id == ItemID.PLATINUM;
	}

	/**
	 * True when every component is fixed-price currency (coins/platinum). Such an item has no
	 * market history, so it is never heatmap-coloured and never ringed near its 1-year high
	 * (current and 1-year high are the same flat value). An empty list is not treated as all-fixed.
	 */
	static boolean isAllFixedPrice(List<Component> components)
	{
		if (components == null || components.isEmpty())
		{
			return false;
		}
		for (Component c : components)
		{
			if (!isFixedPrice(c.getTradeableId()))
			{
				return false;
			}
		}
		return true;
	}

	/** The fixed per-item gp value of a currency id ({@code COINS} = 1, {@code PLATINUM} = 1000). */
	static long fixedPrice(int id)
	{
		if (id == ItemID.PLATINUM)
		{
			return 1000L;
		}
		return 1L;
	}
}
