/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.api;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.commons.lang3.ArrayUtils;

import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

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

	private Predicate<URL> urlers = url -> false;
	private Map<String, byte[]> clazzes = new HashMap<>();
	private Map<String, Consumer<ClassNode>> replacers = new HashMap<>();
	private Map<String, Set<Consumer<ClassNode>>> tinkerers = new HashMap<>();
	private Set<EnumAdder> enumExtensions = new HashSet<>();
	public void hookUp(Consumer<URL> liveURL, Map<String, byte[]> liveClassMap, Map<String, Consumer<ClassNode>> liveReplacers, Map<String, Set<Consumer<ClassNode>>> liveTinkerers, Set<EnumAdder> liveEnums) {
		urlers = url -> {
			liveURL.accept(url);
			return true;
		};

		liveClassMap.putAll(clazzes);
		clazzes = liveClassMap;

		liveReplacers.putAll(replacers);
		replacers = liveReplacers;

		liveTinkerers.putAll(tinkerers);
		tinkerers = liveTinkerers;

		liveEnums.addAll(enumExtensions);
		enumExtensions = liveEnums;
	}

	/**
	 * Adds the given {@link URL} to the mod {@link URLClassLoader}'s list used to search for mod classes and resources
	 *
	 * <p>A {@code false} return value means this has been invoked too early in the loading process and the addition failed
	 * <p>If the given {@link URL} is {@code null} or is already in the list, this will have no effect
	 * but will still return {@link true} if it would have otherwise succeeded.
	 *
	 * @param url The URL to be added to the search path of URLs
	 * @return Whether the URL has been given to the classloader
	 *
	 * @since 1.6
	 */
	public static boolean addURL(URL url) {
		return INSTANCE.urlers.test(url);
	}

	/**
	 * Define a class with the given {@link name} by the given {@link contents} if it doesn't already exist
	 * <p><b>Behaviour is undefined if the target class name is already class loaded</b>
	 *
	 * @param name The name of the class to define
	 * @param contents The bytecode for the class
	 * @return Whether the definition was successful (ie another definition with the same name is not already present)
	 *
	 * @throws NullPointerException If name is {@code null}
	 * @throws IllegalArgumentException If contents is {@code null}
	 */
	public static boolean define(String name, byte[] contents) {
		name = '/' + name.replace('.', '/') + ".class";
		if (INSTANCE.clazzes.containsKey(name)) return false;

		if (contents == null) throw new IllegalArgumentException("Tried to define null class named " + name);
		INSTANCE.clazzes.put(name, contents);

		return true;
	}

	/**
	 * Add a class replacer for the given class {@link target} to allow totally replacing bytecode during definition.
	 * <p><b>Does nothing if the target class is already defined</b>
	 *
	 * <p>This method is designed when the bulk or entirety of the target class is going to be replaced by the
	 * {@code replacer}. For more modest changes {@link #addTransformation(String, Consumer)} is strongly recommended.
	 *
	 * <p>No Mixins or {@link #addTransformation(String, Consumer) transformations} will have applied when the
	 * {@code replacer} is given the {@link ClassNode}. Subsequently only one replacement for a given class can be registered,
	 * attempting to register more will result in an {@link IllegalStateException}.
	 *
	 * @param target The name of the class to be replaced
	 * @param transformer A {@link Consumer} to take the target class's unmodified {@link ClassNode} replace the contents
	 *
	 * @throws NullPointerException If target is {@code null}
	 * @throws IllegalArgumentException If replacer is {@code null}
	 * @throws IllegalStateException If replacement for the target has already been registered
	 *
	 * @since 1.9
	 */
	public static void addReplacement(String target, Consumer<ClassNode> replacer) {
		if (replacer == null) throw new IllegalArgumentException("Tried to set null replacer for " + target);
		String name = target.replace('.', '/');

		Consumer<ClassNode> existing = INSTANCE.replacers.get(name);
		if (existing != null) {
			throw new IllegalStateException("Multiple attempts to replace " + name + ": " + existing + " and " + replacer);
		}

		INSTANCE.replacers.put(name, replacer);
	}

	/**
	 * Add a class transformer for the given class {@link target} to allow modifying the bytecode during definition.
	 * <p><b>Does nothing if the target class is already defined</b>
	 *
	 * <p>This method is designed when certain elements of the target class are changed by the {@code transformer}.
	 * For more drastic changes {@link #addReplacement(String, Consumer)} might prove beneficial.
	 *
	 * <p>Any {@link #addReplacement(String, Consumer) replacement} will have applied before the {@code transformer}
	 * is given the {@link ClassNode}. Any number of other Mixins or transformations could have applied also so care
	 * should be taken that the target of the transformation is as expected.
	 *
	 * @param target The name of the class to be transformed
	 * @param transformer A {@link Consumer} to take the target class's {@link ClassNode} to be tinkered with
	 *
	 * @throws NullPointerException If target is {@code null}
	 * @throws IllegalArgumentException If transformer is {@code null}
	 */
	public static void addTransformation(String target, Consumer<ClassNode> transformer) {
		if (transformer == null) throw new IllegalArgumentException("Tried to add null transformer for " + target);
		INSTANCE.tinkerers.computeIfAbsent(target.replace('.', '/'), k -> new HashSet<>()).add(transformer);
	}

	/**
	 * Create a new {@link EnumAdder} in order to add additional Enum entries to the given type name.
	 * <p>Nothing will be done if the given Enum type has already been loaded.</p>
	 *
	 * @param type The name of the enum to be extended
	 * @return A builder for which additional entries can be defined
	 *
	 * @throws NullPointerException If type is {@code null}
	 */
	public static EnumAdder enumBuilder(String type) {
		if (type == null) throw new NullPointerException("Tried to add onto a null type!");

		return new EnumAdder(type.replace('.', '/'), ArrayUtils.EMPTY_CLASS_ARRAY);
	}

	/**
	 * Create a new {@link EnumAdder} in order to add additional Enum entries to the given type name.
	 * <p>Nothing will be done if the given Enum type has already been loaded.</p>
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
	 * <p>Nothing will be done if the given Enum type has already been loaded.</p>
	 *
	 * @param type The name of the enum to be extended
	 * @param parameterTypes The <b>internal names</b> of the parameter types the constructor to be used takes
	 * @return A builder for which additional entries can be defined
	 *
	 * @throws NullPointerException If type is {@code null}
	 * @throws IllegalArgumentException If parameterTypes is or contains {@code null}, or is invalidly specified
	 */
	public static EnumAdder enumBuilder(String type, String... parameterTypes) {
		if (type == null) throw new NullPointerException("Tried to add onto a null type!");
		if (parameterTypes == null || ArrayUtils.contains(parameterTypes, null))
			throw new IllegalArgumentException("Invalid parameter array: " + Arrays.toString(parameterTypes));

		return new EnumAdder(type.replace('.', '/'), Arrays.stream(parameterTypes).map(param -> param.replace('.', '/')).toArray(String[]::new));
	}

	/**
	 * Create a new {@link EnumAdder} in order to add additional Enum entries to the given type name
	 *
	 * <p>The given parameter types can be any mix of
	 * 	<ul>
	 * 		<li>{@link Class} - <b>Will crash if a Minecraft class to avoid early class loading</b>
	 * 		<li>{@link String} - Given as <b>internal names</b> (ie <code>Lmy/package/class;</code> or <code>I</code>)
	 * 		<li>{@link Type} - Any {@link Type#getSort() sorts} aside from {@link Type#METHOD} or {@link Type#VOID}
	 * 	</ul>
	 * 	So that it matches the constructor that is wanted to be used.</p>
	 *
	 * <p>Nothing will be done if the given Enum type has already been loaded.</p>
	 *
	 * @param type The name of the enum to be extended
	 * @param parameterTypes The type or internal names of the parameter types the constructor to be used takes
	 * @return A builder for which additional entries can be defined
	 *
	 * @throws NullPointerException If type is {@code null}
	 * @throws IllegalArgumentException If parameterTypes is or contains {@code null}, or is invalidly specified
	 */
	public static EnumAdder enumBuilder(String type, Object... parameterTypes) {
		if (type == null) throw new NullPointerException("Tried to add onto a null type!");
		if (parameterTypes == null || ArrayUtils.contains(parameterTypes, null))
			throw new IllegalArgumentException("Invalid parameter array: " + Arrays.toString(parameterTypes));

		return new EnumAdder(type.replace('.', '/'), parameterTypes);
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
	 * @throws NullPointerException If type is {@code null}
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