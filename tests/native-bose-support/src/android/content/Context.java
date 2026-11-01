package android.content;

/** Minimal JVM context for exercising the production preference stores and preset codec. */
public abstract class Context {
    public static final int MODE_PRIVATE = 0;
    public abstract SharedPreferences getSharedPreferences(String name, int mode);
}
