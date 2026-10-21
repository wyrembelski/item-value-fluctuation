/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

		// HYBRID AMBIGUITY RULE: ItemMapping.map(id) returns EVERY mapping entry whose untradable
		// list contains this id. Some ids appear in more than one entry, and the two shapes must be
		// handled differently:
		//
		//  * ADDITIVE (sum): an ornament-kitted item such as the Soul reaper axe (or) is listed by
		//    a base-weapon mapping (-> Soulreaper axe) AND an ornament-kit mapping (-> the kit).
		//    Both targets are genuine, distinct, tradeable PARTS, so the item's value is their sum.
		//    The old code priced these as their own (untradeable) id, which has no /latest price, so
		//    the item was silently skipped — the Soul reaper axe (or) bug.
		//
		//  * DOUBLE-COUNT (do NOT sum): a league/ornament cannon base is listed by a 1:1 base
		//    mapping (-> plain cannon base) AND by a multi-part "ornament PACK" mapping whose
		//    tradeable is the whole assembled set. Summing those double-counts and invents a huge
		//    false value (the cannon "+55% spike" bug). Here we pick exactly ONE entry.
		//
		// We tell them apart structurally (no price probe, no ItemManager): a PACK entry is one
		// whose untradable ids resolve to two or more DISTINCT tradeables (the cannon pack bundles
		// several different pieces), whereas a kit/variant entry resolves to a single tradeable. If
		// any entry is a pack, the result is ambiguous -> single-base; otherwise the entries are
		// additive -> sum. Either branch still recurses through expand(...) so MAX_DEPTH cycle
		// protection and quantity multiplication are unchanged, and both only ever emit TRADEABLE
		// component ids.
		if (mapped.size() > 1)
		{
			final List<ItemMapping> entries = new ArrayList<>(mapped);
			if (isAdditive(entries, itemId))
			{
				for (ItemMapping m : entries)
				{
					expandEntry(m, itemId, multiplier, depth, totals);
				}
			}
			else
			{
				expandEntry(chooseSingleBase(entries, itemId), itemId, multiplier, depth, totals);
			}
			return;
		}

		for (ItemMapping m : mapped)
		{
			expandEntry(m, itemId, multiplier, depth, totals);
		}
	}

	/**
	 * Expands one mapping entry: either records a self-mapping leaf, or recurses into the entry's
	 * tradeable with the quantity multiplied through. Shared by the single-entry path and both
	 * branches of the multi-entry hybrid rule so leaf-vs-recurse is decided in exactly one place.
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
	 * Whether the entries returned by a multi-entry {@link ItemMapping#map(int)} are safe to SUM as
	 * additive parts (true) or must be collapsed to a single base to avoid a double-count (false).
	 * All of the following must hold to sum:
	 *
	 * <ul>
	 *   <li><b>(a) distinct tradeable targets</b> — no two entries share the same
	 *       {@link ItemMapping#getTradeableItem()} (the same base counted twice);</li>
	 *   <li><b>(b) no "pack" entry</b> — no entry bundles a MULTI-PART set. The double-count pattern
	 *       the old guard protected (the cannon base listed by both a 1:1 base mapping and a
	 *       multi-piece ornament-pack mapping) is detected structurally: a pack entry is one whose
	 *       untradable ids resolve to two or more DISTINCT tradeables. An ornament-kit / item-
	 *       variation entry resolves to a single tradeable and is NOT a pack, so a godsword (or)
	 *       (whose base entry also lists a variant id) still sums correctly;</li>
	 *   <li><b>(c) plausible tradeable targets</b> — no entry's tradeable equals the ambiguous key
	 *       {@code itemId} itself (an entry whose "tradeable" is the untradeable key is not an
	 *       additive part). This is the structural stand-in for "a real tradeable" without injecting
	 *       a price service.</li>
	 * </ul>
	 */
	private static boolean isAdditive(List<ItemMapping> entries, int itemId)
	{
		final Set<Integer> targets = new HashSet<>();
		for (ItemMapping m : entries)
		{
			final int tradeable = m.getTradeableItem();
			if (tradeable == itemId)
			{
				return false; // (c)
			}
			if (!targets.add(tradeable))
			{
				return false; // (a) duplicate target
			}
			if (isPackEntry(m))
			{
				return false; // (b) multi-part pack -> double-count risk
			}
		}
		return true;
	}

	/**
	 * True if {@code entry} bundles a multi-part set (the double-count pattern), detected purely
	 * from the static {@link ItemMapping} table. An entry's untradable ids are the keys that map
	 * BACK to it; a genuine multi-part pack enumerates several keys that each also map to their own
	 * DIFFERENT tradeable part.
	 *
	 * <p>Concretely: for each untradable id {@code u} of this entry, look up {@code map(u)} and
	 * collect the tradeables it points at OTHER than this entry's own tradeable. If two or more such
	 * distinct "other part" tradeables exist, this is a pack.
	 *
	 * <ul>
	 *   <li>Assembled-cannon pack (tradeable = full cannon set): its four piece-keys each also map
	 *       to a different individual-piece tradeable -> four distinct other-parts -> PACK, so the
	 *       cannon base is single-based and never double-counted.</li>
	 *   <li>Godsword (or) base entry (tradeable = the godsword): its extra untradable id is only a
	 *       VARIATION that maps to the same godsword tradeable -> zero distinct other-parts -> NOT a
	 *       pack, so a godsword (or) still sums base + ornament kit.</li>
	 *   <li>Ornament-kit / single-key entries: length &le; 1 -> trivially not a pack.</li>
	 * </ul>
	 */
	private static boolean isPackEntry(ItemMapping entry)
	{
		final int[] untradable = entry.getUntradableItems();
		if (untradable == null || untradable.length <= 1)
		{
			return false;
		}

		final int ownTradeable = entry.getTradeableItem();
		final Set<Integer> otherParts = new HashSet<>();
		for (int u : untradable)
		{
			final Collection<ItemMapping> sub = ItemMapping.map(u);
			if (sub == null)
			{
				continue;
			}
			for (ItemMapping s : sub)
			{
				final int t = s.getTradeableItem();
				if (t != ownTradeable)
				{
					otherParts.add(t);
					if (otherParts.size() > 1)
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Picks exactly ONE entry to expand when the entries are not additive (overlap / pack / same
	 * target). Never sums. Precedence:
	 *
	 * <ol>
	 *   <li><b>(i)</b> the single entry whose tradeable equals {@code canonKey} (the already-
	 *       canonicalized base of the raw item), if exactly one matches;</li>
	 *   <li><b>(ii)</b> else the entry with the SMALLEST untradable-list length (the 1:1 base
	 *       mapping beats a multi-part pack — this is what separates the cannon case);</li>
	 *   <li><b>(iii)</b> else, still tied, the entry with the SMALLEST tradeable id, for a
	 *       deterministic result (the collection order from {@link ItemMapping#map(int)} is
	 *       unspecified).</li>
	 * </ol>
	 */
	private static ItemMapping chooseSingleBase(List<ItemMapping> entries, int canonKey)
	{
		ItemMapping canonMatch = null;
		int canonMatches = 0;
		for (ItemMapping m : entries)
		{
			if (m.getTradeableItem() == canonKey)
			{
				canonMatch = m;
				canonMatches++;
			}
		}
		if (canonMatches == 1)
		{
			return canonMatch; // (i)
		}

		ItemMapping best = null;
		for (ItemMapping m : entries)
		{
			if (best == null || compareSingleBase(m, best) < 0)
			{
				best = m;
			}
		}
		return best;
	}

	/** Orders candidates for {@link #chooseSingleBase}: smaller untradable list, then smaller id. */
	private static int compareSingleBase(ItemMapping a, ItemMapping b)
	{
		final int la = a.getUntradableItems() == null ? 0 : a.getUntradableItems().length;
		final int lb = b.getUntradableItems() == null ? 0 : b.getUntradableItems().length;
		if (la != lb)
		{
			return Integer.compare(la, lb); // (ii)
		}
		return Integer.compare(a.getTradeableItem(), b.getTradeableItem()); // (iii)
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
