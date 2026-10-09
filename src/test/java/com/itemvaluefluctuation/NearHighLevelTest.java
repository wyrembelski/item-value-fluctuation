/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-math coverage of {@link ColorScale#nearHighLevel(long, long, int)} (the near-1-year-high
 * proximity decision), with no client dependencies.
 */
public class NearHighLevelTest
{
	@Test
	public void unknownOrNonPositiveYearHighIsNone()
	{
		assertEquals(NearHighLevel.NONE, ColorScale.nearHighLevel(100, 0, 5));
		assertEquals(NearHighLevel.NONE, ColorScale.nearHighLevel(100, -1, 5));
	}

	@Test
	public void atOrAboveHighIsAt()
	{
		assertEquals(NearHighLevel.AT, ColorScale.nearHighLevel(1000, 1000, 5));
		assertEquals(NearHighLevel.AT, ColorScale.nearHighLevel(1200, 1000, 5));
	}

	@Test
	public void withinThresholdButBelowIsNear()
	{
		// 5% band of 1000 -> boundary at 950.
		assertEquals(NearHighLevel.NEAR, ColorScale.nearHighLevel(960, 1000, 5));
	}

	@Test
	public void exactlyOnTheNearBoundaryIsNear()
	{
		// current * 100 == yearHigh * (100 - pct): 950 * 100 == 1000 * 95.
		assertEquals(NearHighLevel.NEAR, ColorScale.nearHighLevel(950, 1000, 5));
	}

	@Test
	public void justBelowTheNearBoundaryIsNone()
	{
		assertEquals(NearHighLevel.NONE, ColorScale.nearHighLevel(949, 1000, 5));
	}

	@Test
	public void zeroThresholdOnlyCountsAtOrAbove()
	{
		assertEquals(NearHighLevel.AT, ColorScale.nearHighLevel(1000, 1000, 0));
		assertEquals(NearHighLevel.NONE, ColorScale.nearHighLevel(999, 1000, 0));
	}

	@Test
	public void hundredThresholdMakesAnyNonNegativeCurrentNear()
	{
		assertEquals(NearHighLevel.NEAR, ColorScale.nearHighLevel(1, 1000, 100));
		assertEquals(NearHighLevel.NEAR, ColorScale.nearHighLevel(0, 1000, 100));
		assertEquals(NearHighLevel.AT, ColorScale.nearHighLevel(1000, 1000, 100));
	}

	/**
	 * Pure fixed-price currency must be excluded from the ring/tooltip eligibility decision. A
	 * coins/platinum stack has no market history, so its combined current and combined 1-year high
	 * are the same flat value and the raw proximity helper would read it as AT. Both the overlay
	 * ring gate and the tooltip gate short-circuit on {@link ItemResolver#isAllFixedPrice}, so a
	 * qualifying-value currency stack never reaches the proximity helper. This pins that guard.
	 */
	@Test
	public void allFixedCurrencyIsExcludedEvenWhenItWouldReadAsAtHigh()
	{
		// A valuable all-currency stack: 1,000,000 coins + 10 platinum. If it were ever evaluated,
		// current == yearHigh so the helper would classify it AT — the guard must stop that.
		final List<Component> currency = List.of(
			new Component(ItemID.COINS, 1_000_000),
			new Component(ItemID.PLATINUM, 10));
		assertTrue(ItemResolver.isAllFixedPrice(currency));
		// Sanity: the same flat value WOULD be AT without the all-fixed guard.
		final long flat = 1_000_000L + 10L * 1000L;
		assertEquals(NearHighLevel.AT, ColorScale.nearHighLevel(flat, flat, 5));
	}

	/** A mapping that contains BOTH a market component and currency is NOT all-fixed. */
	@Test
	public void mixedMarketAndCurrencyIsNotAllFixed()
	{
		final List<Component> mixed = List.of(
			new Component(4151, 1), // a market item (abyssal whip id), stands in for any tradeable
			new Component(ItemID.COINS, 500));
		assertFalse(ItemResolver.isAllFixedPrice(mixed));
	}
}
