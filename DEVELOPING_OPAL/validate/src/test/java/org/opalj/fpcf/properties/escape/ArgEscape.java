package org.opalj.fpcf.properties.escape;

import org.opalj.fpcf.FPCFAnalysis;
import org.opalj.fpcf.analyses.escape.InterproceduralEscapeAnalysis;
import org.opalj.fpcf.analyses.escape.SimpleEscapeAnalysis;
import org.opalj.fpcf.properties.PropertyValidator;

import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

@PropertyValidator(key = "EscapeProperty", validator = ArgEscapeMatcher.class)
@Target({ TYPE_USE, PARAMETER })
public @interface ArgEscape {

    /**
     * A short reasoning of this property.
     */
    String value();

    Class<? extends FPCFAnalysis>[] analyses() default { SimpleEscapeAnalysis.class,
            InterproceduralEscapeAnalysis.class };
}