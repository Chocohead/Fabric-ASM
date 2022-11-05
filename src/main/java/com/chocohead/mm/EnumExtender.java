/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import com.chocohead.mm.api.ClassTinkerers;
import com.chocohead.mm.api.EnumAdder;
import com.chocohead.mm.api.EnumAdder.EnumAddition;

public final class EnumExtender {
	public static final Map<String, Object[]> POOL = new HashMap<>();


	static Consumer<ClassNode> makeEnumExtender(EnumAdder builder) {
		return node -> {
			//System.out.println("Extending " + node.name);
			if ((node.access & Opcodes.ACC_ENUM) != Opcodes.ACC_ENUM)
				throw new IllegalStateException("Tried to add enum entries to a non-enum type " + node.name);

			String valuesField = null;
			out: for (MethodNode method : node.methods) {
				if ("values".equals(method.name) && ("()[L" + node.name + ';').equals(method.desc)) {
					for (Iterator<AbstractInsnNode> it = method.instructions.iterator(); it.hasNext();) {
						AbstractInsnNode insn = it.next();

						if (insn.getType() == AbstractInsnNode.FIELD_INSN) {
							valuesField = ((FieldInsnNode) insn).name;
							break out;
						}
					}

					throw new IllegalStateException("Unable to find values field in " + node.name + '#' + method.name + method.desc);
				}
			}
			if (valuesField == null) throw new IllegalStateException("Unable to find " + node.name + "#values()[L" + node.name + ';');

			MethodNode clinit = null;
			for (MethodNode method : node.methods) {
				if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
					clinit = method;
					break;
				}
			} //Even empty enums have values and valueOf, which by extension means they have a static block to make the (empty) $VALUES field
			if (clinit == null) throw new IllegalStateException("Unable to find " + node.name + "'s static block");

			MethodNode arrayCreation = clinit;
			AbstractInsnNode setValues = null, newArray = null, fieldsSet = null;
			out: for (ListIterator<AbstractInsnNode> it = clinit.instructions.iterator(); it.hasNext();) {
				AbstractInsnNode insn = it.next();

				if (insn.getType() == AbstractInsnNode.FIELD_INSN && insn.getOpcode() == Opcodes.PUTSTATIC && valuesField.equals(((FieldInsnNode) insn).name)) {
					setValues = insn;

					//The values field has to be set to something so the previous instruction will never be null
					off: if (insn.getPrevious().getType() == AbstractInsnNode.METHOD_INSN) {
						MethodInsnNode minsn = (MethodInsnNode) insn.getPrevious();
						if (!node.name.equals(minsn.owner) || !minsn.desc.endsWith(")[L" + node.name + ';'))
							throw new IllegalStateException("Unexpected $VALUES array creator: " + minsn.owner + '#' + minsn.name + minsn.desc);

						for (MethodNode method : node.methods) {
							if (minsn.name.equals(method.name) && minsn.desc.equals(method.desc)) {
								arrayCreation = method;
								fieldsSet = minsn;

								it = method.instructions.iterator(method.instructions.size());
								for (insn = it.previous(); it.hasPrevious(); insn = it.previous()) {
									if (insn.getType() == AbstractInsnNode.INSN && insn.getOpcode() == Opcodes.ARETURN) {
										setValues = insn;
										break off;
									}
								}

								throw new IllegalStateException("$VALUES array creator " + minsn.owner + '#' + minsn.name + minsn.desc + " never returns?!");
							}
						}

						throw new IllegalStateException("Unable to find $VALUES array creator: " + minsn.owner + '#' + minsn.name + minsn.desc);
					}

					for (insn = it.previous(); it.hasPrevious(); insn = it.previous()) {
						if (insn.getType() == AbstractInsnNode.TYPE_INSN && insn.getOpcode() == Opcodes.ANEWARRAY) {
							newArray = it.previous();
							if (fieldsSet == null) fieldsSet = newArray;
							break out;
						}
					}

					throw new IllegalStateException("Unable to find $VALUES array creation point");
				}
			}
			if (setValues == null) throw new IllegalStateException("Unable to find $VALUES array setting point");

			int currentOrdinal;
			if (newArray.getType() == AbstractInsnNode.INT_INSN) {
				currentOrdinal = ((IntInsnNode) newArray).operand;
			} else if (newArray.getType() == AbstractInsnNode.INSN) {
				switch (newArray.getOpcode()) {
				case Opcodes.ICONST_0:
					currentOrdinal = 0;
					break;

				case Opcodes.ICONST_1:
					currentOrdinal = 1;
					break;

				case Opcodes.ICONST_2:
					currentOrdinal = 2;
					break;

				case Opcodes.ICONST_3:
					currentOrdinal = 3;
					break;

				case Opcodes.ICONST_4:
					currentOrdinal = 4;
					break;

				case Opcodes.ICONST_5:
					currentOrdinal = 5;
					break;

				default:
					throw new IllegalStateException("Unexpected Insn opcode: " + newArray.getOpcode());
				}
			} else {
				throw new IllegalStateException("Unexpected newArray instruction type: " + newArray.getType() + " (" + newArray.getOpcode() + ')');
			}

			String constructor = getConstructorDescriptor(builder.parameterTypes);
			Supplier<String> anonymousClassFactory;
			if (builder.willSubclass()) {
				anonymousClassFactory = anonymousClassFactory(node);

				node.access &= ~Opcodes.ACC_FINAL; //Ensure the type can be subclassed at all
				for (MethodNode method : node.methods) {
					if ("<init>".equals(method.name) && constructor.equals(method.desc)) {
						//Make sure the subclass can use the constructor it wants to
						method.access &= ~Opcodes.ACC_PRIVATE;
						break;
					}
				}
			} else {
				anonymousClassFactory = null;
			}
			InsnList fieldSetting = new InsnList();
			InsnList arrayFilling = new InsnList();

			for (EnumAddition addition : builder.getAdditions()) {
				node.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC + Opcodes.ACC_ENUM, addition.name, 'L' + node.name + ';', null, null);

				LabelNode stuffStart;
				if (builder.hasParameters()) {
					String poolKey = builder.type + '#' + addition.name; //As unique as the field name is

					fieldSetting.add(new FieldInsnNode(Opcodes.GETSTATIC, "com/chocohead/mm/EnumExtender", "POOL", "Ljava/util/Map;"));
					POOL.put(poolKey, addition.getParameters());
					fieldSetting.add(new LdcInsnNode(poolKey));
					fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
					fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
					fieldSetting.add(new VarInsnNode(Opcodes.ASTORE, 0));

					stuffStart = new LabelNode();
					fieldSetting.add(stuffStart);
				} else stuffStart = null;

				String additionType = addition.isEnumSubclass() ? anonymousClassFactory.get() : node.name;
				fieldSetting.add(new TypeInsnNode(Opcodes.NEW, additionType));
				fieldSetting.add(new InsnNode(Opcodes.DUP));

				fieldSetting.add(new LdcInsnNode(addition.name));
				fieldSetting.add(instructionForValue(currentOrdinal));

				for (int i = 0; i < builder.parameterTypes.length; i++) {
					fieldSetting.add(new VarInsnNode(Opcodes.ALOAD, 0));
					fieldSetting.add(instructionForValue(i));
					fieldSetting.add(new InsnNode(Opcodes.AALOAD));

					Type targetType = builder.parameterTypes[i];
					switch (targetType.getSort()) {//If the target is primitive, need to cast to the boxed form then unbox
					case Type.INT:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
						break;

					case Type.VOID:
						throw new AssertionError("Constructor takes a primitive void as a parameter?");

					case Type.BOOLEAN:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
						break;

					case Type.BYTE:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
						break;

					case Type.CHAR:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
						break;

					case Type.SHORT:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
						break;

					case Type.DOUBLE:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
						break;

					case Type.FLOAT:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
						break;

					case Type.LONG:
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
						fieldSetting.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
						break;

					case Type.OBJECT:
						if ("java/lang/Object".equals(targetType.getInternalName())) break;
					case Type.ARRAY:
						//Need to case to an object (that isn't Object which we already are)
						fieldSetting.add(new TypeInsnNode(Opcodes.CHECKCAST, targetType.getInternalName()));
						break;

					case Type.METHOD:
						throw new IllegalArgumentException("Tried to use method Type as a constructor argument");

					default:
						throw new IllegalStateException("Unexpected target type sort: " + targetType.getSort() + " (" + targetType + ')');
					}
				}

				fieldSetting.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, additionType, "<init>", constructor, false));
				fieldSetting.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, addition.name, 'L' + node.name + ';'));

				if (builder.hasParameters()) {
					LabelNode stuffEnd = new LabelNode();
					fieldSetting.add(stuffEnd);

					assert stuffStart != null;
					clinit.localVariables.add(new LocalVariableNode("stuff", "[Ljava/lang/Object;", null, stuffStart, stuffEnd, 0));
				}


				if (addition.isEnumSubclass()) {
					ClassTinkerers.define(additionType, EnumSubclasser.defineAnonymousSubclass(node, addition, additionType, constructor));
					node.innerClasses.add(new InnerClassNode(additionType, node.name, additionType.substring(node.name.length() + 1), Opcodes.ACC_ENUM));
				}


				arrayFilling.add(new InsnNode(Opcodes.DUP));
				arrayFilling.add(instructionForValue(currentOrdinal++));
				arrayFilling.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, addition.name, 'L' + node.name + ';'));
				arrayFilling.add(new InsnNode(Opcodes.AASTORE));
			}

			clinit.instructions.insertBefore(fieldsSet, fieldSetting);
			clinit.instructions.insertBefore(setValues, arrayFilling);
			arrayCreation.instructions.set(newArray, instructionForValue(currentOrdinal));

			if (builder.hasParameters()) clinit.maxLocals = Math.max(clinit.maxLocals, 1);
			clinit.maxStack = Math.max(clinit.maxStack, getStackSize(builder.parameterTypes));
		};
	}

	private static String getConstructorDescriptor(Type[] parameters) {
		StringBuilder stringBuilder = new StringBuilder("(Ljava/lang/String;I");
		for (Type parameter : parameters) {
			stringBuilder.append(parameter.getDescriptor());
		}
		return stringBuilder.append(")V").toString();
	}

	private static Supplier<String> anonymousClassFactory(ClassNode target) {
		String leadIn = target.name + '$';

		Set<String> seenInners = new HashSet<>();
		for (MethodNode method : target.methods) {
			on: for (ListIterator<AbstractInsnNode> it = method.instructions.iterator(); it.hasNext();) {
				AbstractInsnNode insn = it.next();

				if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
					String owner = ((MethodInsnNode) insn).owner;

					if (owner.startsWith(leadIn)) {
						for (int i = leadIn.length(); i < owner.length(); i++) {
							char c = owner.charAt(i);
							if ('0' > c || c > '9') continue on;
						}

						seenInners.add(owner.substring(leadIn.length()));
					}
				}
			}
		}

		return new Supplier<String>() {
			private int last = seenInners.stream().mapToInt(Integer::parseInt).max().orElse(0);

			@Override
			public String get() {
				return leadIn + ++last;
			}
		};
	}

	private static int getStackSize(Type[] parameters) {
		int size = 4; //+4 for <init> DUP, String, int

		//The size of the final parameter doesn't matter as the POOL index forces +1 space
		switch (parameters.length) {
		case 0:
			return size;

		case 1:
			assert parameters[0].getSize() <= 2;
			return size + 1 + 1;

		default:
			for (int i = 0, end = parameters.length - 1; i < end; i++) {
				size += parameters[i].getSize();
			}

			assert parameters[parameters.length - 1].getSize() <= 2;
			return size + 1 + 1;
		}
	}

	private static AbstractInsnNode instructionForValue(int value) {
		switch (value) {
		case -1:
			return new InsnNode(Opcodes.ICONST_M1);

		case 0:
			return new InsnNode(Opcodes.ICONST_0);

		case 1:
			return new InsnNode(Opcodes.ICONST_1);

		case 2:
			return new InsnNode(Opcodes.ICONST_2);

		case 3:
			return new InsnNode(Opcodes.ICONST_3);

		case 4:
			return new InsnNode(Opcodes.ICONST_4);

		case 5:
			return new InsnNode(Opcodes.ICONST_5);

		default:
			if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
				return new IntInsnNode(Opcodes.BIPUSH, value);
			} else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
				return new IntInsnNode(Opcodes.SIPUSH, value);
			} else {
				return new LdcInsnNode(value);
			}
		}
	}
}