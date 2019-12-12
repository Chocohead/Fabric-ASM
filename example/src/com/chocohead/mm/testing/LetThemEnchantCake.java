/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.testing;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

public class LetThemEnchantCake extends EnchantmentTargetMixin {
	@Override
	public boolean isAcceptableItem(Item item) {
		return item == Items.CAKE;
	}
}

@Mixin(EnchantmentTarget.class)
abstract class EnchantmentTargetMixin {
	@Shadow
	abstract boolean isAcceptableItem(Item item);
}