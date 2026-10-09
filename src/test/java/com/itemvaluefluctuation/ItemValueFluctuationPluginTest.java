/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Developer launcher. Boots a RuneLite client with this plugin side-loaded so it can be tested
 * in-game via {@code ./gradlew run}. This is a dev harness only; it is not shipped.
 */
public class ItemValueFluctuationPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ItemValueFluctuationPlugin.class);
		RuneLite.main(args);
	}
}
