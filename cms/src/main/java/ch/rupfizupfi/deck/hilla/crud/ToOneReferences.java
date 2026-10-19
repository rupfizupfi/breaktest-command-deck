package ch.rupfizupfi.deck.hilla.crud;

import ch.rupfizupfi.deck.data.AbstractEntity;
import com.vaadin.hilla.exception.EndpointValidationException;
import com.vaadin.hilla.exception.EndpointValidationException.ValidationErrorData;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Normalises the {@code @ManyToOne}/{@code @OneToOne} references of a payload before it is saved.
 * <p>
 * A Hilla form starts a reference field at the model's empty value — an empty object with no id —
 * and sends it unchanged when nothing was picked. Handed to JPA, that is a transient entity and the
 * save fails with {@code TransientPropertyValueException}, a 500. A reference without an id names
 * no row, so it is the same as none: it becomes {@code null}. A reference the mapping declares
 * {@code optional = false} that is then {@code null} is refused as a validation error on that field,
 * which the form shows next to it, instead of reaching the database's not-null constraint.
 */
final class ToOneReferences {
    static final String REQUIRED = "must be selected";

    private ToOneReferences() {
    }

    static void normalize(Object value) {
        List<ValidationErrorData> missing = new ArrayList<>();
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                if (manyToOne == null && oneToOne == null) {
                    continue;
                }
                Object target = read(field, value);
                if (target instanceof AbstractEntity reference && reference.getId() == null) {
                    write(field, value, null);
                    target = null;
                }
                boolean optional = manyToOne != null ? manyToOne.optional() : oneToOne.optional();
                if (target == null && !optional) {
                    missing.add(new ValidationErrorData(REQUIRED, field.getName()));
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new EndpointValidationException(missing);
        }
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read the reference " + field.getName(), e);
        }
    }

    private static void write(Field field, Object owner, Object target) {
        try {
            field.set(owner, target);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot clear the reference " + field.getName(), e);
        }
    }
}
