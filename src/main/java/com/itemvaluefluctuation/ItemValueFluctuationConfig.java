/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ItemValueFluctuationConfig.CONFIG_GROUP)
public interface ItemValueFluctuationConfig extends Config
{
	String CONFIG_GROUP = "item-value-fluctuation";

	// ------------------------------------------------------------------
	// General
	// ------------------------------------------------------------------
	@ConfigSection(
		name = "General",
		description = "Core behaviour of the bank price heatmap",
		position = 0
	)
	String generalSection = "general";

	@ConfigItem(
		keyName = "timeFrame",
		name = "Time frame",
		description = "The window over which price change is measured. Each profile maps to a"
			+ " lookback supported by the OSRS Wiki prices API (the shortest is 6h).",
		section = generalSection,
		position = 0
	)
	default TimeFrame timeFrame()
	{
		return TimeFrame.WEEK;
	}

	@ConfigItem(
		keyName = "highlightMode",
		name = "Highlight",
		description = "Whether to highlight items that gained value, lost value, or both",
		section = generalSection,
		position = 1
	)
	default HighlightMode highlightMode()
	{
		return HighlightMode.BOTH;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "overlayOpacity",
		name = "Overlay opacity",
		description = "How opaque the colour wash over each bank slot is",
		section = generalSection,
		position = 2
	)
	@Units(Units.PERCENT)
	default int overlayOpacity()
	{
		return 55;
	}

