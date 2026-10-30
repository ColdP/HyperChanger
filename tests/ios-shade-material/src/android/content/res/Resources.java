package android.content.res;

public class Resources {
    public String getResourceEntryName(int id) {
        if (id == 7) return "notification_group_collapse";
        if (id == 8) return "collapse_button";
        return "test_view";
    }
}
