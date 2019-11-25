package com.chocohead.mm;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.ClassInfo;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

public class Extension implements IExtension {
	private final String mixinPackage;

	public Extension(String mixinPackage) {
		this.mixinPackage = mixinPackage;
	}

	@Override
	public boolean checkActive(MixinEnvironment environment) {
		return true;
	}

	@Override
	public void preApply(ITargetClassContext context) {
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