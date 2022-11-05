/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm;

import java.util.Map;

import com.google.common.collect.ForwardingMap;

public class UnremovableMap<K, V> extends ForwardingMap<K, V> {
	private final Map<K, V> map;

	public UnremovableMap(Map<K, V> map) {
		this.map = map;
	}

	@Override
	protected Map<K, V> delegate() {
		return map;
	}

	@Override
	public V put(K key, V value) {
		if (map.containsKey(key)) {
			throw new UnsupportedOperationException();
		} else {
			return map.put(key, value);
		}
	}

	@Override
	public V remove(Object object) {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean remove(Object key, Object value) {
		throw new UnsupportedOperationException();
	}
}