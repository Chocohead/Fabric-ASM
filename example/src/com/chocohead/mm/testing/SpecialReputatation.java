/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.testing;

import net.minecraft.village.VillagerGossips.Reputation;

public class SpecialReputatation extends Reputation {
	@Override
	public String toString() {
		return SpecialReputatation.class.getSimpleName() + '@' + Integer.toHexString(hashCode());
	}
}