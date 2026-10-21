/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pure, headless coverage of {@link ItemResolver}'s component resolution — in particular the hybrid
 * ambiguity rule that decides whether a multi-entry {@link net.runelite.client.game.ItemMapping}
 * result is SUMMED (additive ornament-kitted items such as the Soul reaper axe (or)) or collapsed
 * to a SINGLE base (the cannon-pack double-count pattern that produced the old "+55% spike").
 *
 * <p>No Swing, no client, no {@link PriceService}, and no mocking framework (Mockito is not on the
 * test classpath). {@link ItemManager#canonicalize(int)} is normally the only {@link ItemManager}
 * touch before expansion, and the plugin already confirmed (see the investigation) that
 * {@code canonicalize} leaves an ornament-kitted id unchanged, so these tests exercise the resolver
 * through the package-private {@link ItemResolver#resolveCanonical(int)} seam with the canonical id
 * passed directly. {@code ItemMapping.map(...)} is a real static over RuneLite's maintained table,
 * so the genuine mapping entries are used.
 */
public class ItemResolverTest
{
	/** A resolver with a null ItemManager: resolveCanonical never touches it. */
	private final ItemResolver resolver = new ItemResolver(null);

	private static Map<Integer, Long> byTradeable(List<Component> components)
	{
		final Map<Integer, Long> m = new HashMap<>();
		for (Component c : components)
		{
			m.merge(c.getTradeableId(), c.getPerItemQuantity(), Long::sum);
		}
		return m;
	}

	private static Set<Integer> tradeableIds(List<Component> components)
	{
		final Set<Integer> ids = new HashSet<>();
		for (Component c : components)
		{
			ids.add(c.getTradeableId());
		}
		return ids;
	}

	@Test
	public void soulReaperAxeOrnResolvesToTradeableBasePlusKit()
	{
		final List<Component> components = resolver.resolveCanonical(ItemID.SOULREAPER_AXE_ORN);
		final Map<Integer, Long> byId = byTradeable(components);

		// Both tradeable parts, each once, summed — NOT the untradeable (or) id.
		final Map<Integer, Long> expected = new HashMap<>();
		expected.put(ItemID.SOULREAPER, 1L);
		expected.put(ItemID.DEMONIC_AXE_ORNAMENT_KIT, 1L);
		assertEquals(expected, byId);
		assertFalse("must not price as its own untradeable id",
			byId.containsKey(ItemID.SOULREAPER_AXE_ORN));
	}

	@Test
	public void ornamentCannonBaseDoesNotDoubleCount()
	{
		// The League III multicannon base is listed by BOTH a 1:1 base mapping (-> the plain cannon
		// base piece) AND the multi-part cannon "pack" mapping (-> the assembled cannon set). The
		// old guard protected against SUMMING those (the "+55%" spike). The fix must collapse to the
		// single plain base piece, never summing the pack's extra parts.
		final List<Component> components =
			resolver.resolveCanonical(ItemID.LEAGUE_3_MULTICANNON_BASE);

		// Exactly one tradeable component: the plain cannon base piece (ItemID.TWPART1 — the base
		// section of the Dwarf multicannon, tradeable id 6), NOT the assembled-set "pack" tradeable
		// (LEAGUE_3_MULTICANNON_PACK) and NOT any of the other cannon pieces.
		assertEquals(1, components.size());
		final Component only = components.get(0);
		assertEquals(ItemID.TWPART1, only.getTradeableId());
		assertEquals(1L, only.getPerItemQuantity());
		assertFalse("must not sum the pack's assembled-set tradeable",
			tradeableIds(components).contains(ItemID.LEAGUE_3_MULTICANNON_PACK));
		assertFalse("must not price as its own untradeable id",
			tradeableIds(components).contains(ItemID.LEAGUE_3_MULTICANNON_BASE));
	}

	@Test
	public void cleanSingleEntryItemUnchanged()
	{
		// The (charged) Dragonfire shield is a single-entry mapping to its tradeable uncharged
		// form. The single-entry path is untouched by the hybrid rule, so it still resolves to that
		// one tradeable part and never to its own id.
		final List<Component> components = resolver.resolveCanonical(ItemID.DRAGONFIRE_SHIELD);
		assertEquals(1, components.size());
		assertEquals(1L, components.get(0).getPerItemQuantity());
		assertFalse("must not price as its own id",
			tradeableIds(components).contains(ItemID.DRAGONFIRE_SHIELD));
	}

	@Test
	public void plainTradeableResolvesToItself()
	{
		// A plain tradeable has no ItemMapping entry -> leaf -> [Component(id, 1)].
		final List<Component> components = resolver.resolveCanonical(ItemID.DRAGON_SCIMITAR);
		assertEquals(1, components.size());
		assertEquals(ItemID.DRAGON_SCIMITAR, components.get(0).getTradeableId());
		assertEquals(1L, components.get(0).getPerItemQuantity());
	}

	/**
	 * A table of real ornament-kitted / (or) ids (confirmed to exist in the client on the test
	 * classpath — an unknown constant is a compile error). None may resolve to its own untradeable
	 * id; each must resolve to one or more tradeable components.
	 */
	@Test
	public void knownOrnamentIdsNeverResolveToUntradeableOwnId()
	{
		final int[] ornamentIds = {
			ItemID.SOULREAPER_AXE_ORN,       // soul reaper axe (or)
			ItemID.AGSG,                     // armadyl godsword (or)
			ItemID.ZGSG,                     // zamorak godsword (or)
			ItemID.BGSG,                     // bandos godsword (or)
			ItemID.SGSG,                     // saradomin godsword (or)
			ItemID.DRAGON_KITESHIELD_GOLD,   // dragon kiteshield (or)
		};

		for (int id : ornamentIds)
		{
			final List<Component> components = resolver.resolveCanonical(id);
			assertNotNull("id " + id, components);
			assertFalse("id " + id + " resolved to no components", components.isEmpty());
			assertFalse("id " + id + " resolved to its own untradeable id",
				tradeableIds(components).contains(id));
		}
	}

	@Test
	public void godswordOrnamentSumsBaseAndKit()
	{
		// A godsword (or)'s base entry also lists a variation id, which must NOT be mistaken for a
		// multi-part pack: the (or) still sums the godsword base + its ornament kit.
		final Map<Integer, Long> byId = byTradeable(resolver.resolveCanonical(ItemID.AGSG));
		final Map<Integer, Long> expected = new HashMap<>();
		expected.put(ItemID.AGS, 1L);
		expected.put(ItemID.AGS_ORNAMENT_KIT, 1L);
		assertEquals(expected, byId);
	}

	/**
	 * {@link PriceService#getCachedCombined}-style guarantee for the Soul reaper axe (or): with a
	 * stubbed {@code /latest} map that contains the tradeable base + kit but NOT the untradeable
	 * (or) id, the resolved components are all present in that map — so the combined price is
	 * computable (today, before the fix, the item resolved to the absent (or) id and was skipped).
	 *
	 * <p>{@code PriceService.latestLow} has no production test seam, and the plan forbids adding one
	 * purely for the test, so this asserts the equivalent resolver-level guarantee: every resolved
	 * component id is a key in the stub {@code /latest} map, which is exactly the precondition
	 * {@code getCachedCombined} needs to return a non-null combined price.
	 */
	@Test
	public void resolvedComponentsPresentInLatestForSoulReaperOrn()
	{
		final Map<Integer, Long> stubLatest = new HashMap<>();
		stubLatest.put(ItemID.SOULREAPER, 9_000_000L);
		stubLatest.put(ItemID.DEMONIC_AXE_ORNAMENT_KIT, 50_000L);
		// Deliberately absent: ItemID.SOULREAPER_AXE_ORN (untradeable, never in /latest).

		final List<Component> components = resolver.resolveCanonical(ItemID.SOULREAPER_AXE_ORN);
		assertFalse(components.isEmpty());
		for (Component c : components)
		{
			assertTrue("component " + c.getTradeableId() + " missing from /latest",
				stubLatest.containsKey(c.getTradeableId()));
		}

		// And the combined current price is the straightforward sum of the stub lows.
		long combined = 0L;
		for (Component c : components)
		{
			combined += stubLatest.get(c.getTradeableId()) * c.getPerItemQuantity();
		}
		assertEquals(9_050_000L, combined);
	}
}
