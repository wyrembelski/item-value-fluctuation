/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure, client-free coverage of {@link VisibilityState} — the visibility-hotkey decision shared by
 * the bank overlay and the hover tooltip. Covers all three modes plus the unbound-is-always-visible
 * default.
 */
public class VisibilityStateTest
{
	@Test
	public void unboundIsAlwaysVisibleRegardlessOfModeOrFlags()
	{
		// No key bound -> inert, always shown, whatever the mode or the (stale) flags say.
		for (VisibilityMode mode : VisibilityMode.values())
		{
			assertTrue(VisibilityState.shouldShow(mode, false, false, false));
			assertTrue(VisibilityState.shouldShow(mode, false, true, false));
			assertTrue(VisibilityState.shouldShow(mode, false, false, true));
			assertTrue(VisibilityState.shouldShow(mode, false, true, true));
		}
	}

	@Test
	public void toggleFollowsTheLatchedToggleState()
	{
		// TOGGLE ignores keyHeld and simply reflects the latch.
		assertTrue(VisibilityState.shouldShow(VisibilityMode.TOGGLE, true, false, true));
		assertTrue(VisibilityState.shouldShow(VisibilityMode.TOGGLE, true, true, true));
		assertFalse(VisibilityState.shouldShow(VisibilityMode.TOGGLE, true, false, false));
		assertFalse(VisibilityState.shouldShow(VisibilityMode.TOGGLE, true, true, false));
	}

	@Test
	public void holdToShowOnlyVisibleWhileKeyHeld()
	{
		assertTrue(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_SHOW, true, true, false));
		assertTrue(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_SHOW, true, true, true));
		assertFalse(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_SHOW, true, false, false));
		assertFalse(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_SHOW, true, false, true));
	}

	@Test
	public void holdToHideHiddenOnlyWhileKeyHeld()
	{
		assertFalse(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_HIDE, true, true, false));
		assertFalse(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_HIDE, true, true, true));
		assertTrue(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_HIDE, true, false, false));
		assertTrue(VisibilityState.shouldShow(VisibilityMode.HOLD_TO_HIDE, true, false, true));
	}

	@Test
	public void initialToggleStateIsHiddenOnlyForHoldToShow()
	{
		// HOLD_TO_SHOW starts hidden; TOGGLE and HOLD_TO_HIDE start shown.
		assertFalse(VisibilityState.initialToggleOn(VisibilityMode.HOLD_TO_SHOW));
		assertTrue(VisibilityState.initialToggleOn(VisibilityMode.TOGGLE));
		assertTrue(VisibilityState.initialToggleOn(VisibilityMode.HOLD_TO_HIDE));
	}

	@Test
	public void boundToggleStartsVisibleViaInitialState()
	{
		// A freshly (re)initialised TOGGLE session: initial latch on, key not held -> visible.
		final VisibilityMode mode = VisibilityMode.TOGGLE;
		final boolean start = VisibilityState.initialToggleOn(mode);
		assertTrue(VisibilityState.shouldShow(mode, true, false, start));
	}

	@Test
	public void boundHoldToShowStartsHiddenViaInitialState()
	{
		// A freshly (re)initialised HOLD_TO_SHOW session: key not held -> hidden to start.
		final VisibilityMode mode = VisibilityMode.HOLD_TO_SHOW;
		final boolean start = VisibilityState.initialToggleOn(mode);
		assertFalse(VisibilityState.shouldShow(mode, true, false, start));
	}
}
