/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.testing;

import com.chocohead.mm.api.ClassTinkerers;

import net.minecraft.item.ItemStack;

public class EarlyRiser implements Runnable {
	@Override
	public void run() {
		boolean dev = "true".equalsIgnoreCase(System.getProperty("fabric.development")); //FabricLoader.getInstance().isDevelopmentEnvironment()

		String name = "net.minecraft." + (dev ? "block.entity.BannerPattern" : "class_2582");
		ClassTinkerers.enumBuilder(name, String.class, String.class).addEnum("TEST_PATTERN", "amazing_test", "test").build();

		String itemstack = "Lnet.minecraft." + (dev ? "item.ItemStack" : "class_1799") + ';';
		ClassTinkerers.enumBuilder(name, "Ljava/lang/String;", "Ljava/lang/String;", itemstack).addEnum("TEST_PATTERN_2", () -> new Object[] {"flawless_test", "t3st", ItemStack.EMPTY}).build();
	}
}