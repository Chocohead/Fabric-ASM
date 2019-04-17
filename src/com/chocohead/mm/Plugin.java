/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.InnerClassNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.google.gson.JsonElement;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import com.chocohead.mm.api.ClassTinkerers;
import com.chocohead.mm.api.EnumAdder;

public class Plugin implements IMixinConfigPlugin {
	private static final Consumer<URL> addURL;
	static {
		ClassLoader loader = Plugin.class.getClassLoader();
		Method addUrlMethod = null;
		for (Method method : loader.getClass().getDeclaredMethods()) {
			/*System.out.println("Type: " + method.getReturnType());
			System.out.println("Params: " + method.getParameterCount() + ", " + Arrays.toString(method.getParameterTypes()));*/
			if (method.getReturnType() == Void.TYPE && method.getParameterCount() == 1 && method.getParameterTypes()[0] == URL.class) {
				addUrlMethod = method; //Probably
				break;
			}
		}
		if (addUrlMethod == null) throw new IllegalStateException("Couldn't find method in " + loader);
		try {
			addUrlMethod.setAccessible(true);
			MethodHandle handle = MethodHandles.lookup().unreflect(addUrlMethod);
			addURL = url -> {
				try {
					handle.invoke(loader, url);
				} catch (Throwable t) {
					throw new RuntimeException("Unexpected error adding URL", t);
				}
			};
		} catch (IllegalAccessException e) {
			throw new RuntimeException("Couldn't get handle for " + addUrlMethod, e);
		}
	}
	final List<String> mixins = new ArrayList<>();
	private Map<String, Set<Consumer<ClassNode>>> classModifiers;

	@Override
	public void onLoad(String rawMixinPackage) {
		String mixinPackage = rawMixinPackage.replace('.', '/');

		Map<String, Set<String>> transforms = new HashMap<>();
		try {
			Enumeration<URL> urls = MM.class.getClassLoader().getResources("silky.at");
			while (urls.hasMoreElements()) {
				URL url = urls.nextElement();
				//System.out.println("Found AT: " + url);

				try (Scanner scanner = new Scanner(url.openStream())) {
					//System.out.println("Made scanner");
					while (scanner.hasNextLine()) {
						String line = scanner.nextLine().trim();
						//System.out.println("On line: \"" + line + '\"');
						if (line.isEmpty() || line.startsWith("#")) continue;

						int split = line.indexOf(' ');
						String className, method;
						if (split > 0) {
							className = line.substring(0, split++);
							method = line.substring(split);
						} else {
							className = line;
							method = "<*>";
						}

						transforms.computeIfAbsent(className, k -> new HashSet<>()).add(method);
					}
					//System.out.println("Finished with scanner");
				}
			}
		} catch (IOException e) {
			throw new RuntimeException("Error loading access transformers", e);
		}

		//transforms.computeIfAbsent("net.minecraft.item.ItemStack", k -> new HashSet<>()).add("<*>");
		//this.transforms.add("net.minecraft.class_1234");
		/*transforms.computeIfAbsent("net.minecraft.client.MinecraftClient", k -> new HashSet<>()).add("<*>");
		transforms.computeIfAbsent("net.minecraft.entity.passive.SheepEntity", k -> new HashSet<>()).add("<*>");
		transforms.computeIfAbsent("net.minecraft.client.gui.ingame.CreativePlayerInventoryScreen$CreativeSlot", k -> new HashSet<>()).add("<*>");*/

		for (Entry<String, Set<String>> entry : transforms.entrySet()) {
			//System.out.println("Adding transformation " + entry.getKey() + " => " + entry.getValue());
			ClassTinkerers.addTransformation(entry.getKey(), makeAT(entry.getValue()));
		}

		Map<String, byte[]> classGenerators = new HashMap<>();
		Map<String, Set<Consumer<ClassNode>>> classModifiers = new HashMap<String, Set<Consumer<ClassNode>>>() {
			private static final long serialVersionUID = 4152702952480161028L;
			private boolean skipGen = false;
			private int massPool = 1;

			private void generate(String name, Collection<? extends String> targets) {
				//System.out.println("Generating " + mixinPackage + name + " with targets " + targets);
				classGenerators.put('/' + mixinPackage + name + ".class", makeMixinBlob(mixinPackage + name, targets));
				//ClassTinkerers.define(mixinPackage + name, makeMixinBlob(mixinPackage + name, targets)); ^^^
				mixins.add(name);
			}

			@Override
			public Set<Consumer<ClassNode>> put(String key, Set<Consumer<ClassNode>> value) {
				if (!skipGen) generate(key, Collections.singleton(key));
				return super.put(key, value);
			}

			@Override
			public void putAll(Map<? extends String, ? extends Set<Consumer<ClassNode>>> m) {
				skipGen = true;
				generate("MassExport_" + massPool++, m.keySet());
				super.putAll(m);
				skipGen = false;
			}
		};
		Set<EnumAdder> enumExtenders = new HashSet<EnumAdder>() {
			private static final long serialVersionUID = -2218861530200989346L;
			private boolean skipCheck = false;

			@Override
			public boolean add(EnumAdder builder) {
				if (!skipCheck) ClassTinkerers.addTransformation(builder.type, EnumExtender.makeEnumExtender(builder));
				return super.add(builder);
			}

			@Override
			public boolean addAll(Collection<? extends EnumAdder> builders) {
				skipCheck = true;
				for (EnumAdder builder : builders) {
					ClassTinkerers.addTransformation(builder.type, EnumExtender.makeEnumExtender(builder));
				}
				boolean out = super.addAll(builders);
				skipCheck = false;
				return out;
			}

			@Override
			public boolean remove(Object o) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean removeIf(Predicate<? super EnumAdder> filter) {
				throw new UnsupportedOperationException();
			}

			@Override
			public boolean removeAll(Collection<?> c) {
				throw new UnsupportedOperationException();
			}
		};
		ClassTinkerers.INSTANCE.hookUp(new UnremovableMap<>(classGenerators), new UnremovableMap<>(classModifiers), enumExtenders);

		addURL.accept(CasualStreamHandler.create(classGenerators));
		this.classModifiers = classModifiers;

		//System.out.println("Loaded initially with: " + classModifiers);
	}

