package com.example.limitreq.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequestFrequencyLimit {
    /**
     * Frequency.
     * Unit millisecond.
     *
     * @return frequency
     */
    int value() default 1000;
}
