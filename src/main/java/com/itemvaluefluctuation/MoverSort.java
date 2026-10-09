/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.Comparator;

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
		GP_DESC("gp change"),
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
}
