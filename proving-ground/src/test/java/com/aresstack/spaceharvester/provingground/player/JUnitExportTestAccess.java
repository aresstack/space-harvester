package com.aresstack.spaceharvester.provingground.player;

import java.util.Map;

/** The export test's compiler for tests in other packages. */
public final class JUnitExportTestAccess {

    private JUnitExportTestAccess() {
    }

    public static String compile(JUnitExport.Result result, Map<String, byte[]> classes) throws Exception {
        return JUnitExportTest.compile(result, classes);
    }
}
