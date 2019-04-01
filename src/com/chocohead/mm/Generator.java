/*
 * Copyright 2019 Chocohead
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.chocohead.mm;
/*
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.spongepowered.asm.mixin.transformer.ext.IClassGenerator;

public class Generator implements IClassGenerator {
	private final String rootPackage;
	private final int split;

	static Generator instance;
	static void fire(String rootPackage) {
		instance = new Generator(rootPackage);
	}

	private Generator(String rootPackage) {
		this.rootPackage = rootPackage;
		split = rootPackage.length();
	}

	@Override
	public byte[] generate(String name) {
		//System.out.println("Asked to generate " + name);
		if (name.startsWith(rootPackage)) {
			String target = name.substring(split);

			ClassWriter cw = new ClassWriter(0);
			cw.visit(52, Opcodes.ACC_PUBLIC + Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);

			AnnotationVisitor mixinAnnotation = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
			AnnotationVisitor targets = mixinAnnotation.visitArray("value");
			targets.visit(null, Type.getType('L' + target + ';'));
			targets.visitEnd();
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
			//System.out.println("Defined " + name.replace('.', '/'));
			return cw.toByteArray();
		} else {
			return null; //Not our class to generate
		}
	}
}*/