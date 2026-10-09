/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

/**
 * Pure, headless coverage of the side panel's sort ({@link MoverSort}) and view-filter
 * ({@link MoverView}) logic. No Swing, no client, no {@link PriceService} — rows are built directly
 * from {@link PanelRowData} / {@link PriceFluctuation} constructor values.
 */
public class MoverLogicTest
{
	/**
	 * Builds a row. {@code pct}/{@code gpChange} are per-item; current price and quantity drive the
	 * whole-stack value/gp sort keys.
	 */
	private static PanelRowData row(int id, String name, int qty, long current, double pct,
		long gpChange, NearHighLevel nearHigh)
	{
		// Movement-highlighted by default (what the sort/gainer/loser tests assume).
		return row(id, name, qty, current, pct, gpChange, nearHigh, true);
	}

	private static PanelRowData row(int id, String name, int qty, long current, double pct,
		long gpChange, NearHighLevel nearHigh, boolean movementHighlighted)
	{
		final long anchor = current - gpChange;
		final PriceFluctuation f = new PriceFluctuation(id, current, anchor,
			Math.max(current, anchor), Math.min(current, anchor), pct, gpChange);
		return new PanelRowData(id, name, qty, f, movementHighlighted, nearHigh,
			nearHigh == NearHighLevel.NONE ? 0 : current, false, null, 1);
	}

	private static List<Integer> ids(List<PanelRowData> rows)
	{
		return rows.stream().map(PanelRowData::getRawItemId).collect(Collectors.toList());
	}

	private static List<PanelRowData> sorted(List<PanelRowData> rows, MoverSort.Mode mode)
	{
		final List<PanelRowData> copy = new ArrayList<>(rows);
		copy.sort(MoverSort.comparator(mode));
		return copy;
	}

	private static List<PanelRowData> filtered(List<PanelRowData> rows, MoverView.Mode mode)
	{
		return rows.stream().filter(MoverView.predicate(mode)).collect(Collectors.toList());
	}

	// Fixed fixture reused across cases.
	private final PanelRowData bigGainer = row(1, "Big gainer", 1, 1_000, 50.0, 400, NearHighLevel.AT);
	private final PanelRowData smallGainer = row(2, "Small gainer", 10, 500, 5.0, 20, NearHighLevel.NONE);
	private final PanelRowData flat = row(3, "Flat item", 1, 2_000, 0.0, 0, NearHighLevel.NEAR);
	private final PanelRowData smallLoser = row(4, "Small loser", 1, 800, -5.0, -40, NearHighLevel.NONE);
	private final PanelRowData bigLoser = row(5, "Big loser", 1, 300, -40.0, -200, NearHighLevel.NONE);

	private List<PanelRowData> all()
	{
		return new ArrayList<>(Arrays.asList(bigGainer, smallGainer, flat, smallLoser, bigLoser));
	}

	@Test
	public void percentDescGainerTopLoserBottom()
	{
		// +50, +5, 0, -5, -40  ->  ids 1,2,3,4,5
		assertEquals(Arrays.asList(1, 2, 3, 4, 5), ids(sorted(all(), MoverSort.Mode.PERCENT_DESC)));
	}

	@Test
	public void gpDescUsesWholeStackChange()
	{
		// stack gp change: big gainer +400, small gainer 20*10=+200, flat 0, small loser -40,
		// big loser -200  ->  1,2,3,4,5
		assertEquals(Arrays.asList(1, 2, 3, 4, 5), ids(sorted(all(), MoverSort.Mode.GP_DESC)));
	}

	@Test
	public void valueDescUsesWholeStackValue()
	{
		// stack value: small gainer 500*10=5000, flat 2000, big gainer 1000, small loser 800,
		// big loser 300  ->  2,3,1,4,5
		assertEquals(Arrays.asList(2, 3, 1, 4, 5), ids(sorted(all(), MoverSort.Mode.VALUE_DESC)));
	}

	@Test
	public void nameAscIsCaseInsensitive()
	{
		// Names: "Big gainer"(1), "Big loser"(5), "Flat item"(3), "Small gainer"(2),
		// "Small loser"(4)
		assertEquals(Arrays.asList(1, 5, 3, 2, 4), ids(sorted(all(), MoverSort.Mode.NAME_ASC)));
	}

	@Test
	public void equalPrimaryKeysBreakTiesByNameThenId()
	{
		final PanelRowData a = row(20, "Zeta", 1, 100, 10.0, 10, NearHighLevel.NONE);
		final PanelRowData b = row(10, "Alpha", 1, 100, 10.0, 10, NearHighLevel.NONE);
		final PanelRowData c = row(30, "Alpha", 1, 100, 10.0, 10, NearHighLevel.NONE);
		// All equal on percent; tiebreak: name asc ("Alpha" before "Zeta"), then id asc (10 < 30).
		final List<PanelRowData> input = new ArrayList<>(Arrays.asList(a, b, c));
		assertEquals(Arrays.asList(10, 30, 20), ids(sorted(input, MoverSort.Mode.PERCENT_DESC)));
	}

	@Test
	public void viewAllKeepsEverything()
	{
		assertEquals(Arrays.asList(1, 2, 3, 4, 5), ids(filtered(all(), MoverView.Mode.ALL)));
	}

	@Test
	public void viewGainersKeepsOnlyPositiveMoves()
	{
		assertEquals(Arrays.asList(1, 2), ids(filtered(all(), MoverView.Mode.GAINERS)));
	}

	@Test
	public void viewLosersKeepsOnlyNegativeMoves()
	{
		assertEquals(Arrays.asList(4, 5), ids(filtered(all(), MoverView.Mode.LOSERS)));
	}

	@Test
	public void viewNearHighKeepsOnlyRingedRows()
	{
		// bigGainer=AT, flat=NEAR; both ringed. The rest are NONE.
		assertEquals(Arrays.asList(1, 3), ids(filtered(all(), MoverView.Mode.NEAR_HIGH)));
	}

	@Test
	public void gainersLosersIgnoreRowsThatAreOnlyRinged()
	{
		// A -2% item that is NOT movement-highlighted (only present because it is ringed near its
		// 1-year high) must NOT show under Losers, and a ringed-only +3% must NOT show under Gainers.
		final PanelRowData ringedOnlyLoser =
			row(40, "Ringed dip", 1, 1_000, -2.0, -20, NearHighLevel.NEAR, false);
		final PanelRowData ringedOnlyGain =
			row(41, "Ringed rise", 1, 1_000, 3.0, 30, NearHighLevel.AT, false);
		final List<PanelRowData> input = new ArrayList<>(Arrays.asList(ringedOnlyLoser, ringedOnlyGain));
		assertEquals(Collections.emptyList(), ids(filtered(input, MoverView.Mode.LOSERS)));
		assertEquals(Collections.emptyList(), ids(filtered(input, MoverView.Mode.GAINERS)));
		// They still show under Near-high, which is why they are in the list at all.
		assertEquals(Arrays.asList(40, 41), ids(filtered(input, MoverView.Mode.NEAR_HIGH)));
	}

	@Test
	public void reversedSortFlipsOrder()
	{
		// Natural %-desc is 1,2,3,4,5; reversed is biggest-drop-first 5,4,3,2,1.
		final List<PanelRowData> copy = new ArrayList<>(all());
		copy.sort(MoverSort.comparator(MoverSort.Mode.PERCENT_DESC, true));
		assertEquals(Arrays.asList(5, 4, 3, 2, 1), ids(copy));
	}
}
