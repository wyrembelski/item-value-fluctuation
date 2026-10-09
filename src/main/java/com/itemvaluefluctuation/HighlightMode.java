/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Which direction of price movement to highlight in the bank.
 */
@Getter
@RequiredArgsConstructor
public enum HighlightMode
{
	GAINS_ONLY("Gains only"),
	LOSSES_ONLY("Losses only"),
	BOTH("Gains and losses");

	private final String displayName;

	public boolean showGains()
	{
		return this == GAINS_ONLY || this == BOTH;
	}

	public boolean showLosses()
	{
		return this == LOSSES_ONLY || this == BOTH;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
