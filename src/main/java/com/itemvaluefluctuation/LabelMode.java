/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * What the per-slot label shows for a highlighted item.
 */
@Getter
@RequiredArgsConstructor
public enum LabelMode
{
	/** Percent change over the window, e.g. {@code +19%}. */
	PERCENT("Percent"),
	/** Per-item gp change over the window, e.g. {@code +95}. */
	GP_PER_ITEM("GP (per item)"),
	/** Whole-stack gp change (per-item change x quantity), e.g. {@code +1.65M}. */
	GP_TOTAL("GP (total stack)");

	private final String displayName;

	@Override
	public String toString()
	{
		return displayName;
	}
}
