/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import org.objectweb.asm.Type;

/**
 * Simple builder to add additional Enum entries repetitively using the given constructor
 *
 * @author Chocohead
 */
public final class EnumAdder {
	/**
	 * Glorified Pair/Tuple implementation specifically for {@link EnumAdder}
	 *
	 * @author Chocohead
	 */
	public final class EnumAddition {
		/** The name of the new entry */
		public final String name;
		/** The class name of the inner class provider for the new entry
		 * @since 1.9
		 */
		public final String structClass;
		/** The factory which produces the parameters to construct the new entry with */
		private final Supplier<Object[]> parameterFactory;

		/**
		 * @param name The name of the new entry
		 * @param parameterFactory A factory which produces the parameters to construct the new entry with
		 *
		 * @since 2.0
		 */
		EnumAddition(String name, Supplier<Object[]> parameterFactory) {
			this(name, null, parameterFactory);
		}

		/**
		 * @param name The name of the new entry
		 * @param structClass The class name of the inner class provider for the new entry, maybe be {@code null}
		 * @param parameterFactory A factory which produces the parameters to construct the new entry with
		 *
		 * @since 1.9
		 */
		EnumAddition(String name, String structClass, Supplier<Object[]> parameterFactory) {
			this.name = name;
			this.structClass = structClass;
			this.parameterFactory = parameterFactory;
		}

		/**
		 * Uses {@link #parameterFactory} to produce the parameters to make the new entry
		 *
		 * @return The parameters as produced by the parameter factory
		 *
		 * @throws IllegalArgumentException If the factory produces an invalid parameter array
		 */
		public Object[] getParameters() {
			return checkParameters(parameterFactory.get());
		}

		/**
		 * Whether the new entry will be created as a subclass of the enum target type
		 *
		 * @return Whether the new entry has an associated inner class provider
		 *
		 * @since 1.9
		 */
		public boolean isEnumSubclass() {
			return structClass != null;
		}
	}

	/** The name of the enum being added to */
	public final String type;
	/** The parameter types of the constructor being used */
	public final Type[] parameterTypes;
	/** The collection of entries to be added, in order of being declared */
	private final List<EnumAddition> additions = new ArrayList<>();
	/** Whether {@link #build()} has been called */
	private boolean finished = false;

	/**
	 * New {@link EnumAdder}s can be made via {@link ClassTinkerers#enumBuilder(String, Class...)}
	 *
	 * @param type The name of the enum to be extended
	 * @param parameterTypes The type of parameters the constructor to be used takes
	 */
	EnumAdder(String type, Class<?>... parameterTypes) {
		this(type, Arrays.stream(parameterTypes).map(Type::getType).toArray(Type[]::new));
	}

	/**
	 * New {@link EnumAdder}s can be made via {@link ClassTinkerers#enumBuilder(String, String...)}
	 *
	 * @param type The name of the enum to be extended
	 * @param parameterTypes The names of the taken parameter types of the constructor being used
	 *
	 * @throws IllegalArgumentException If any of the given parameter type names are invalid
	 */
	EnumAdder(String type, String... parameterTypes) {
		this(type, Arrays.stream(parameterTypes).map(Type::getType).toArray(Type[]::new));
	}

	/**
	 * New {@link EnumAdder}s can be made via {@link ClassTinkerers#enumBuilder(String, Object...)}
	 *
	 * @param enumType The name of the enum to be extended
	 * @param parameterTypes The type or name of parameters the constructor to be used takes
	 *
	 * @throws IllegalArgumentException If any of the given parameter types are invalid
	 */
	EnumAdder(String enumType, Object... parameterTypes) {
		this(enumType, Arrays.stream(parameterTypes).map(type -> {
			if (type.getClass() == Type.class) {
				return (Type) type;
			} else if (type.getClass() == String.class) {
				return Type.getType(((String) type).replace('.', '/'));
			} else if (type.getClass() == Class.class) {
				if (((Class<?>) type).getName().startsWith("net.minecraft.")) {
					throw new IllegalArgumentException("Early loaded " + ((Class<?>) type).getName());
				}
				return Type.getType((Class<?>) type);
			} else {
				throw new IllegalArgumentException("Unsure how to map parameter type " + type.getClass() + " (from " + type + ')');
			}
		}).toArray(Type[]::new));
	}

	private EnumAdder(String type, Type[] parameterTypes) {
		this.type = type;
		this.parameterTypes = parameterTypes;
	}

	/**
	 * Add an entry to the Enum with the given name, constructed using the given parameters
	 *
	 * @param name The name of the new entry, undetermined result if the name already exists
	 * @param parameters The parameters to construct the new entry with
	 * @return This object to chain with
	 *
	 * @throws NullPointerException If name or parameters are {@code null}
	 * @throws IllegalArgumentException If the length of parameters differs to that of {@link #parameterTypes}
	 * @throws IllegalStateException If {@link #build()} has already been called on this object
	 */
	public EnumAdder addEnum(String name, Object... parameters) {
		if (finished) throw new IllegalStateException("Attempted to add onto a built EnumAdder");

		if (name == null) throw new NullPointerException("Null name attempted to be added to " + type);
		checkParameters(parameters);

		additions.add(new EnumAddition(name, () -> parameters));

		return this;
	}

