package com.contextflow.contracts;

import java.lang.annotation.*;

/** JSON input metadata: absent is allowed; explicit null is rejected by RequestFields. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface NonNullIfPresent {}
