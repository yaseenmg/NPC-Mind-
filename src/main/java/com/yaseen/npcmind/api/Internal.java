package com.yaseen.npcmind.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a public type or member as an <b>implementation detail</b> of NPC Mind that is
 * <b>not</b> covered by the API stability guarantee (see {@code docs/API_STABILITY.md}).
 *
 * <p>Any class outside {@code com.yaseen.npcmind.api}, outside the stable tool contract
 * ({@code AITool}, {@code ToolContract}, {@code JsonArgs}) and outside the bootstrap facade
 * ({@code NPCMindLibrary}) carries this annotation. Host mods must not call into
 * {@code @Internal} types: they may be renamed, reshaped, moved to another package, or
 * removed in <em>any</em> release — including patch releases — without notice and without a
 * deprecation cycle.</p>
 *
 * <p>The retention is {@link RetentionPolicy#CLASS}: the marker is recorded in the class
 * file (so IDEs and bytecode tools surface it) but adds no runtime reflection cost and
 * cannot be queried through {@code getAnnotation} at runtime. Use
 * {@code tools/check_api_surface.py} to verify the annotation coverage mechanically.</p>
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.PACKAGE})
public @interface Internal {
    /** Optional note explaining why the element is internal, or what the stable replacement is. */
    String value() default "";
}
