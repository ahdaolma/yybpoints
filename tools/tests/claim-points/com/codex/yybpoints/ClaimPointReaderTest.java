package com.codex.yybpoints;

public final class ClaimPointReaderTest {
    public static void main(String[] args) {
        check(25, ClaimPointReader.fromSuccessResponse(new y.l0(0, new y.j(25))));
        check(-1, ClaimPointReader.fromSuccessResponse(new y.l0(3, new y.j(25))));
        check(-1, ClaimPointReader.fromSuccessResponse(new y.l0(0, null)));
        check(-1, ClaimPointReader.fromSuccessResponse(new y.l0(0, new y.j(0))));
        check(-1, ClaimPointReader.fromSuccessResponse(new Object()));
        check(-1, ClaimPointReader.fromSuccessResponse(
                new y.l0(0, new y.j((long) Integer.MAX_VALUE + 1))));
        System.out.println("6 claim point checks passed");
    }

    private static void check(int expected, int actual) {
        if (expected != actual) throw new AssertionError(expected + " != " + actual);
    }
}
