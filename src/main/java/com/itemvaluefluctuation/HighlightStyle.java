/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How a highlighted bank slot is drawn: a filled square wash (the original look) or a thick glowing
 * outline.
 */
@Getter
@RequiredArgsConstructor
public enum HighlightStyle
{
	SQUARE("Filled square"),
	OUTLINE("Outline");

	private final String displayName;

	@Override
	public String toString()
	{
		return displayName;
	}
}
