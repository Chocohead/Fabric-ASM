package com.chocohead.mm;

import static java.lang.annotation.ElementType.CONSTRUCTOR;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.CLASS;

import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

@Retention(CLASS)
@Target({METHOD, CONSTRUCTOR})
@Repeatable(CorrectedMethods.class)
public @interface CorrectedMethod {
	String from();

	String to();
}