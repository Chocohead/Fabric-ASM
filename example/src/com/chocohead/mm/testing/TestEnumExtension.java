/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
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