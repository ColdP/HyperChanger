package android.os;

public final class SystemProperties {
    public static String lastKey;
    public static int deviceReads;
    public static String get(String key) {
        lastKey = key;
        if ("ro.product.brand".equals(key)) return System.getProperty("nativebose.test.brand", "ReDmI");
        if ("ro.product.device".equals(key)) deviceReads++;
        return "ro.product.device".equals(key) ? "annibale" : "";
    }
}
