/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * How the visibility hotkey controls whether the bank highlights are shown. Only applies when a
 * key is actually bound; with no key bound the highlights are always shown (see
 * {@link VisibilityState}).
 */
@Getter
@RequiredArgsConstructor
public enum VisibilityMode
{
	/** Tap the key to switch the bank highlights on/off; they stay until pressed again. */
	TOGGLE("Toggle"),
	/** Highlights are hidden normally and shown only while the key is held down. */
	HOLD_TO_SHOW("Hold to show"),
	/** Highlights are shown normally and hidden only while the key is held down. */
	HOLD_TO_HIDE("Hold to hide");

	private final String displayName;

	@Override
	public String toString()
	{
		return displayName;
	}
}
