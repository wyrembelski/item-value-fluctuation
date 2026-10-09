/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A named time-window "profile" the user can pick. Each profile maps to a lookback value
 * accepted by the OSRS Wiki real-time prices {@code /timeseries} endpoint. The shortest real
 * lookback the API supports is 6h, so "Aggressive" is 6h rather than a tighter window.
 */
@Getter
@RequiredArgsConstructor
public enum TimeFrame
{
	// windowSeconds = how far back the anchor sits; bulkStepSeconds = the aggregate block size we
	// snapshot for the bulk anchor (0 = no bulk anchor available, fall back to per-item timeseries).
	// The wiki's /5m endpoint retains ~24h and /1h retains ~1 year, so: 6h/24h use /5m; 7d/30d/6m
	// use /1h (verified /1h returns data at a 182-day-ago timestamp). Only 1y sits at the very edge
	// of /1h retention, so it keeps the per-item fallback (and is usually already cached by the
	// near-high ring, which fetches 1y history anyway).
	AGGRESSIVE("Aggressive (6h)", "6h", 6 * 3600L, 300L),
	DAY("Day (24h)", "24h", 24 * 3600L, 300L),
	WEEK("Week (7d)", "7d", 7 * 24 * 3600L, 3600L),
	MONTH("Month (30d)", "30d", 30 * 24 * 3600L, 3600L),
	HALF_YEAR("Half-year (6m)", "6m", 182L * 24 * 3600L, 3600L),
	YEAR("Year (1y)", "1y", 365L * 24 * 3600L, 0L);

	private final String displayName;

	/**
	 * The {@code lookback} query value for the prices API ({@code 6h}, {@code 24h}, {@code 7d},
	 * {@code 30d}, {@code 6m}, {@code 1y}).
	 */
	private final String lookback;

	/** How far back (seconds) the window-start anchor sits from "now". */
	private final long windowSeconds;

	/**
	 * The aggregate block size (seconds) to snapshot for a BULK anchor: 300 -> {@code /5m},
	 * 3600 -> {@code /1h}, 0 -> no bulk anchor available (use the per-item {@code /timeseries}).
	 */
	private final long bulkStepSeconds;

	/** Whether a single bulk aggregate snapshot can supply this timeframe's anchor. */
	public boolean hasBulkAnchor()
	{
		return bulkStepSeconds > 0L;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
