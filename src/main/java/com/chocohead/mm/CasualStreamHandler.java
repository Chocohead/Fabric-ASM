/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.security.Permission;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;

public final class CasualStreamHandler extends URLStreamHandler {
	private static final class CasualConnection extends URLConnection {
		private final byte[] realStream;

		public CasualConnection(URL url, byte[] realStream) {
			super(url);

			this.realStream = realStream;
		}

		@Override
		public void connect() throws IOException {
			System.out.println("Connection attempt");
			throw new UnsupportedOperationException();
		}

		@Override
		public InputStream getInputStream() {
			if (dumper == null) {
				System.err.println("Asked for " + url.getPath() + " too early to export");
			} else {
				dumper.accept(url.getPath().substring(1, url.getPath().length() - 6).replace('/', '.'), realStream);
			}
			return new ByteArrayInputStream(realStream);
		}

		@Override
		public Permission getPermission() {
			return null;
		}
	}

	static BiConsumer<String, byte[]> dumper;
	private final Map<String, byte[]> providers;

	public static URL create(String name, byte[] stream) {
		return create(Collections.singletonMap('/' + name.replace('.', '/') + ".class", stream));
	}

	public static URL create(Map<String, byte[]> mixins) {
		try {
			return new URL("magic-at", null, -1, "/", new CasualStreamHandler(mixins));
		} catch (MalformedURLException e) {
			throw new RuntimeException("Unexpected error creating URL", e);
		}
	}

	//There is a proper way to do this too https://stackoverflow.com/questions/26363573/registering-and-using-a-custom-java-net-url-protocol
	//Unfortunately the proper way requires being present on the system classloader, which we're not going to be :|
	public CasualStreamHandler(Map<String, byte[]> providers) {
		this.providers = providers;
	}

	@Override
	protected URLConnection openConnection(URL url) throws IOException {
		//System.out.println(providers.keySet());
		//System.out.println("Open connection on " + url.getPath());
		if (!providers.containsKey(url.getPath())) return null; //Who?
		//System.out.println("### PASSED ###");
		return new CasualConnection(url, providers.get(url.getPath()));
	}
}