package com.codex.yybpoints;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/** Reads the awarded amount from the app's successful 5401 response. */
final class ClaimPointReader {
    private ClaimPointReader() { }

    static int fromSuccessResponse(Object response) {
        if (response instanceof List) {
            List<?> items = (List<?>) response;
            response = items.isEmpty() ? null : items.get(0);
        }
        if (response == null || !"y.l0".equals(response.getClass().getName())) return -1;
        try {
            Field code = fieldOrUniqueType(response, "a", int.class);
            if (code == null || code.getInt(response) != 0) return -1;
            Field awardField = fieldOrUniqueType(response, "c", Object.class, "y.j");
            Object award = awardField == null ? null : awardField.get(response);
            if (award == null || !"y.j".equals(award.getClass().getName())) return -1;
            Field amount = fieldOrUniqueNumber(award, "a");
            if (amount == null) return -1;
            long points = ((Number) amount.get(award)).longValue();
            return points > 0 && points <= Integer.MAX_VALUE ? (int) points : -1;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return -1;
        }
    }

    static String fieldShape(Object response) {
        if (response instanceof List) {
            List<?> items = (List<?>) response;
            response = items.isEmpty() ? null : items.get(0);
        }
        if (response == null) return "null";
        StringBuilder shape = new StringBuilder(response.getClass().getName());
        for (Field field : response.getClass().getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                shape.append(' ').append(field.getName()).append(':')
                        .append(field.getType().getName());
                if ("y.j".equals(field.getType().getName())) {
                    for (Field nested : field.getType().getDeclaredFields()) {
                        if (!Modifier.isStatic(nested.getModifiers()))
                            shape.append(' ').append(nested.getName()).append(':')
                                    .append(nested.getType().getName());
                    }
                }
            }
        }
        return shape.toString();
    }

    private static Field fieldOrUniqueType(Object object, String preferred,
                                           Class<?> type) {
        return fieldOrUniqueType(object, preferred, type, null);
    }

    private static Field fieldOrUniqueType(Object object, String preferred,
                                           Class<?> type, String exactTypeName) {
        Field found = null;
        boolean ambiguous = false;
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (exactTypeName == null ? field.getType() != type
                    : !exactTypeName.equals(field.getType().getName())) continue;
            if (preferred.equals(field.getName())
                    || (type == int.class && "f8147a".equals(field.getName()))) {
                field.setAccessible(true);
                return field;
            }
            if (found != null) ambiguous = true;
            else found = field;
        }
        if (ambiguous) return null;
        if (found != null) found.setAccessible(true);
        return found;
    }

    private static Field fieldOrUniqueNumber(Object object, String preferred)
            throws IllegalAccessException {
        Field found = null;
        boolean ambiguous = false;
        for (Field field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || (field.getType() != int.class && field.getType() != long.class)) continue;
            field.setAccessible(true);
            if (preferred.equals(field.getName())) return field;
            Number value = (Number) field.get(object);
            if (value.longValue() <= 0) continue;
            if (found != null) ambiguous = true;
            else found = field;
        }
        return ambiguous ? null : found;
    }
}
