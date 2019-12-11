/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.testing;

import com.chocohead.mm.api.ClassTinkerers;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import net.minecraft.item.ItemStack;

public class EarlyRiser implements Runnable {
	@Override
	public void run() {
		MappingResolver remapper = FabricLoader.getInstance().getMappingResolver();

		String bannerPattern = remapper.mapClassName("intermediary", "net.minecraft.class_2582");
		ClassTinkerers.enumBuilder(bannerPattern, String.class, String.class).addEnum("TEST_PATTERN", "amazing_test", "test").build();

		String itemstack = 'L' + remapper.mapClassName("intermediary", "net.minecraft.class_1799") + ';';
		ClassTinkerers.enumBuilder(bannerPattern, "Ljava/lang/String;", "Ljava/lang/String;", itemstack).addEnum("TEST_PATTERN_2", () -> new Object[] {"flawless_test", "t3st", ItemStack.EMPTY}).build();

		String clickEventAction = remapper.mapClassName("intermediary", "net.minecraft.class_2558$class_2559");
		ClassTinkerers.enumBuilder(clickEventAction, String.class, boolean.class).addEnum("TEST_COMMAND", "test_command", true).build();

		ClassTinkerers.enumBuilder("com.chocohead.mm.testing.TestEnum", long.class, int[].class).addEnum("TEST", 0L, new int[0]).build();
		ClassTinkerers.enumBuilder("com.chocohead.mm.testing.TestEnum", int[].class, long.class).addEnum("TEST_ALSO", new int[0], Long.MAX_VALUE).build();
		ClassTinkerers.enumBuilder("com.chocohead.mm.testing.TestEnum", double.class, double.class).addEnum("TEST_AS_WELL", 1D, 0D).build();
		ClassTinkerers.enumBuilder("com.chocohead.mm.testing.TestEnum", new Class[0]).addEnum("TEST_TOO").addEnumSubclass("TEST_THREE", "com.chocohead.mm.testing.TestEnumExtension").build();

		ClassTinkerers.enumBuilder("net.minecraft.enchantment.EnchantmentTarget", new Class[0]).addEnumSubclass("CAKE", "com.chocohead.mm.testing.LetThemEnchantCake").build();
	}
}