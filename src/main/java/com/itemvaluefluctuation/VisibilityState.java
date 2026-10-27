/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

/**
 * Pure, client-free decision logic for the visibility hotkey. The plugin owns the live mutable
 * flags (toggle state and whether the key is currently held) and asks this helper whether the bank
 * highlights should be painted, so both the slot overlay and the hover tooltip read one source of
 * truth and can never diverge.
 *
 * <p>When no key is bound the feature is inert and the highlights are always shown, so an existing
 * user who updates and never binds a key sees no change.
 */
final class VisibilityState
{
	private VisibilityState()
	{
	}

	/**
	 * Whether the bank highlights should be painted right now.
	 *
	 * @param mode       the user-selected visibility mode
	 * @param keyBound   whether a hotkey is actually bound (false = {@code Keybind.NOT_SET})
	 * @param keyHeld    whether the bound key is currently held down (used by the hold modes)
	 * @param toggleOn   the latched toggle state (used by {@link VisibilityMode#TOGGLE})
	 * @return {@code true} to paint the highlights, {@code false} to suppress them
	 */
	static boolean shouldShow(VisibilityMode mode, boolean keyBound, boolean keyHeld,
		boolean toggleOn)
	{
		if (!keyBound)
		{
			// Unbound: the feature is inert, the overlay behaves exactly as before (always shown).
			return true;
		}
		switch (mode)
		{
			case HOLD_TO_SHOW:
				return keyHeld;
			case HOLD_TO_HIDE:
				return !keyHeld;
			case TOGGLE:
			default:
				return toggleOn;
		}
	}

	/**
	 * The toggle state a (re)initialised session should start in for the given mode: hidden for
	 * {@link VisibilityMode#HOLD_TO_SHOW}, shown for everything else. The hold modes further depend
	 * on the live key-held state, but this fixes the starting latch so a mode switch or login never
	 * leaves TOGGLE stuck hidden or HOLD_TO_SHOW stuck shown.
	 */
	static boolean initialToggleOn(VisibilityMode mode)
	{
		return mode != VisibilityMode.HOLD_TO_SHOW;
	}
}
