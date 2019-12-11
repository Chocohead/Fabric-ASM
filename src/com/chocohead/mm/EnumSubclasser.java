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

import com.google.common.collect.Iterables;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.lib.AnnotationVisitor;
import org.spongepowered.asm.lib.ClassReader;
import org.spongepowered.asm.lib.ClassVisitor;
import org.spongepowered.asm.lib.ClassWriter;
import org.spongepowered.asm.lib.MethodVisitor;
import org.spongepowered.asm.lib.Type;
import org.spongepowered.asm.lib.commons.GeneratorAdapter;
import org.spongepowered.asm.lib.commons.Method;
import org.spongepowered.asm.lib.tree.AbstractInsnNode;
import org.spongepowered.asm.lib.tree.AnnotationNode;
import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.lib.tree.FieldInsnNode;
import org.spongepowered.asm.lib.tree.MethodInsnNode;
import org.spongepowered.asm.lib.tree.MethodNode;
import org.spongepowered.asm.lib.tree.TypeInsnNode;
import org.spongepowered.asm.lib.tree.VarInsnNode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.util.Annotations;

import com.chocohead.mm.api.EnumAdder.EnumAddition;

class EnumSubclasser {
	private static class StructClassVisitor extends ClassVisitor {
		private static final String MIXIN = Type.getDescriptor(Mixin.class);
		private final List<MethodNode> methods = new ArrayList<>();
		private String name, parent;
		private boolean isMixin, hasRead;

		public StructClassVisitor() {
			super(Opcodes.ASM7);
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			this.name = name;
			parent = superName;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			if (!visible && !isMixin) {
				isMixin = MIXIN.equals(descriptor);
			}

			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			MethodNode method = new MethodNode(access, name, descriptor, signature, exceptions);
			methods.add(method);
			return method;
		}

		@Override
		public void visitEnd() {
			hasRead = true;
		}

		public boolean isMixin() {
			if (!hasRead) throw new IllegalStateException("Haven't visited class");
			return isMixin;
		}

		public StructClass asStruct() {
			if (!hasRead) throw new IllegalStateException("Haven't visited class");
			if (isMixin) throw new IllegalArgumentException("Tried to turn Mixin into a struct");
			return new StructClass(name, parent, methods);
		}
	}
	static class StructClass {
		private boolean isFixed;
		public final String name;
		private String parent;
		public final List<MethodNode> methods;

		StructClass(String name, String parent, List<MethodNode> methods) {
			this.name = name;
			this.parent = parent;
			this.methods = methods;
		}

		public StructClass(ClassNode node) {
			name = node.name;
			parent = node.superName;
			methods = node.methods;
		}

		boolean isFixed() {
			return isFixed;
		}

		void setFixed() {
			isFixed = true;
		}

		public String getParent() {
			return parent;
		}

		String switchParent(String name) {
			String old = parent;
			parent = name;
			return old;
		}
	}
	private static final Map<EnumAddition, StructClass> ADDITION_TO_CHANGES = new IdentityHashMap<>();
	private static final Map<String, StructClass> STRUCTS_TO_CLASS = new HashMap<>();
	private static final Set<String> STRUCT_MIXINS = new HashSet<>();

