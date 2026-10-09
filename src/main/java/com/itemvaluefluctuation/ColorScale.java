/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.awt.Color;
import javax.annotation.Nullable;

/**
 * Maps a {@link PriceFluctuation} to the heatmap colour it should wash the bank slot with,
 * honouring the configured highlight mode, tier thresholds, tier colours and the gp/value
 * filters. Returns {@code null} when the item should not be highlighted at all.
 */
class ColorScale
{
	private static final float STALE_ALPHA_FACTOR = 0.4f;
	private static final int GREY = 128;

	private final ItemValueFluctuationConfig config;

	ColorScale(ItemValueFluctuationConfig config)
	{
		this.config = config;
	}

	/**
	 * The highlight colour for this movement, or {@code null} if it does not clear the filters
	 * or falls below the smallest configured tier.
	 *
	 * @param f        the computed price movement for the slot's item
	 * @param quantity the number of items in the bank slot (for the total-value filter)
	 */
	@Nullable
	Color colorFor(PriceFluctuation f, int quantity)
	{
		if (f == null)
		{
			return null;
		}

		// Value / swing filters.
		if (f.getCurrentPrice() < config.minItemValue())
		{
			return null;
		}
		final long totalValue = f.getCurrentPrice() * Math.max(1, quantity);
		if (totalValue < config.minTotalValue())
		{
			return null;
		}
		if (Math.abs(f.getGpChange()) < config.minGpSwing())
		{
			return null;
		}

		final HighlightMode mode = config.highlightMode();
		final double pct = f.getPercentChange();

		if (pct > 0 && mode.showGains())
		{
			return gainColor(pct);
		}
		if (pct < 0 && mode.showLosses())
		{
			return lossColor(-pct);
		}
		return null;
	}

	@Nullable
	private Color gainColor(double gainPct)
	{
		if (gainPct >= config.gainTier6Pct())
		{
			return config.gainTier6Color();
		}
		if (gainPct >= config.gainTier5Pct())
		{
			return config.gainTier5Color();
		}
		if (gainPct >= config.gainTier4Pct())
		{
			return config.gainTier4Color();
		}
		if (gainPct >= config.gainTier3Pct())
		{
			return config.gainTier3Color();
		}
		if (gainPct >= config.gainTier2Pct())
		{
			return config.gainTier2Color();
		}
		if (gainPct >= config.gainTier1Pct())
		{
			return config.gainTier1Color();
		}
		return null;
	}

	@Nullable
	private Color lossColor(double dropPct)
	{
		if (dropPct >= config.lossTier5Pct())
		{
			return config.lossTier5Color();
		}
		if (dropPct >= config.lossTier4Pct())
		{
			return config.lossTier4Color();
		}
		if (dropPct >= config.lossTier3Pct())
		{
			return config.lossTier3Color();
		}
		if (dropPct >= config.lossTier2Pct())
		{
			return config.lossTier2Color();
		}
		if (dropPct >= config.lossTier1Pct())
		{
			return config.lossTier1Color();
		}
		return null;
	}

	/**
	 * Pure decision for the "near 1-year high" ring: where {@code currentPrice} sits relative to
	 * {@code yearHigh}, given a {@code thresholdPct} (0-100) proximity band. Independent of any
	 * client state so it can be unit-tested directly.
	 *
	 * <ul>
	 *   <li>{@code yearHigh <= 0} (unknown) &rarr; {@link NearHighLevel#NONE}.</li>
	 *   <li>{@code current >= yearHigh} &rarr; {@link NearHighLevel#AT} (current can tick above the
	 *       sampled high).</li>
	 *   <li>{@code current >= yearHigh * (100 - thresholdPct) / 100} &rarr; {@link NearHighLevel#NEAR}.</li>
	 *   <li>otherwise &rarr; {@link NearHighLevel#NONE}.</li>
	 * </ul>
	 *
	 * <p>The near comparison is done in {@code long} to avoid float drift.
	 */
	static NearHighLevel nearHighLevel(long currentPrice, long yearHigh, int thresholdPct)
	{
		if (yearHigh <= 0L)
		{
			return NearHighLevel.NONE;
		}
		if (currentPrice >= yearHigh)
		{
			return NearHighLevel.AT;
		}
		final long pct = Math.max(0L, Math.min(100L, thresholdPct));
		// current >= yearHigh * (100 - pct) / 100, rearranged to avoid integer division drift.
		if (currentPrice * 100L >= yearHigh * (100L - pct))
		{
			return NearHighLevel.NEAR;
		}
		return NearHighLevel.NONE;
	}

	/**
	 * Pure test for whether an item's price actually MOVED over the year enough for a "near the
	 * high" ring to be meaningful. A stable commodity (runes, ores, bars) sits in a tight band all
	 * year, so being "at its high" is noise. The yearly range (high minus low) must clear BOTH a
	 * percent gate and an absolute gp gate (per item): a few gp of wiggle on a cheap item fails the
	 * gp gate even if the percent looks large, and a tight band on an expensive item fails the
	 * percent gate even if the gp looks large. Independent of client state so it is unit-testable.
	 *
	 * @param yearHigh    combined 1-year high (per item)
	 * @param yearLow     combined 1-year low (per item)
	 * @param minRangePct minimum (high-low)/low as a percent
	 * @param minRangeGp  minimum (high-low) in gp
	 */
	static boolean hasMeaningfulYearRange(long yearHigh, long yearLow, int minRangePct,
		long minRangeGp)
	{
		if (yearLow <= 0L || yearHigh <= yearLow)
		{
			return false;
		}
		final long rangeGp = yearHigh - yearLow;
		if (rangeGp < minRangeGp)
		{
			return false;
		}
		// rangeGp / yearLow * 100 >= minRangePct, rearranged to avoid integer division drift.
		return rangeGp * 100L >= yearLow * (long) Math.max(0, minRangePct);
	}

	/**
	 * Applies the configured overlay opacity (0-100 percent) to a tier colour, producing the
	 * final 0-255 alpha used when filling a slot.
	 */
	Color withOverlayAlpha(Color base)
	{
		final int alpha = Math.round(config.overlayOpacity() * 255f / 100f);
		return new Color(base.getRed(), base.getGreen(), base.getBlue(),
			Math.max(0, Math.min(255, alpha)));
	}

	/**
	 * A visibly dimmed, desaturated version of {@link #withOverlayAlpha(Color)}, used to show the
	 * previous time frame's colour while the new one is still loading: RGB is blended halfway
	 * toward mid-grey and the overlay alpha is scaled down.
	 */
	Color dimmed(Color base)
	{
		final int alpha = Math.round(config.overlayOpacity() * 255f / 100f * STALE_ALPHA_FACTOR);
		return new Color(
			(base.getRed() + GREY) / 2,
			(base.getGreen() + GREY) / 2,
			(base.getBlue() + GREY) / 2,
			Math.max(0, Math.min(255, alpha)));
	}
}
