package android.os;

import java.util.ArrayList;
import java.util.List;

/** Deterministic main queue; delayed callbacks run only when requested by a test. */
public class Handler {
    private final List<Runnable> immediate = new ArrayList<>();
    private final List<Runnable> delayed = new ArrayList<>();
    public boolean post(Runnable task) { immediate.add(task); return true; }
    public boolean postDelayed(Runnable task, long delay) { delayed.add(task); return true; }
    public void removeCallbacks(Runnable task) {
        immediate.removeIf(item -> item == task);
        delayed.removeIf(item -> item == task);
    }
    public void drain() {
        while (!immediate.isEmpty()) immediate.remove(0).run();
    }
    public void elapse() {
        List<Runnable> pending = new ArrayList<>(delayed);
        delayed.clear();
        pending.forEach(Runnable::run);
        drain();
    }
    public boolean empty() { return immediate.isEmpty() && delayed.isEmpty(); }
}