	static byte[] defineAnonymousSubclass(ClassNode enumNode, EnumAddition addition, String anonymousClassName, String constructor) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES);

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
		generator.putField(thisType, "struct", structType);
		generator.returnValue();
		generator.endMethod();

		StructClass struct = loadStruct(enumType.getDescriptor(), anonymousClassName, addition);
		assert struct.name.equals(structType.getInternalName());

		Map<String, String> gains = new HashMap<>();
		Map<String, MethodNode> toMatch = enumNode.methods.stream().collect(Collectors.toMap(m -> m.name + m.desc, Function.identity()));
		Map<String, MethodNode> overrides = getParentStructs(struct).stream().flatMap(node -> node.methods.stream()).peek(m -> {
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
			generator.loadThis();
			generator.getField(thisType, "struct", structType);
			generator.loadArgs();
			generator.invokeVirtual(structType, method);
			generator.returnValue();
			generator.endMethod();
		}

		for (Entry<String, String> entry : gains.entrySet()) {
			String to = entry.getValue();
			assert !toMatch.containsKey(to); //Surely Mojang wouldn't have a method using our wonderful names

			method = makeMethod(to);
			generator = new GeneratorAdapter(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE, method, null, null, writer);
			generator.loadThis();
			generator.loadArgs();
			generator.invokeConstructor(enumType, makeMethod(entry.getKey()));
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

	private static synchronized StructClass loadStruct(String enumDescriptor, String newOwner, EnumAddition addition) {
		StructClass node = ADDITION_TO_CHANGES.get(addition);
		if (node != null && node.isFixed()) return node;

		node = STRUCTS_TO_CLASS.get(addition.structClass);
		if (node != null && node.isFixed()) {
			ADDITION_TO_CHANGES.put(addition, node);
			return node;
		}

		if (node == null) {
			StructClassVisitor visitor;
			try (InputStream in = EnumSubclasser.class.getResourceAsStream(addition.structClass + ".class")) {
				assert in != null: "Unable to find provided struct class " + addition.structClass;
				new ClassReader(in).accept(visitor = new StructClassVisitor(), ClassReader.SKIP_FRAMES); //Recalculating frames is slow, but we are changing a lot of things
			} catch (IOException e) {
				throw new RuntimeException("Unable to find provided struct class " + addition.structClass, e);
			}

			assert !visitor.isMixin();
			node = visitor.asStruct();
		}

		List<StructClass> parents = getParentStructs(node);
		assert !parents.isEmpty() && parents.get(0) == node;

		//Make sure to remove the Mixin class from the deepest parent's hierarchy
		String mixin = Iterables.getLast(parents).switchParent(Object.class.getName().replace('.', '/'));

		//Stream.concat(Stream.of(node), parents.stream()).forEach(struct -> {
		for (StructClass struct : parents) {
			for (MethodNode method : struct.methods) {
				Map<String, String> replacements = new HashMap<>();

				for (ListIterator<AbstractInsnNode> it = method.instructions.iterator(); it.hasNext();) {
					AbstractInsnNode insn = it.next();

					if (insn.getType() == AbstractInsnNode.METHOD_INSN && mixin.equals(((MethodInsnNode) insn).owner)) {
						assert !struct.isFixed();

						MethodInsnNode mInsn = (MethodInsnNode) insn;
						boolean easySwap = Type.getArgumentsAndReturnSizes(mInsn.desc) >> 2 <= 1; //Implicit this gives 1 for ()V

						if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {//super call, needs special handling
							if ("<init>".equals(mInsn.name)) {//Make sure not to screw up the class's constructor
								mInsn.owner = struct.getParent();
								continue; //Don't need the stack replacement
							} else {
								String newName = "MMsuper£" + mInsn.name;
								replacements.put(mInsn.name + mInsn.desc, newName + mInsn.desc);

								mInsn.owner = easySwap ? newOwner : struct.name;
								mInsn.name = newName;
								if (easySwap) mInsn.setOpcode(Opcodes.INVOKEVIRTUAL);
							}
						} else {
							mInsn.owner = easySwap ? newOwner : struct.name;
							if (easySwap) mInsn.setOpcode(Opcodes.INVOKESPECIAL);
						}

						if (easySwap) {//No arguments to get caught up in, previous instruction should be aload_0
							AbstractInsnNode previous = insn.getPrevious();

							if (previous.getType() != AbstractInsnNode.VAR_INSN || ((VarInsnNode) previous).var != 0) {
								//Well it's not aload_0, don't know what to do now :|
								throw new IllegalStateException("Not quite sure how to handle the bytecode, previous was " + previous.getType());
							}

							method.instructions.insert(previous, new TypeInsnNode(Opcodes.CHECKCAST, newOwner));
							method.instructions.set(previous, new FieldInsnNode(Opcodes.GETSTATIC, newOwner, addition.name, enumDescriptor));
						} else {//Any number of arguments to trip up with, easier to make a bridge
							throw new UnsupportedOperationException("Calling through " + mInsn.owner + '/' + mInsn.name + mInsn.desc);
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

			struct.setFixed();
		}//);

		ADDITION_TO_CHANGES.put(addition, node);
		return node;
	}

	static List<StructClass> getParentStructs(String name) {
		StructClass start = loadSuperStruct(name);
		if (start == null) throw new IllegalArgumentException("Cannot get parents of Mixins");

		List<StructClass> parents = getParentStructs(start);
		return parents.subList(1, parents.size());
	}

	private static List<StructClass> getParentStructs(StructClass start) {
		List<StructClass> parents = new ArrayList<>();

		StructClass child = start;
		String parent;
		do {
			parents.add(child);
			parent = child.getParent();

			if (parent == null || parent.startsWith("java/lang/")) {
				if (child.isFixed()) {
					return parents; //Reached the ex-Mixin extending class
				} else {
					throw new IllegalStateException(parents.stream().map(node -> node.name)
							.collect(Collectors.joining("Missing Mixin from struct hierachy ", " => ", " => " + parent)));
				}
			}
		} while ((child = loadSuperStruct(parent)) != null);

		return parents;
	}

	private static synchronized StructClass loadSuperStruct(String name) {
		StructClass struct = STRUCTS_TO_CLASS.get(name);
		if (struct != null) return struct;

		//If we recognise this as a Mixin there's no need to load it
		if (STRUCT_MIXINS.contains(name)) return null;

		//Maybe one of the existing enum additions already uses what we want
		for (Entry<EnumAddition, StructClass> entry : ADDITION_TO_CHANGES.entrySet()) {
			if (entry.getKey().structClass.equals(name)) {
				return entry.getValue();
			}
		}

		StructClassVisitor node;
		try (InputStream in = EnumSubclasser.class.getResourceAsStream('/' + name + ".class")) {
			assert in != null: "Unable to find provided struct class " + name;
			new ClassReader(in).accept(node = new StructClassVisitor(), ClassReader.SKIP_FRAMES);
		} catch (IOException e) {
			throw new RuntimeException("Unable to find provided struct class " + name, e);
		}

		if (node.isMixin()) {
			//We've found the defining Mixin, nothing more to look for
			STRUCT_MIXINS.add(name);
			return null;
		} else {
			struct = node.asStruct();
			STRUCTS_TO_CLASS.put(name, struct);
			return struct;
		}
	}

	static Consumer<ClassNode> makeStructFixer(EnumAddition addition, String target) {
		return node -> {
			assert node.name.equals(addition.structClass);

			StructClass replacement;
			synchronized (EnumSubclasser.class) {
				replacement = ADDITION_TO_CHANGES.get(addition);

				if (replacement == null) {
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
			StructClass replacement;
			synchronized (EnumSubclasser.class) {
				replacement = STRUCTS_TO_CLASS.get(parent);

				if (replacement == null) {
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

	private static void applyStructFixes(ClassNode target, StructClass fixes) {
		assert fixes.isFixed();
		target.superName = fixes.getParent();
		target.methods = fixes.methods;
	}
}