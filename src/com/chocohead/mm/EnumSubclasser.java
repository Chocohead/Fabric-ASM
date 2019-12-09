package com.chocohead.mm;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.common.collect.Iterables;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.lib.AnnotationVisitor;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.ClassWriter;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.commons.GeneratorAdapter;
import org.spongepowered.asm.lib.commons.Method;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.util.Annotations;

import com.chocohead.mm.api.EnumAdder.EnumAddition;

class EnumSubclasser {
	private static final Map<EnumAddition, ClassNode> ADDITION_TO_CHANGES = new IdentityHashMap<>();
	private static final Map<String, ClassNode> STRUCTS_TO_CLASS = new HashMap<>();
	private static final Set<String> STRUCT_MIXINS = new HashSet<>();

	static byte[] defineAnonymousSubclass(ClassNode enumNode, EnumAddition addition, String anonymousClassName, String constructor) {
		ClassWriter writer = new ClassWriter(0);

		Type enumType = Type.getObjectType(enumNode.name);
		Type thisType = Type.getObjectType(anonymousClassName);
		Type structType = Type.getObjectType(addition.structClass);

		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_ENUM, anonymousClassName, null, enumNode.name, null);
		writer.visitOuterClass(enumType.getInternalName(), null, null);

		writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "struct", structType.getDescriptor(), null, null).visitEnd();

		Method method = new Method("<init>", constructor);
		GeneratorAdapter generator = new GeneratorAdapter(Opcodes.ACC_PUBLIC, method, null, null, writer);
		generator.loadThis();
		generator.loadArgs();
		generator.invokeConstructor(enumType, method);
		generator.loadThis();
		generator.newInstance(structType);
		generator.dup();
		generator.invokeConstructor(structType, new Method("<init>", "()V"));
		generator.putStatic(thisType, "struct", structType);
		generator.returnValue();
		generator.endMethod();

		ClassNode struct = loadStruct(anonymousClassName, addition);
		assert struct.name.equals(structType.getInternalName());

		Map<String, String> gains = new HashMap<>();
		Map<String, MethodNode> toMatch = enumNode.methods.stream().collect(Collectors.toMap(m -> m.name + m.desc, Function.identity()));
		Map<String, MethodNode> overrides = Stream.concat(Stream.of(struct), getParentStructs(struct.superName).stream()).flatMap(node -> node.methods.stream()).peek(m -> {
			AnnotationNode annotation = Annotations.getInvisible(m, CorrectedMethod.class);

			List<AnnotationNode> corrections;
			if (annotation != null) {
				corrections = Collections.singletonList(annotation);
			} else {
				AnnotationNode annotations = Annotations.getInvisible(m, CorrectedMethods.class);
				corrections = annotations != null ? Annotations.getValue(annotation) : Collections.emptyList();
			}

			for (AnnotationNode correction : corrections) {
				String from = Annotations.getValue(correction, "from");
				String to = Annotations.getValue(correction, "to");

				String previous = gains.put(from, to);
				assert previous == null || previous.equals(to);
			}
		}).filter(m -> m.name.charAt(0) != '<' && !Modifier.isPrivate(m.access) && !Modifier.isStatic(m.access)).map(m -> m.name + m.desc).filter(toMatch::containsKey)
				.collect(Collectors.toMap(Function.identity(), toMatch::get));

		for (Entry<String, MethodNode> entry : overrides.entrySet()) {
			assert !Modifier.isFinal(entry.getValue().access); //We shouldn't override a final method really

			method = makeMethod(entry.getKey());
			//Strictly speaking the JVM doesn't especially care about checked exceptions or signatures, but because we can...
			generator = new GeneratorAdapter(Opcodes.ACC_PUBLIC, method, entry.getValue().signature,
					entry.getValue().exceptions.stream().map(Type::getObjectType).toArray(Type[]::new), writer);
			generator.getStatic(thisType, "struct", structType);
			generator.loadArgs();
			generator.invokeVirtual(structType, method);
			generator.returnValue();
			generator.endMethod();
		}

		for (String to : gains.values()) {
			assert !toMatch.containsKey(to); //Surely Mojang wouldn't have a method using our wonderful names

			method = makeMethod(to);
			generator = new GeneratorAdapter(Opcodes.ACC_PUBLIC, method, null, null, writer);
			generator.loadThis();
			generator.loadArgs();
			generator.invokeConstructor(enumType, method);
			generator.getStatic(thisType, "struct", structType);
			generator.loadArgs();
			generator.invokeVirtual(structType, method);
			generator.returnValue();
			generator.endMethod();
		}

		writer.visitEnd();

		return writer.toByteArray();
	}

	private static Method makeMethod(String nameDesc) {
		int split = nameDesc.indexOf('(');
		return new Method(nameDesc.substring(0, split), nameDesc.substring(split));
	}

	private static synchronized ClassNode loadStruct(String newOwner, EnumAddition addition) {
		ClassNode node = ADDITION_TO_CHANGES.get(addition);
		if (node != null) return node;

		node = STRUCTS_TO_CLASS.get(addition.structClass);
		if (node != null) {
			ADDITION_TO_CHANGES.put(addition, node);
			return node;
		}

		try (InputStream in = EnumSubclasser.class.getResourceAsStream(addition.structClass + ".class")) {
			assert in != null: "Unable to find provided struct class " + addition.structClass;
			new ClassReader(in).accept(node = new ClassNode(), ClassReader.SKIP_FRAMES); //Recalculating frames is slow, but we are changing a lot of things
		} catch (IOException e) {
			throw new RuntimeException("Unable to find provided struct class " + addition.structClass, e);
		}

		List<ClassNode> parents = getParentStructs(node.superName);

		String mixin; {
			ClassNode deepestParent = Iterables.getLast(parents, node);
			mixin = deepestParent.superName;

			//Make sure to remove the Mixin class from the deepest parent's hierarchy
			deepestParent.superName = Object.class.getName().replace('.', '/');
		}

		//Stream.concat(Stream.of(node), parents.stream()).forEach(struct -> {
		for (ClassNode struct : Iterables.concat(Collections.singletonList(node), parents)) {
			for (MethodNode method : struct.methods) {
				Map<String, String> replacements = new HashMap<>();

				for (ListIterator<AbstractInsnNode> it = method.instructions.iterator(); it.hasNext();) {
					AbstractInsnNode insn = it.next();

					if (insn.getType() == AbstractInsnNode.METHOD_INSN && mixin.equals(((MethodInsnNode) insn).owner)) {
						if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {//super call, needs special handling
							MethodInsnNode mInsn = (MethodInsnNode) insn;

							String newName = "MMsuper£" + mInsn.name;
							replacements.put(mInsn.name + mInsn.desc, newName + mInsn.desc);

							mInsn.owner = newOwner;
							mInsn.name = newName;
							mInsn.setOpcode(Opcodes.INVOKEVIRTUAL);
						} else {
							((MethodInsnNode) insn).owner = newOwner;
						}
					}
				}

				switch (replacements.size()) {
				case 0:
					break;

				case 1: {
					Entry<String, String> entry = Iterables.getOnlyElement(replacements.entrySet());
					Annotations.setInvisible(method, CorrectedMethod.class, "from", entry.getKey(), "to", entry.getValue());
					break;
				}

				default: {
					AnnotationNode annotation = new AnnotationNode(Type.getDescriptor(CorrectedMethods.class));
					AnnotationVisitor annotations = annotation.visitArray("value");

					String correctedMethod = Type.getDescriptor(CorrectedMethod.class);
					for (Entry<String, String> entry : replacements.entrySet()) {
						AnnotationVisitor nest = annotations.visitAnnotation(null, correctedMethod);
						nest.visit("from", entry.getKey());
						nest.visit("to", entry.getValue());
						nest.visitEnd();
					}

					annotations.visitEnd();
					method.invisibleAnnotations.add(annotation);
					break;
				}
				}
			}
		}//);

		return node;
	}

	static List<ClassNode> getParentStructs(String name) {
		List<ClassNode> parents = new ArrayList<>();

		String child = name;
		ClassNode parent;
		while ((parent = loadSuperStruct(child)) != null) {
			parents.add(parent);

			child = parent.superName;
			if (child == null || child.startsWith("java/lang/")) {
				throw new IllegalStateException("Missing Mixin from struct hierachy " + name + parents.stream().map(node -> node.name).collect(Collectors.joining(" => ", " => ", "")));
			}
		}

		return parents;
	}

	private static synchronized ClassNode loadSuperStruct(String name) {
		ClassNode node = STRUCTS_TO_CLASS.get(name);
		if (node != null) return node;

		//If we recognise this as a Mixin there's no need to load it
		if (STRUCT_MIXINS.contains(name)) return null;

		//Maybe one of the existing enum additions already uses what we want
		for (Entry<EnumAddition, ClassNode> entry : ADDITION_TO_CHANGES.entrySet()) {
			if (entry.getKey().structClass.equals(name)) {
				return entry.getValue();
			}
		}

		try (InputStream in = EnumSubclasser.class.getResourceAsStream('/' + name + ".class")) {
			assert in != null: "Unable to find provided struct class " + name;
			new ClassReader(in).accept(node = new ClassNode(), ClassReader.SKIP_FRAMES);
		} catch (IOException e) {
			throw new RuntimeException("Unable to find provided struct class " + name, e);
		}

		if (Annotations.getInvisible(node, Mixin.class) != null) {
			//We've found the defining Mixin, nothing more to look for
			STRUCT_MIXINS.add(name);
			return null;
		} else {
			STRUCTS_TO_CLASS.put(name, node);
			return node;
		}
	}

	static Consumer<ClassNode> makeStructFixer(EnumAddition addition, String target) {
		return node -> {
			assert node.name.equals(addition.structClass);

			ClassNode replacement;
			synchronized (EnumSubclasser.class) {
				replacement = ADDITION_TO_CHANGES.get(addition);

				if (node == null) {
					try {//Don't recognise the addition, force class load the enum
						Class.forName(target.replace('/', '.'));
					} catch (ClassNotFoundException e) {
						throw new IllegalStateException("Unable to load target enum " + target + " for " + addition.name + " => " + addition.structClass);
					}

					replacement = ADDITION_TO_CHANGES.get(addition);
					if (replacement == null) throw new IllegalStateException("Unable to find " + target + " for " + addition.name + " => " + addition.structClass);
				}
			}
			assert replacement != null;

			applyStructFixes(node, replacement);
		};
	}

	static Consumer<ClassNode> makeStructFixer(String parent, String target) {
		return node -> {
			ClassNode replacement;
			synchronized (EnumSubclasser.class) {
				replacement = STRUCTS_TO_CLASS.get(parent);

				if (node == null) {
					try {//Don't recognise the addition, force class load the enum
						Class.forName(target.replace('/', '.'));
					} catch (ClassNotFoundException e) {
						throw new IllegalStateException("Unable to load target enum " + target + " for super struct " + parent);
					}

					replacement = STRUCTS_TO_CLASS.get(parent);
					if (replacement == null) throw new IllegalStateException("Unable to find " + target + " for super struct " + parent);
				}
			}
			assert replacement != null;

			applyStructFixes(node, replacement);
		};
	}

	private static void applyStructFixes(ClassNode target, ClassNode fixes) {
		target.superName = fixes.superName;
		target.methods = fixes.methods;
	}
}