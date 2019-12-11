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