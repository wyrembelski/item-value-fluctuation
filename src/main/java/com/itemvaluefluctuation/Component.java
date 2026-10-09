/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import lombok.Value;

/**
 * One tradeable building block of a (possibly untradeable) bank item, together with how many of
 * that tradeable the parent item is worth. A plain tradeable item resolves to a single
 * {@code Component} with {@code perItemQuantity == 1}; a combined/variant item (barrows armour,
 * an ornament-kitted weapon, an avernic defender, ...) resolves to one or more components whose
 * summed prices give the parent's Grand Exchange value.
 */
@Value
public class Component
{
	/** The tradeable item id whose price stands in for this part of the parent item. */
	int tradeableId;

	/** How many of {@link #tradeableId} one of the parent item is worth. */
	long perItemQuantity;
}
