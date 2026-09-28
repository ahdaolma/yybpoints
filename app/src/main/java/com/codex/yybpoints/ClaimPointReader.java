package com.codex.yybpoints;

/** Reads the awarded amount from the app's successful 5401 response. */
final class ClaimPointReader {
    private ClaimPointReader() { }

    static int fromSuccessResponse(Object response) {
        if (response == null || !"y.l0".equals(response.getClass().getName())) return -1;
        try {
            if (response.getClass().getField("a").getInt(response) != 0) return -1;
            Object award = response.getClass().getField("c").get(response);
            if (award == null || !"y.j".equals(award.getClass().getName())) return -1;
            long points = award.getClass().getField("a").getLong(award);
            return points > 0 && points <= Integer.MAX_VALUE ? (int) points : -1;
        } catch (ReflectiveOperationException | SecurityException ignored) {
            return -1;
        }
    }
}
