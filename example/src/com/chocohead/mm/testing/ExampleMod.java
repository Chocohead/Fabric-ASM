/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.testing;

import net.minecraft.block.entity.BannerPattern;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.item.Items;
import net.minecraft.text.event.ClickEvent.Action;
import net.minecraft.village.VillagerGossips;
import net.minecraft.village.VillagerGossips.Reputation;

import java.util.Arrays;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.chocohead.mm.api.ClassTinkerers;

import net.fabricmc.api.ModInitializer;

public class ExampleMod implements ModInitializer {
	public static final Logger LOGGER = LogManager.getLogger();

	@Override
	public void onInitialize() {
		LOGGER.info("Normal reputation: " + new Reputation());
		LOGGER.info("Special reputation: " + new SpecialReputatation());

		LOGGER.info("Math.max(5, 6) = " + Math.max(5, 6));
		LOGGER.info("VillagerGossips.max(5, 6) = " + VillagerGossips.max(5, 6));

		BannerPattern pattern = ClassTinkerers.getEnum(BannerPattern.class, "TEST_PATTERN");
		LOGGER.info("Banner pattern: " + pattern + " with ordinal " + pattern.ordinal());

		for (BannerPattern banner : BannerPattern.values()) {
			LOGGER.debug(banner.ordinal() + " => " + banner);
		}

		LOGGER.info("Generic BannerPattern interfaces: " + Arrays.toString(BannerPattern.class.getGenericInterfaces()));

		Action action = Action.get("test_command");
		LOGGER.info("Test click action: " + action + " with ordinal " + action.ordinal());

		for (TestEnum test : TestEnum.values()) {
			LOGGER.debug(test.ordinal() + " => " + test);
		}

		EnchantmentTarget target = ClassTinkerers.getEnum(EnchantmentTarget.class, "CAKE");
		LOGGER.info("Can enchant cake? " + target.isAcceptableItem(Items.CAKE));
	}
}