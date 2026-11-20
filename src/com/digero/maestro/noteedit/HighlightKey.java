package com.digero.maestro.noteedit;

import java.util.ArrayList;
import java.util.List;

/** Display-only piano-roll scale selection, independent of the exported key. */
public final class HighlightKey {
    public static final String AUTO = "Auto";
    private static final String[] ROOTS = {"C","C#","D","Eb","E","F","F#","G","Ab","A","Bb","B"};
    private static final int[] MAJOR = {0,2,4,5,7,9,11}, MINOR = {0,2,3,5,7,8,10};
    private HighlightKey() {}
    public static List<String> options() {
        List<String> keys = new ArrayList<>(); keys.add(AUTO);
        for (String root : ROOTS) { keys.add(root+" major"); keys.add(root+" minor"); }
        return List.copyOf(keys);
    }
    public static String normalize(String key) { return options().contains(key == null ? "" : key) ? key : AUTO; }
    public static int[] scale(String key) {
        int index = options().indexOf(key)-1;
        if(index < 0) throw new IllegalArgumentException("Expected a manual key");
        int root=index/2;
        return java.util.Arrays.stream(index%2==0?MAJOR:MINOR).map(n->(n+root)%12).toArray();
    }
}