	@ConfigItem(
		keyName = "showPercentLabel",
		name = "Show label",
		description = "Draw a small change label in the corner of each highlighted slot",
		section = generalSection,
		position = 3
	)
	default boolean showPercentLabel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "labelMode",
		name = "Label shows",
		description = "What the slot label displays: the percent change, the per-item GP change,"
			+ " or the whole stack's GP change (shortened as K/M/B)",
		section = generalSection,
		position = 4
	)
	default LabelMode labelMode()
	{
		return LabelMode.PERCENT;
	}

	@ConfigItem(
		keyName = "showTooltip",
		name = "Show tooltip",
		description = "Show a detail tooltip (current price, window-start price, % change,"
			+ " 1-year high) when hovering a highlighted item",
		section = generalSection,
		position = 5
	)
	default boolean showTooltip()
	{
		return true;
	}

	@ConfigItem(
		keyName = "highlightStyle",
		name = "Highlight style",
		description = "How a moved item's slot is drawn: a filled square wash or a thick glowing"
			+ " outline (so you can still read what the item is)",
		section = generalSection,
		position = 6
	)
	default HighlightStyle highlightStyle()
	{
		return HighlightStyle.OUTLINE;
	}

	@ConfigItem(
		keyName = "showBankHighlights",
		name = "Show bank highlights",
		description = "Colour the bank slots. Turn off to keep the bank uncoloured while still"
			+ " using the Movers side panel.",
		section = generalSection,
		position = 7
	)
	default boolean showBankHighlights()
	{
		return true;
	}

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "outlineThickness",
		name = "Outline thickness",
		description = "Border thickness (in pixels) for the Outline highlight style",
		section = generalSection,
		position = 8
	)
	default int outlineThickness()
	{
		return 2;
	}

	// ------------------------------------------------------------------
	// Visibility (reveal/hide the bank highlights on demand with a hotkey)
	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Visibility",
		description = "Optionally bind a hotkey that reveals or hides the bank highlights on"
			+ " demand, so you can keep the plugin on but only colour the bank when you want",
		position = 5
	)
	String visibilitySection = "visibility";

	@ConfigItem(
		keyName = "visibilityHotkey",
		name = "Visibility hotkey",
		description = "A key that controls whether the bank highlights are shown. Leave unbound to"
			+ " always show them (the default). The side panel is never affected.",
		section = visibilitySection,
		position = 0
	)
	default Keybind visibilityHotkey()
	{
		return Keybind.NOT_SET;
	}

	@ConfigItem(
		keyName = "visibilityMode",
		name = "Visibility mode",
		description = "How the hotkey behaves (only applies when a key is bound): Toggle taps the"
			+ " key to switch the highlights on/off; Hold to show reveals them only while held;"
			+ " Hold to hide hides them only while held.",
		section = visibilitySection,
		position = 1
	)
	default VisibilityMode visibilityMode()
	{
		return VisibilityMode.TOGGLE;
	}

	// ------------------------------------------------------------------
	// Year high (an independent signal from recent movement)
	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Year high",
		description = "Highlight items trading at or near their highest instant-sell price over"
			+ " the past year, independent of the chosen time frame",
		position = 1
	)
	String yearHighSection = "yearHigh";

	@ConfigItem(
		keyName = "highlightNearYearHigh",
		name = "Highlight near 1-year high",
		description = "Draw a gold ring around items whose current instant-sell price is at or"
			+ " near their highest instant-sell price over the past year. This is independent of"
			+ " the movement time frame and composes over the movement colour. The wiki price"
			+ " history maxes out at 1 year, so this is a 1-year high, not an all-time high.",
		section = yearHighSection,
		position = 0
	)
	default boolean highlightNearYearHigh()
	{
		return false;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "nearHighThresholdPct",
		name = "Near-high threshold",
		description = "An item within this percent of its 1-year high counts as 'near' the high"
			+ " (at or above the high is treated as 'at' the high)",
		section = yearHighSection,
		position = 1
	)
	@Units(Units.PERCENT)
	default int nearHighThresholdPct()
	{
		return 5;
	}

	@Alpha
	@ConfigItem(
		keyName = "nearHighColor",
		name = "Near-high orb",
		description = "Orb colour when an item is near (within the threshold of) its 1-year high",
		section = yearHighSection,
		position = 2
	)
	default Color nearHighColor()
	{
		return new Color(0xFF, 0xC1, 0x07); // gold
	}

	@Alpha
	@ConfigItem(
		keyName = "atHighColor",
		name = "At-high orb",
		description = "Orb colour when an item is at or above its 1-year high",
		section = yearHighSection,
		position = 3
	)
	default Color atHighColor()
	{
		return new Color(0xFF, 0xEA, 0x00); // brighter gold
	}

	@ConfigItem(
		keyName = "excludeAlchFloorFromYearHigh",
		name = "Ignore alch-floored items",
		description = "Don't ring items whose Grand Exchange price sits just above their high-alch"
			+ " value. Such items are propped up by alching and stay flat all year, so they are"
			+ " permanently at their '1-year high' without being a real sell signal.",
		section = yearHighSection,
		position = 4
	)
	default boolean excludeAlchFloorFromYearHigh()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "alchFloorThresholdPct",
		name = "Alch-floor threshold",
		description = "How close to its high-alch value an item's price must be to count as"
			+ " alch-floored and be ignored by the 1-year-high ring",
		section = yearHighSection,
		position = 5
	)
	@Units(Units.PERCENT)
	default int alchFloorThresholdPct()
	{
		return 5;
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "minYearRangePct",
		name = "Min yearly range %",
		description = "Only ring an item whose price actually swung over the year: its 1-year high"
			+ " must be at least this percent above its 1-year low. Stops stable commodities (runes,"
			+ " ores, bars) from ringing just because their flat price happens to sit at its 'high'.",
		section = yearHighSection,
		position = 6
	)
	@Units(Units.PERCENT)
	default int minYearRangePct()
	{
		return 15;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "minYearRangeGp",
		name = "Min yearly range (GP)",
		description = "Only ring an item whose 1-year high is at least this many GP above its"
			+ " 1-year low (per item). A few GP of yearly wiggle on a cheap commodity is not a"
			+ " meaningful 'near the high' signal, even if the percent looks large.",
		section = yearHighSection,
		position = 7
	)
	default int minYearRangeGp()
	{
		return 100;
	}

	// ------------------------------------------------------------------
	// Filtering (keeps the bank from turning into a rainbow of noise)
	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Filtering",
		description = "Thresholds that decide which items are worth highlighting",
		position = 2
	)
	String filterSection = "filtering";

	@Range(min = 0)
	@ConfigItem(
		keyName = "minItemValue",
		name = "Min item value",
		description = "Ignore items whose current value (per item) is below this many GP."
			+ " Keeps low-value junk from lighting up.",
		section = filterSection,
		position = 0
	)
	default int minItemValue()
	{
		return 1000;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "minTotalValue",
		name = "Min total value",
		description = "Ignore a bank slot whose whole stack (price x quantity) is worth less than"
			+ " this many GP. Keeps small stacks you don't care about from lighting up.",
		section = filterSection,
		position = 1
	)
	default int minTotalValue()
	{
		return 500_000;
	}

	@Range(min = 0)
	@ConfigItem(
		keyName = "minGpSwing",
		name = "Min GP swing",
		description = "Only highlight when the per-item price moved at least this many GP."
			+ " A big percent move on a cheap item can still be noise.",
		section = filterSection,
		position = 2
	)
	default int minGpSwing()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "includePlaceholders",
		name = "Include placeholders",
		description = "Include bank placeholder slots (the empty 'remembered' slots left after you"
			+ " withdraw all of an item). Off by default, since you don't actually own them.",
		section = filterSection,
		position = 3
	)
	default boolean includePlaceholders()
	{
		return false;
	}

	// ------------------------------------------------------------------
	// Gains ramp
	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Gains colours",
		description = "Thresholds and colours for items that went up in value",
		position = 3,
		closedByDefault = true
	)
	String gainsSection = "gains";

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "gainTier1Pct",
		name = "Tier 1 %",
		description = "Minimum gain for the first (lightest) gain colour",
		section = gainsSection,
		position = 0
	)
	@Units(Units.PERCENT)
	default int gainTier1Pct()
	{
		return 5;
	}

	@Alpha
	@ConfigItem(
		keyName = "gainTier1Color",
		name = "Tier 1 colour",
		description = "Colour for a small gain",
		section = gainsSection,
		position = 1
	)
	default Color gainTier1Color()
	{
		return new Color(0xA5, 0xD6, 0xA7); // pale green
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "gainTier2Pct",
		name = "Tier 2 %",
		description = "Minimum gain for the second gain colour",
		section = gainsSection,
		position = 2
	)
	@Units(Units.PERCENT)
	default int gainTier2Pct()
	{
		return 10;
	}

	@Alpha
	@ConfigItem(
		keyName = "gainTier2Color",
		name = "Tier 2 colour",
		description = "Colour for a moderate gain",
		section = gainsSection,
		position = 3
	)
	default Color gainTier2Color()
	{
		return new Color(0x4C, 0xAF, 0x50); // green
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "gainTier3Pct",
		name = "Tier 3 %",
		description = "Minimum gain for the third gain colour",
		section = gainsSection,
		position = 4
	)
	@Units(Units.PERCENT)
	default int gainTier3Pct()
	{
		return 15;
	}

	@Alpha
	@ConfigItem(
		keyName = "gainTier3Color",
		name = "Tier 3 colour",
		description = "Colour for a large gain",
		section = gainsSection,
		position = 5
	)
	default Color gainTier3Color()
	{
		return new Color(0x00, 0xE6, 0x76); // bright green
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "gainTier4Pct",
		name = "Tier 4 %",
		description = "Minimum gain for the top 'spike' gain colour",
		section = gainsSection,
		position = 6
	)
	@Units(Units.PERCENT)
	default int gainTier4Pct()
	{
		return 30;
	}

	@Alpha
	@ConfigItem(
		keyName = "gainTier4Color",
		name = "Tier 4 colour",
		description = "Colour for a sharp spike up",
		section = gainsSection,
		position = 7
	)
	default Color gainTier4Color()
	{
		return new Color(0xC6, 0x1F, 0xFF); // bright purple
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "gainTier5Pct",
		name = "Tier 5 %",
		description = "Minimum gain for the fifth gain colour",
		section = gainsSection,
		position = 8
	)
	@Units(Units.PERCENT)
	default int gainTier5Pct()
	{
		return 50;
	}

	@Alpha
	@ConfigItem(
		keyName = "gainTier5Color",
		name = "Tier 5 colour",
		description = "Colour for a very large spike up",
		section = gainsSection,
		position = 9
	)
	default Color gainTier5Color()
	{
		return new Color(0x7B, 0x1F, 0xA2); // deep purple
	}

	@Range(min = 0, max = 10000)
	@ConfigItem(
		keyName = "gainTier6Pct",
		name = "Tier 6 % (gold)",
		description = "Minimum gain for the top 'moonshot' gold colour (an item that roughly"
			+ " doubled or more over the window)",
		section = gainsSection,
		position = 10
	)
	@Units(Units.PERCENT)
	default int gainTier6Pct()
	{
		return 100;
	}

	@Alpha
	@ConfigItem(
		keyName = "gainTier6Color",
		name = "Tier 6 colour (gold)",
		description = "Colour for a moonshot gain",
		section = gainsSection,
		position = 11
	)
	default Color gainTier6Color()
	{
		return new Color(0xFF, 0xC1, 0x07); // gold
	}

	// ------------------------------------------------------------------
	// Losses ramp
	// ------------------------------------------------------------------
	@ConfigSection(
		name = "Losses colours",
		description = "Thresholds and colours for items that went down in value",
		position = 4,
		closedByDefault = true
	)
	String lossesSection = "losses";

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "lossTier1Pct",
		name = "Tier 1 %",
		description = "Minimum drop (as a positive percent) for the first (lightest) loss colour",
		section = lossesSection,
		position = 0
	)
	@Units(Units.PERCENT)
	default int lossTier1Pct()
	{
		return 5;
	}

	@Alpha
	@ConfigItem(
		keyName = "lossTier1Color",
		name = "Tier 1 colour",
		description = "Colour for a small drop",
		section = lossesSection,
		position = 1
	)
	default Color lossTier1Color()
	{
		return new Color(0xEF, 0x9A, 0x9A); // pale red / salmon
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "lossTier2Pct",
		name = "Tier 2 %",
		description = "Minimum drop for the second loss colour",
		section = lossesSection,
		position = 2
	)
	@Units(Units.PERCENT)
	default int lossTier2Pct()
	{
		return 10;
	}

	@Alpha
	@ConfigItem(
		keyName = "lossTier2Color",
		name = "Tier 2 colour",
		description = "Colour for a moderate drop",
		section = lossesSection,
		position = 3
	)
	default Color lossTier2Color()
	{
		return new Color(0xE5, 0x39, 0x35); // red
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "lossTier3Pct",
		name = "Tier 3 %",
		description = "Minimum drop for the third loss colour",
		section = lossesSection,
		position = 4
	)
	@Units(Units.PERCENT)
	default int lossTier3Pct()
	{
		return 15;
	}

	@Alpha
	@ConfigItem(
		keyName = "lossTier3Color",
		name = "Tier 3 colour",
		description = "Colour for a large drop",
		section = lossesSection,
		position = 5
	)
	default Color lossTier3Color()
	{
		return new Color(0xFF, 0x17, 0x44); // bright red
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "lossTier4Pct",
		name = "Tier 4 %",
		description = "Minimum drop for the top 'crash' loss colour",
		section = lossesSection,
		position = 6
	)
	@Units(Units.PERCENT)
	default int lossTier4Pct()
	{
		return 30;
	}

	@Alpha
	@ConfigItem(
		keyName = "lossTier4Color",
		name = "Tier 4 colour",
		description = "Colour for a sharp crash down",
		section = lossesSection,
		position = 7
	)
	default Color lossTier4Color()
	{
		return new Color(0x7F, 0x00, 0x1F); // deep crimson
	}

	@Range(min = 0, max = 1000)
	@ConfigItem(
		keyName = "lossTier5Pct",
		name = "Tier 5 %",
		description = "Minimum drop for the bottom 'collapse' colour",
		section = lossesSection,
		position = 8
	)
	@Units(Units.PERCENT)
	default int lossTier5Pct()
	{
		return 50;
	}

	@Alpha
	@ConfigItem(
		keyName = "lossTier5Color",
		name = "Tier 5 colour",
		description = "Colour for a severe collapse in value",
		section = lossesSection,
		position = 9
	)
	default Color lossTier5Color()
	{
		return new Color(0x3D, 0x00, 0x0F); // near-black crimson
	}
}
