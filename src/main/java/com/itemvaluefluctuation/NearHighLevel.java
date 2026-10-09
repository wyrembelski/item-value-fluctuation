/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

/**
 * Where an item's current price sits relative to its 1-year high: not near it, near it (within the
 * configured threshold), or at/above it. Decided by {@link ColorScale#nearHighLevel(long, long, int)}.
 */
enum NearHighLevel
{
	/** The current price is below the near-high threshold (or the year high is unknown). */
	NONE,
	/** The current price is within the configured percent of the 1-year high, but below it. */
	NEAR,
	/** The current price is at or above the sampled 1-year high. */
	AT
}
