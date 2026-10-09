/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import javax.annotation.Nullable;
import lombok.Value;

/**
 * An immutable, Swing-free snapshot of one bank item's movement, built on the client thread and
 * read on the EDT by the side panel. It carries exactly what a panel row and its detail view need,
 * so no client API is touched once this crosses the thread boundary. The pure sort/filter logic
 * ({@link MoverSort}, {@link MoverView}) operates on this type, which keeps that logic unit-testable
 * without Swing or a client.
 */
@Value
public class PanelRowData
{
	/** The raw bank item id (used for the icon and detail header). */
	int rawItemId;

	/** The display name resolved on the client thread. */
	String name;

	/** How many of this item are in the bank (stack size). */
	int quantity;

	/** The combined fluctuation for this item; never {@code null} for a row (empty rows are omitted). */
	PriceFluctuation fluctuation;

	/**
	 * True when the overlay would actually paint a MOVEMENT colour for this item (cleared the swing
	 * filter, highlight mode and the smallest gain/loss tier). A row can be present without this —
	 * e.g. an item that is only ringed near its 1-year high — so the Gainers/Losers filters key off
	 * this flag, NOT the raw sign, to match what the bank highlights.
	 */
	boolean movementHighlighted;

	/** Where the item sits relative to its 1-year high; {@code NONE} when not ringed. */
	NearHighLevel nearHigh;

	/** The combined 1-year high in gp, or 0 if unknown / not ringed. */
	long yearHigh;

	/** True when the item is priced from more than one tradeable component. */
	boolean pricedFromComponents;

	/** Set when a single component's id differs from the raw id (e.g. a degraded/combined item); else {@code null}. */
	@Nullable
	String pricedViaName;

	/** How many tradeable components the item resolved to. */
	int componentCount;

	/** Whole-stack current value in gp (per-item current price times quantity, min 1). */
	long stackValue()
	{
		return fluctuation.getCurrentPrice() * Math.max(1, quantity);
	}

	/** Whole-stack gp change in gp (per-item gp change times quantity, min 1). */
	long stackGpChange()
	{
		return fluctuation.getGpChange() * Math.max(1, quantity);
	}
}
