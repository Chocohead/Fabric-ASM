package com.chocohead.mm.testing;

import java.util.concurrent.ThreadLocalRandom;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

public class TestEnumExtension extends TestEnumMixin {
	private int numberFactory(int limit) {
		return ThreadLocalRandom.current().nextInt(limit);
	}

	@Override
	void magicMethod() {
		super.magicMethod();
	}

	@Override
	public boolean reallyMagicMethod(int value) {
		return super.reallyMagicMethod(numberFactory(value));
	}
}

@Mixin(value = TestEnum.class, remap = false)
class TestEnumMixin {
	@Shadow
	void magicMethod() {
	}

	@Shadow
	public boolean reallyMagicMethod(int value) {
		return false;
	}
}