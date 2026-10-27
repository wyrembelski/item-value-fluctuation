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
 * its tradeable mapping, which already folds in item-variation mappings, and SUM every mapped
 * tradeable component's price multiplied by its quantity. Attribution for BSD-2 compliance; no
 * comments or code are copied verbatim.
 *
 * <p><b>Why we always sum (and the one thing we can't mirror).</b> RuneLite's
 * {@code getItemPriceWithSource} runs a step we cannot: {@code itemID = WORN_ITEMS.getOrDefault(itemID, itemID)}
 * pre-resolves a worn/combined id to its canonical base through a PRIVATE map before calling
 * {@link ItemMapping#map(int)}, then it unconditionally sums every mapping entry
 * ({@code price += getItemPriceWithSource(entry.getTradeableItem()) * entry.getQuantity()}).
 * Plugins can't reach {@code WORN_ITEMS}. An audit of the complete multi-entry key set in the
 * maintained {@link ItemMapping} table (see the task notes) confirmed that this is acceptable:
 * <ul>
 *   <li>No key has two entries that point at the SAME tradeable, so unconditionally summing never
 *       double-counts the same item.</li>
 *   <li>Every multi-entry key whose entries point at DISTINCT tradeables is genuinely additive —
 *       a combined/kitted item built from separately-priced parts (e.g. Avernic treads (max) =
 *       base treads + Primordial + Pegasian + Eternal boots; a godsword (or) = base + ornament
 *       kit; Cannon base (or) = plain cannon base + Shattered cannon ornament kit). The cases
 *       RuneLite only disambiguates via the inaccessible {@code WORN_ITEMS} step are ones where
 *       summing is the correct value anyway.</li>
 * </ul>
 * So this class mirrors {@code getItemPriceWithSource}'s always-sum behaviour with no bespoke
 * ambiguity heuristic. The missing {@code WORN_ITEMS} pre-step only affects a rare worn-only
 * variant that neither {@code canonicalize} nor the public mapping surfaces; such an item degrades
 * gracefully (uncoloured / no tooltip) rather than mispriced — a documented plugin limitation.
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
		return resolveCanonical(canon);
	}

	/**
	 * Resolves an already-{@code canonicalize}d id to its tradeable components. Split out from
	 * {@link #resolve(int)} so the pure mapping/expansion logic (which depends only on the static
	 * {@link ItemMapping} table, not on {@link ItemManager}) can be exercised headlessly in tests.
	 */
	List<Component> resolveCanonical(int canonId)
	{
		if (isFixedPrice(canonId))
		{
			return List.of(new Component(canonId, 1));
		}

		// componentId -> total per-item quantity, accumulated as the mapping chain is expanded.
		final Map<Integer, Long> totals = new LinkedHashMap<>();
		expand(canonId, 1L, 0, totals);

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

		// ItemMapping.map(id) returns EVERY mapping entry whose untradable list contains this id,
		// each pointing at a tradeable component (× a quantity). Mirroring RuneLite's own
		// getItemPriceWithSource, we SUM every entry unconditionally: the entries for a combined /
		// kitted item are its genuinely-distinct tradeable parts (e.g. Avernic treads (max) = base
		// treads + Primordial + Pegasian + Eternal boots; a godsword (or) = base + ornament kit;
		// Cannon base (or) = plain cannon base + Shattered cannon ornament kit). An audit of the
		// whole multi-entry key set confirmed no key points two entries at the SAME tradeable, so
		// summing never double-counts (see the class javadoc and task notes). Each recursion through
		// expandEntry keeps MAX_DEPTH cycle protection and quantity multiplication, and only ever
		// emits TRADEABLE component ids.
		for (ItemMapping m : mapped)
		{
			expandEntry(m, itemId, multiplier, depth, totals);
		}
	}

	/**
	 * Expands one mapping entry: either records a self-mapping leaf, or recurses into the entry's
	 * tradeable with the quantity multiplied through, so leaf-vs-recurse is decided in one place.
	 */
	private void expandEntry(ItemMapping m, int itemId, long multiplier, int depth,
		Map<Integer, Long> totals)
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
