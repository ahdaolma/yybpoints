package com.codex.yybpoints;

import java.lang.reflect.Field;

/** Reads the awarded amount from the app's successful 5401 response. */
final class ClaimPointReader {
    private ClaimPointReader() { }

    static int fromSuccessResponse(Object response) {
        if (response == null || !"y.l0".equals(response.getClass().getName())) return -1;
        try {
            if (field(response, "a").getInt(response) != 0) return -1;
            Object award = field(response, "c").get(response);
            if (award == null || !"y.j".equals(award.getClass().getName())) return -1;
            long points = field(award, "a").getLong(award);
            return points > 0 && points <= Integer.MAX_VALUE ? (int) points : -1;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return -1;
        }
    }

    private static Field field(Object object, String name) throws NoSuchFieldException {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
