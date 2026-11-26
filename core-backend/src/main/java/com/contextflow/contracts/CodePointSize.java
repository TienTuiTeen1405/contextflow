package com.contextflow.contracts;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = CodePointSize.Validator.class)
@Target({
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.METHOD,
    ElementType.TYPE_USE,
    ElementType.RECORD_COMPONENT
})
@Retention(RetentionPolicy.RUNTIME)
public @interface CodePointSize {
    String message() default "Unicode code-point length is outside the contract bounds";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    int min() default 0;

    int max() default Integer.MAX_VALUE;

    final class Validator implements ConstraintValidator<CodePointSize, String> {
        private int min;
        private int max;

        public void initialize(CodePointSize constraint) {
            min = constraint.min();
            max = constraint.max();
        }

        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) return true;
            int length = value.codePointCount(0, value.length());
            return length >= min && length <= max;
        }
    }
}
