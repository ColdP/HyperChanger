// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;

/** Frames replaced on a fingerprint surface must be repainted when hiding ends. */
final class FingerprintFrameRestore<T> {
    private final Map<T, Integer> frames = new WeakHashMap<>();

    synchronized void suppressed(T animation, int resource) {
        frames.put(animation, resource);
    }

    synchronized void painted(T animation) {
        frames.remove(animation);
    }

    synchronized int pendingCount() {
        return frames.size();
    }

    void restore(BiConsumer<T, Integer> draw) {
        Map<T, Integer> snapshot;
        synchronized (this) {
            snapshot = new java.util.HashMap<>(frames);
            frames.clear();
        }
        snapshot.forEach(draw);
    }
}
