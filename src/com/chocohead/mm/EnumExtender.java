/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm;

import com.chocohead.mm.api.ClassTinkerers;
import com.chocohead.mm.api.EnumAdder;
import com.chocohead.mm.api.EnumAdder.EnumAddition;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

public final class EnumExtender {
    public static final Map<String, Object[]> POOL = new HashMap<>();

    static Consumer<ClassNode> makeEnumExtender(EnumAdder builder) {
        return node -> {
            if ((node.access & Opcodes.ACC_ENUM) != Opcodes.ACC_ENUM)
                throw new IllegalStateException("Tried to add enum entries to a non-enum type " + node.name);

            // Indicates that enums use the modern method where the values array is not created in the static constructor, but in a separate synthetic method.
            // Needed for versions newer than or equal to 21w19a since those are compiled with Java 16.
            // I *presume* this was changed in Java 12 as enums got a bit of an overhaul in that version, although I am not entirely certain about this.
            boolean modern = node.version >= Opcodes.V12;
            
            String valuesField = null;
            out: for (MethodNode method : node.methods) {
                if ("values".equals(method.name) && ("()[L" + node.name + ';').equals(method.desc)) {
                    for (AbstractInsnNode insn : method.instructions) {
                        if (insn.getType() == AbstractInsnNode.FIELD_INSN) {
                            valuesField = ((FieldInsnNode) insn).name;
                            break out;
                        }
                    }

                    throw new IllegalStateException("Unable to find values field in " + node.name + '#' + method.name + method.desc);
                }
            }
            if (valuesField == null) throw new IllegalStateException("Unable to find " + node.name + "#values()[L" + node.name + ';');

            //Even empty enums have values and valueOf, which by extension means they have a static block to make the (empty) $VALUES field
            MethodNode clinit = node.methods.stream().filter(method -> "<clinit>".equals(method.name) && "()V".equals(method.desc)).findFirst().orElseThrow(() -> new IllegalStateException("Unable to find " + node.name + "'s static block"));

            MethodInsnNode createValuesArrayMethodInsn = modern ? StreamSupport.stream(clinit.instructions.spliterator(), false)
                    .filter(insn -> insn.getType() == AbstractInsnNode.METHOD_INSN && insn.getOpcode() == Opcodes.INVOKESTATIC && ((MethodInsnNode) insn).owner.equals(node.name) &&
                            Optional.ofNullable(getMethodNode(node, (MethodInsnNode) insn)).map(method -> (method.access & Opcodes.ACC_SYNTHETIC) == Opcodes.ACC_SYNTHETIC).orElse(false))
                    .findFirst().map(insn -> (MethodInsnNode) insn)
                    .orElseThrow(() -> new IllegalStateException("Unable to find values creation method")) : null; // Nonexistent on legacy

            MethodNode createValuesArrayMethod = modern ? node.methods.stream()
                    .filter(method -> method.name.equals(createValuesArrayMethodInsn.name) && method.desc.equals(createValuesArrayMethodInsn.desc))
                    .findFirst().orElseThrow(() -> new IllegalStateException("Unable to find values creation method")) : null; // Nonexistent on legacy

            AbstractInsnNode newArray = null, setValues = null;
            if (modern)
                newArray = StreamSupport.stream(createValuesArrayMethod.instructions.spliterator(), false)
                        .filter(insn -> insn.getType() == AbstractInsnNode.TYPE_INSN && insn.getOpcode() == Opcodes.ANEWARRAY)
                        .findFirst().map(AbstractInsnNode::getPrevious).orElseThrow(() -> new IllegalStateException("Unable to find $VALUES array creation point"));
            else {
                out: for (ListIterator<AbstractInsnNode> it = clinit.instructions.iterator(); it.hasNext();) {
                    AbstractInsnNode insn = it.next();

                    if (insn.getType() == AbstractInsnNode.FIELD_INSN && insn.getOpcode() == Opcodes.PUTSTATIC && valuesField.equals(((FieldInsnNode) insn).name)) {
                        setValues = insn;

                        for (insn = it.previous(); it.hasPrevious(); insn = it.previous()) {
                            if (insn.getType() == AbstractInsnNode.TYPE_INSN && insn.getOpcode() == Opcodes.ANEWARRAY) {
                                newArray = it.previous();
                                break out;
                            }
                        }

                        throw new IllegalStateException("Unable to find $VALUES array creation point");
                    }
                }
                if (setValues == null) throw new IllegalStateException("Unable to find $VALUES array setting point");
            }

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
            Supplier<String> anonymousClassFactory = builder.willSubclass() ? anonymousClassFactory(node) : null;

            for (EnumAddition addition : builder.getAdditions()) {
                node.visitField(Opcodes.ACC_PUBLIC + Opcodes.ACC_FINAL + Opcodes.ACC_STATIC + Opcodes.ACC_ENUM, addition.name, 'L' + node.name + ';', null, null);

                String poolKey = builder.type + '#' + addition.name; //As unique as the field name is
                InsnList method = new InsnList();

                LabelNode stuffStart;
                if (builder.hasParameters()) {
                    method.add(new FieldInsnNode(Opcodes.GETSTATIC, "com/chocohead/mm/EnumExtender", "POOL", "Ljava/util/Map;"));
                    POOL.put(poolKey, addition.getParameters());
                    method.add(new LdcInsnNode(poolKey));
                    method.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
                    method.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
                    method.add(new VarInsnNode(Opcodes.ASTORE, 0));

                    stuffStart = new LabelNode();
                    method.add(stuffStart);
                } else stuffStart = null;

                String additionType = addition.isEnumSubclass() ? Objects.requireNonNull(anonymousClassFactory).get() : node.name;
                method.add(new TypeInsnNode(Opcodes.NEW, additionType));
                method.add(new InsnNode(Opcodes.DUP));

                method.add(new LdcInsnNode(addition.name));
                method.add(instructionForValue(currentOrdinal));

                for (int i = 0; i < builder.parameterTypes.length; i++) {
                    method.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    method.add(instructionForValue(i));
                    method.add(new InsnNode(Opcodes.AALOAD));

                    Type targetType = builder.parameterTypes[i];
                    switch (targetType.getSort()) {//If the target is primitive, need to cast to the boxed form then unbox
                        case Type.INT:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
                            break;

                        case Type.VOID:
                            throw new AssertionError("Constructor takes a primitive void as a parameter?");

                        case Type.BOOLEAN:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
                            break;

                        case Type.BYTE:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
                            break;

                        case Type.CHAR:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
                            break;

                        case Type.SHORT:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
                            break;

                        case Type.DOUBLE:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
                            break;

                        case Type.FLOAT:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
                            break;

                        case Type.LONG:
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                            method.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
                            break;

                        case Type.OBJECT:
                            if ("java/lang/Object".equals(targetType.getInternalName())) break;
                        case Type.ARRAY:
                            //Need to case to an object (that isn't Object which we already are)
                            method.add(new TypeInsnNode(Opcodes.CHECKCAST, targetType.getInternalName()));
                            break;

                        case Type.METHOD:
                            throw new IllegalArgumentException("Tried to use method Type as a constructor argument");

                        default:
                            throw new IllegalStateException("Unexpected target type sort: " + targetType.getSort() + " (" + targetType + ')');
                    }
                }

                method.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, additionType, "<init>", constructor, false));
                method.add(new FieldInsnNode(Opcodes.PUTSTATIC, node.name, addition.name, 'L' + node.name + ';'));

                if (builder.hasParameters()) {
                    LabelNode stuffEnd = new LabelNode();
                    method.add(stuffEnd);

                    clinit.localVariables.add(new LocalVariableNode("stuff", "[Ljava/lang/Object;", null, Objects.requireNonNull(stuffStart), stuffEnd, 0));
                }

                String fieldDesc = "L" + node.name + ";";
                AbstractInsnNode insertAfter = modern ? StreamSupport.stream(clinit.instructions.spliterator(), false)
                        .filter(node0 -> node0.getType() == AbstractInsnNode.FIELD_INSN && ((FieldInsnNode) node0).owner.equals(node.name) && fieldDesc.equals(((FieldInsnNode) node0).desc))
                        .reduce((first, second) -> second).orElseThrow(() -> new IllegalStateException("Unable to find return statement of array creation method")) : null; // Get last element

                if (modern) clinit.instructions.insert(insertAfter, method);
                else clinit.instructions.insertBefore(setValues, method);

                if (addition.isEnumSubclass()) {
                    ClassTinkerers.define(additionType, EnumSubclasser.defineAnonymousSubclass(node, addition, additionType, constructor));
                    node.innerClasses.add(new InnerClassNode(additionType, node.name, additionType.substring(node.name.length() + 1), Opcodes.ACC_ENUM));

                    for (MethodNode m : node.methods) {
                        if ("<init>".equals(m.name) && constructor.equals(m.desc)) {
                            //Make sure the subclass can use the constructor it wants to
                            m.access = m.access & ~Opcodes.ACC_PRIVATE;
                            break;
                        }
                    }
                }


                method = new InsnList();

                method.add(new InsnNode(Opcodes.DUP));
                method.add(instructionForValue(currentOrdinal++));
                method.add(new FieldInsnNode(Opcodes.GETSTATIC, node.name, addition.name, 'L' + node.name + ';'));
                method.add(new InsnNode(Opcodes.AASTORE));

                // Inserting right before the return statement of the method.
                if (modern) createValuesArrayMethod.instructions.insertBefore(StreamSupport.stream(createValuesArrayMethod.instructions.spliterator(), false).filter(insn -> insn.getOpcode() == Opcodes.ARETURN).findFirst().orElseThrow(() -> new IllegalStateException("Unable to find return statement of array creation method")), method);
                else clinit.instructions.insertBefore(setValues, method);
            }

            clinit.instructions.set(newArray, instructionForValue(currentOrdinal));
            if (builder.hasParameters()) clinit.maxLocals = Math.max(clinit.maxLocals, 1);
            clinit.maxStack = Math.max(clinit.maxStack, getStackSize(builder.parameterTypes));

            if ((node.access & Opcodes.ACC_FINAL) != 0 && builder.willSubclass()) {
                node.access = node.access & ~Opcodes.ACC_FINAL;
            }
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
            on: for (AbstractInsnNode insn : method.instructions) {
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

    private static MethodNode getMethodNode(ClassNode node, MethodInsnNode insn) {
        return insn.owner.equals(node.name) ? node.methods.stream().filter(method -> method.name.equals(insn.name) && method.desc.equals(insn.desc)).findFirst().orElse(null) : null;
    }
}