	static byte[] makeMixinBlob(String name, Collection<? extends String> targets) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(52, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);

		AnnotationVisitor mixinAnnotation = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targetAnnotation = mixinAnnotation.visitArray("value");
		for (String target : targets) targetAnnotation.visit(null, Type.getType('L' + target + ';'));
		targetAnnotation.visitEnd();
		mixinAnnotation.visitEnd();

		MethodVisitor constructor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		constructor.visitCode();
		Label start = new Label();
		constructor.visitLabel(start);
		constructor.visitVarInsn(Opcodes.ALOAD, 0);
		constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		constructor.visitInsn(Opcodes.RETURN);
		Label end = new Label();
		constructor.visitLabel(end);
		constructor.visitLocalVariable("this", 'L' + name + ';', null, start, end, 0);
		constructor.visitMaxs(1, 1);
		constructor.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static Consumer<ClassNode> makeAT(Set<String> transforms) {
		return node -> {
			//System.out.println("ATing " + node.name + " with " + transforms);
			if (transforms.remove("<*>")) {
				node.access = flipBits(node.access);

				for (InnerClassNode innerClass : node.innerClasses) {
					if (node.name.equals(innerClass.name)) {
						innerClass.access = flipBits(innerClass.access);
						break;
					}
				}
			}

			if (!transforms.isEmpty()) {
				for (MethodNode method : node.methods) {
					if (transforms.remove(method.name + method.desc)) {
						method.access = flipBits(method.access);
						//Technically speaking we should probably do INVOKESPECIAL -> INVOKEVIRTUAL for private -> public transforms
						//But equally that's effort, so let's see how far we can get before it becomes an issue (from being lazy)
						if (transforms.isEmpty()) break;
					}
				}
			}
		};
	}

	private static final int ACCESSES = ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
	private static int flipBits(int access) {
		access &= ACCESSES;
		access |= Opcodes.ACC_PUBLIC;
		access &= ~Opcodes.ACC_FINAL;
		return access;
	}

	@Override
	public String getRefMapperConfig() {
		return null; //We can rely on the default
	}

	@Override
	public List<String> getMixins() {
		//System.out.println("Have " + mixins);
		//Entry points are only created once the game starts, which is way too late if we want to be transforming the game
		//FabricLoader.getInstance().getEntrypoints("mm:early_riser", Runnable.class).forEach(Runnable::run);
		for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
			if (mod.getMetadata().containsCustomElement("mm:early_risers")) {
				for (JsonElement riser : mod.getMetadata().getCustomElement("mm:early_risers").getAsJsonArray()) {
					try {
						Class.forName(riser.getAsString()).asSubclass(Runnable.class).newInstance().run();
					} catch (ReflectiveOperationException e) {
						throw new RuntimeException("Error loading early riser", e);
					}
				}
			}
		}
		//System.out.println("Now have " + mixins);
		return mixins;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		return true; //Sure
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		//System.out.println("Pre-applying " + targetClassName + " via " + mixinClassName);

		Set<Consumer<ClassNode>> transformations = classModifiers.get(targetClassName.replace('.', '/'));
		if (transformations != null) {
			for (Consumer<ClassNode> transformer : transformations) {
				transformer.accept(targetClass);
			}
		}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}