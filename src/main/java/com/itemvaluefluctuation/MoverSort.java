/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.Comparator;
import java.util.List;

/**
 * How the side panel's mover list is ordered. Each mode exposes a pure
 * {@link #comparator(Mode)} over {@link PanelRowData} with a deterministic name-then-id tiebreak,
 * so the ordering is testable headless (no Swing, no client).
 */
final class MoverSort
{
	private MoverSort()
	{
	}

	enum Mode
	{
		/** Biggest gainer first, biggest loser last (sign-aware percent, descending). */
		PERCENT_DESC("% change"),
		/** Biggest whole-stack gp gain first (sign-aware, descending). */
		GP_DESC("GP change"),
		/** Highest whole-stack current value first. */
		VALUE_DESC("Current value"),
		/** Item name, case-insensitive ascending. */
		NAME_ASC("Name");

		private final String displayName;

		Mode(String displayName)
		{
			this.displayName = displayName;
		}

		String getDisplayName()
		{
			return displayName;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}

	/** A stable tiebreak so equal primary keys never reorder between rebuilds. */
	private static final Comparator<PanelRowData> TIEBREAK =
		Comparator.comparing((PanelRowData r) -> r.getName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparingInt(PanelRowData::getRawItemId);

	/**
	 * The comparator for a mode in its NATURAL direction (the one the mode name implies: % / gp /
	 * value biggest-first, name A-Z). {@link #comparator(Mode, boolean)} can flip it.
	 */
	static Comparator<PanelRowData> comparator(Mode mode)
	{
		return comparator(mode, false);
	}

	/**
	 * The comparator for a mode, optionally reversed. {@code reversed} flips the natural direction,
	 * so the user can, for example, order Losers from biggest drop to smallest instead of the
	 * default biggest-gainer-first.
	 */
	static Comparator<PanelRowData> comparator(Mode mode, boolean reversed)
	{
		final Comparator<PanelRowData> primary;
		switch (mode)
		{
			case GP_DESC:
				primary = Comparator.comparingLong(PanelRowData::stackGpChange).reversed();
				break;
			case VALUE_DESC:
				primary = Comparator.comparingLong(PanelRowData::stackValue).reversed();
				break;
			case NAME_ASC:
				primary = Comparator.comparing((PanelRowData r) -> r.getName(),
					String.CASE_INSENSITIVE_ORDER);
				break;
			case PERCENT_DESC:
			default:
				primary = Comparator
					.comparingDouble((PanelRowData r) -> r.getFluctuation().getPercentChange())
					.reversed();
				break;
		}
		return (reversed ? primary.reversed() : primary).thenComparing(TIEBREAK);
	}

	/**
	 * The aggregate movement of a set of rows (the ones currently shown in the panel): the long-safe
	 * sum of every row's whole-STACK gp change, and the VALUE-WEIGHTED percent that change
	 * represents against the total start-of-window (anchor) stack value of those same rows.
	 *
	 * <p>Pure arithmetic over data already in each {@link PanelRowData} — no client, no Swing, no API
	 * call — so it is unit-testable headless and cheap enough to run on the client thread during the
	 * existing gather. The percent is value-weighted (totalGp / totalAnchor), NOT an average of the
	 * per-item percents, so a big move on a small-value item cannot dominate. Guarded against a
	 * non-positive total anchor (division by zero), in which case {@link TotalMovement#hasPercent}
	 * is false and only the gp total is meaningful.
	 */
	static TotalMovement totalMovement(List<PanelRowData> rows)
	{
		long totalGp = 0L;
		long totalAnchor = 0L;
		for (PanelRowData r : rows)
		{
			totalGp += r.stackGpChange();
			totalAnchor += r.getFluctuation().getAnchorPrice() * Math.max(1, r.getQuantity());
		}
		if (totalAnchor <= 0L)
		{
			return new TotalMovement(totalGp, 0d, false);
		}
		return new TotalMovement(totalGp, (totalGp * 100d) / totalAnchor, true);
	}

	/**
	 * Immutable result of {@link #totalMovement(List)}: the whole-set stack gp change and its
	 * value-weighted percent. {@code hasPercent} is false when the total anchor value was
	 * non-positive (so the percent is meaningless and should be omitted).
	 */
	static final class TotalMovement
	{
		private final long gpChange;
		private final double percentChange;
		private final boolean hasPercent;

		TotalMovement(long gpChange, double percentChange, boolean hasPercent)
		{
			this.gpChange = gpChange;
			this.percentChange = percentChange;
			this.hasPercent = hasPercent;
		}

		long getGpChange()
		{
			return gpChange;
		}

		double getPercentChange()
		{
			return percentChange;
		}

		boolean hasPercent()
		{
			return hasPercent;
		}
	}
}