	/**
	 * Add an entry to the Enum with the given name, constructed using the parameters supplied using the given factory
	 *
	 * @param name The name of the new entry, undetermined result if the name already exists
	 * @param parameterFactory The factory to produce the parameters to construct the new entry with
	 * @return This object to chain with
	 *
	 * @throws NullPointerException If name or parameterFactory are {@code null}
	 * @throws IllegalStateException If {@link #build()} has already been called on this object
	 */
	public EnumAdder addEnum(String name, Supplier<Object[]> parameterFactory) {
		if (finished) throw new IllegalStateException("Attempted to add onto a built EnumAdder");

		if (name == null) throw new NullPointerException("Null name attempted to be added to " + type);
		if (parameterFactory == null)
			throw new NullPointerException("Null parameter factory provided to make " + name + " of " + type);

		additions.add(new EnumAddition(name, parameterFactory));

		return this;
	}

	/**
	 * Add an entry to the Enum with the given name, constructed using the given parameters, as an enum subclass
	 *
	 * @param name The name of the new entry, undetermined result if the name already exists
	 * @param structClass The class which the subclass will be built from
	 * @param parameters The parameters to construct the new entry with
	 * @return This object to chain with
	 *
	 * @throws NullPointerException If name or parameters are {@code null}
	 * @throws IllegalArgumentException If the length of parameters differs to that of {@link #parameterTypes} or structClass is {@code null}
	 * @throws IllegalStateException If {@link #build()} has already been called on this object
	 *
	 * @since 1.9
	 */
	public EnumAdder addEnumSubclass(String name, String structClass, Object... parameters) {
		if (finished) throw new IllegalStateException("Attempted to add onto a built EnumAdder");

		if (name == null) throw new NullPointerException("Null name attempted to be added to " + type);
		if (structClass == null) throw new IllegalArgumentException("Null structClass provided to make " + name + " of " + type);
		checkParameters(parameters);

		additions.add(new EnumAddition(name, structClass.replace('.', '/'), () -> parameters));

		return this;
	}

	/**
	 * Add an entry to the Enum with the given name, constructed using the parameters supplied using the
	 * given factory, as an enum subclass
	 *
	 * @param name The name of the new entry, undetermined result if the name already exists
	 * @param structClass The class which the subclass will be built from
	 * @param parameterFactory The factory to produce the parameters to construct the new entry with
	 * @return This object to chain with
	 *
	 * @throws NullPointerException If name or parameterFactory are {@code null}
	 * @throws IllegalArgumentException If structClass is {@code null}
	 * @throws IllegalStateException If {@link #build()} has already been called on this object
	 *
	 * @since 1.9
	 */
	public EnumAdder addEnumSubclass(String name, String structClass, Supplier<Object[]> parameterFactory) {
		if (finished) throw new IllegalStateException("Attempted to add onto a built EnumAdder");

		if (name == null) throw new NullPointerException("Null name attempted to be added to " + type);
		if (structClass == null) throw new IllegalArgumentException("Null structClass provided to make " + name + " of " + type);
		if (parameterFactory == null)
			throw new NullPointerException("Null parameter factory provided to make " + name + " of " + type);

		additions.add(new EnumAddition(name, structClass.replace('.', '/'), parameterFactory));

		return this;
	}

	/**
	 * Checks that the provided parameters array has the same length as {@link #parameterTypes}
	 *
	 * @param parameters The parameters used to construct the Enum entry
	 *
	 * @throws NullPointerException If parameters is {@code null}
	 * @throws IllegalArgumentException If the length of parameters is different to parameterTypes
	 */
	Object[] checkParameters(Object[] parameters) {
		if (parameters.length != parameterTypes.length)
			throw new IllegalArgumentException("Differing number of parameters provided for types expected");

		return parameters;
	}

	/**
	 * Gets if there are any additional constructor parameters beyond the minimum of a name
	 *
	 * @return Whether {@link #parameterTypes} is non-empty
	 *
	 * @since 1.8
	 */
	public boolean hasParameters() {
		return parameterTypes.length > 0;
	}

	/**
	 * Mark this as complete, registers the changes to actually be applied during class load
	 */
	public void build() {
		ClassTinkerers.addEnum(this);
		finished = true;
	}

	/**
	 * Get a read only view of the additions made via {@link #addEnum(String, Object...)}
	 *
	 * @return A read only view of {@link #additions}
	 */
	public Collection<EnumAddition> getAdditions() {
		return Collections.unmodifiableCollection(additions);
	}

	/**
	 * Get whether any of the additions made will subclass the Enum
	 *
	 * @return If any of the additions return {@code true} for {@link EnumAddition#isEnumSubclass()}
	 *
	 * @since 1.9
	 */
	public boolean willSubclass() {
		for (EnumAddition addition : additions) {
			if (addition.isEnumSubclass()) {
				return true;
			}
		}

		return false;
	}
}