/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.util.function.Predicate;

/**
 * Which movers the side panel shows. Each mode exposes a pure {@link #predicate(Mode)} over
 * {@link PanelRowData}, so the selection is testable headless (no Swing, no client).
 */
final class MoverView
{
	private MoverView()
	{
	}

	enum Mode
	{
		ALL("All"),
		GAINERS("Gainers"),
		LOSERS("Losers"),
		NEAR_HIGH("Near 1-year high");

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

	static Predicate<PanelRowData> predicate(Mode mode)
	{
		switch (mode)
		{
			case GAINERS:
				// Only items the bank actually highlights as a gain (not a sub-threshold +2% that
				// is in the list solely because it is ringed near its 1-year high).
				return r -> r.isMovementHighlighted() && r.getFluctuation().isGain();
			case LOSERS:
				return r -> r.isMovementHighlighted() && r.getFluctuation().isLoss();
			case NEAR_HIGH:
				return r -> r.getNearHigh() != NearHighLevel.NONE;
			case ALL:
			default:
				return r -> true;
		}
	}
}
