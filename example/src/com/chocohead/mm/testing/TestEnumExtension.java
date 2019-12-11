package com.chocohead.mm.testing;

import org.spongepowered.asm.mixin.Mixin;

public class TestEnumExtension extends TestEnumMixin {
	@Override
	void magicMethod() {
		super.magicMethod();
	}
}

@Mixin(value = TestEnum.class, remap = false)
class TestEnumMixin {
	void magicMethod() {
	}
}