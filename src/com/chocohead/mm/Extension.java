/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm;

import java.util.Map;
import java.util.function.Consumer;

import org.objectweb.asm.tree.ClassNode;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

final class Extension implements IExtension {
	private final String mixinPackage;
	private final Map<String, Consumer<ClassNode>> classReplacers;

	Extension(String mixinPackage, Map<String, Consumer<ClassNode>> classReplacers) {
		this.mixinPackage = mixinPackage;
		this.classReplacers = classReplacers;
	}

	@Override
	public boolean checkActive(MixinEnvironment environment) {
		return true;
	}

	@Override
	public void preApply(ITargetClassContext context) {
		ClassInfo info = context.getClassInfo();

		if (!info.isMixin()) {//Replacing other Mixins sounds like a world of trouble
			Consumer<ClassNode> replacer = classReplacers.get(info.getName());
			if (replacer != null) replacer.accept(context.getClassNode());
		}
	}

	@Override
	public void postApply(ITargetClassContext context) {
		ClassInfo info = context.getClassInfo();

		if (!info.isMixin()) {//Shouldn't be but checking doesn't hurt
			ClassNode node = context.getClassNode();

			if (node.signature.contains(mixinPackage + info.getName())) {
				//For some reason Mixin likes to tack interfaces into the Mixin'd class's signature
				//This also likes to crash if the JVM has to resolve said signature
				node.signature = node.signature.replace('L' + mixinPackage + info.getName() + ';', "");
			}
		}
	}

	@Override
	public void export(MixinEnvironment env, String name, boolean force, ClassNode classNode) {
	}
}