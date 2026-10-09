/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import lombok.Value;

/**
 * The computed price movement for a single item over the selected time window. This is the
 * render-ready model the overlay (and, later, the side panel) reads. All prices are the
 * instant-sell ("low") price, which is the honest "what could I sell this for" number.
 */
@Value
public class PriceFluctuation
{
	int itemId;

	/** Current instant-sell price per item, in gp. */
	long currentPrice;

	/** Instant-sell price at the start of the window, in gp. */
	long anchorPrice;

	/** The highest instant-sell price seen within the longest window we fetched (1y max). */
	long periodHigh;

	/** The lowest instant-sell price seen within the window. */
	long periodLow;

	/** Signed percent change from anchor to current, e.g. {@code +12.5} or {@code -8.0}. */
	double percentChange;

	/** Signed absolute change per item, in gp ({@code currentPrice - anchorPrice}). */
	long gpChange;

	public boolean isGain()
	{
		return percentChange > 0;
	}

	public boolean isLoss()
	{
		return percentChange < 0;
	}
}
