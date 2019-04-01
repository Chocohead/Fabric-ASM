/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.api;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.lang3.ArrayUtils;
import org.spongepowered.asm.lib.tree.ClassNode;

/**
 * A collection of helper methods to allow adding and changing the definition of classes.
 *
 * <p>Class transformations via {@link #addTransformation(String, Consumer)} and additional enum entries via
 * {@link #enumBuilder(String, Class...)} should be done via an Early Riser.
 * Additional class definitions via {@link #define(String, byte[])} can be done at any time.
 *
 * <p><b>Non-static methods are to be treated as non-API and thus should be left alone.</b>
 *
 * @author Chocohead
 */
public enum ClassTinkerers {
	INSTANCE;

	private Map<String, byte[]> clazzes = new HashMap<>();
	private Map<String, Set<Consumer<ClassNode>>> tinkerers = new HashMap<>();
	private Set<EnumAdder> enumExtensions = new HashSet<>();
	public void hookUp(Map<String, byte[]> liveClassMap, Map<String, Set<Consumer<ClassNode>>> liveTinkerers, Set<EnumAdder> liveEnums) {
		liveClassMap.putAll(clazzes);
		clazzes = liveClassMap;

		liveTinkerers.putAll(tinkerers);
		tinkerers = liveTinkerers;

		liveEnums.addAll(enumExtensions);
		enumExtensions = liveEnums;
	}

	/**
	 * Define a class with the given {@link name} by the given {@link contents} if it doesn't already exist
	 * <p><b>Behaviour is undefined if the target class name is already class loaded</b>
	 *
	 * @param name The name of the class to define
	 * @param contents The bytecode for the class
	 * @return Whether the definition was successful (ie another definition with the same name is not already present)
	 */
	public static boolean define(String name, byte[] contents) {
		name = '/' + name.replace('.', '/') + ".class";
		if (INSTANCE.clazzes.containsKey(name)) return false;

		INSTANCE.clazzes.put(name, contents);
		return true;
	}

	/**
	 * Add a class transformer for the given class {@link target} to allow modifying the bytecode during definition.
	 * <p><b>Does nothing if the target class is already defined</b>
	 *
	 * @param target The name of the class to be transformed
	 * @param transformer A {@link Consumer} to take the target class's {@link ClassNode} to be tinkered with
	 */
	public static void addTransformation(String target, Consumer<ClassNode> transformer) {
		INSTANCE.tinkerers.computeIfAbsent(target.replace('.', '/'), k -> new HashSet<>()).add(transformer);
	}

	/**
	 * Create a new {@link EnumAdder} in order to add additional Enum entries to the given type name.
	 * <p>Nothing will be done if the given Enum type has already been loaded.<p>
	 * <p><b>Will crash if any of the parameter types are from Minecraft to avoid early class loading</b>
	 *
	 * @param type The name of the enum to be extended
	 * @param parameterTypes The type of parameters the constructor to be used takes
	 * @return A builder for which additional entries can be defined
	 *
	 * @throws NullPointerException If type is null
	 * @throws IllegalArgumentException If parameterTypes is or contains null, or is a Minecraft class
	 */
	public static EnumAdder enumBuilder(String type, Class<?>... parameterTypes) {
		if (type == null) throw new NullPointerException("Tried to add onto a null type!");
		if (parameterTypes == null || ArrayUtils.contains(parameterTypes, null))
			throw new IllegalArgumentException("Invalid parameter array: " + Arrays.toString(parameterTypes));

		for (Class<?> param : parameterTypes) {
			if (param.getName().startsWith("net.minecraft.")) {
				throw new IllegalArgumentException("Early loaded " + param.getName());
			}
		}

		return new EnumAdder(type.replace('.', '/'), parameterTypes);
	}

	/**
	 * Create a new {@link EnumAdder} in order to add additional Enum entries to the given type name.
	 * <p>Nothing will be done if the given Enum type has already been loaded.<p>
	 *
	 * @param type The name of the enum to be extended
	 * @param parameterTypes The <b>internal names</b> of the parameter types the constructor to be used takes
	 * @return A builder for which additional entries can be defined
	 *
	 * @throws NullPointerException If type is null
	 * @throws IllegalArgumentException If parameterTypes is or contains null, or is invalidly specified
	 */
	public static EnumAdder enumBuilder(String type, String... parameterTypes) {
		if (type == null) throw new NullPointerException("Tried to add onto a null type!");
		if (parameterTypes == null || ArrayUtils.contains(parameterTypes, null))
			throw new IllegalArgumentException("Invalid parameter array: " + Arrays.toString(parameterTypes));

		return new EnumAdder(type.replace('.', '/'), Arrays.stream(parameterTypes).map(param -> param.replace('.', '/')).toArray(String[]::new));
	}

	/**
	 * Register the given {@link EnumAdder} as finished and ready to be used
	 *
	 * @param builder The finished EnumAdder to store
	 */
	static void addEnum(EnumAdder builder) {
		if (ArrayUtils.contains(builder.parameterTypes, null)) //Individual array entries could be swapped out naughtily, guard against it
			throw new IllegalArgumentException("Builder for " + builder.type + " has an invalid parameter array: " + Arrays.toString(builder.parameterTypes));

		//Only bother adding it if changes are actually made
		if (!builder.getAdditions().isEmpty()) INSTANCE.enumExtensions.add(builder);
	}

	/**
	 * Gets the Enum entry with the given name from the given enum type
	 *
	 * @param type The type of Enum for which to search in
	 * @param name The name of the entry to return
	 * @return The entry within type that has {@link Enum#name()} equal to name
	 *
	 * @throws NullPointerException If type is null
	 * @throws IllegalArgumentException If no entry with the given name can be found in type
	 */
	public static <E extends Enum<E>> E getEnum(Class<E> type, String name) {
		for (E constant : type.getEnumConstants()) {
			if (constant.name().equals(name)) {
				return constant;
			}
		}

		throw new IllegalArgumentException("Unable to find " + name + " in " + type);
	}
}