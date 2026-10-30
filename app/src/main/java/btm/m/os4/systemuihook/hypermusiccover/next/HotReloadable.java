// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next;

/**
 * A feature that can be taken back out of a process and put back into it.
 *
 * <p>{@link #retire()} runs while retirement is still allowed to fail: it is the feature's own
 * "off" path, the same one the lock screen detach uses, not a second implementation. Anything it
 * might not manage must be reported before this is called (the busy checks in HotReload), so
 * this method may assume the process is quiet.
 *
 * <p>{@link #save()} returns only framework or host objects and primitives - never one of ours,
 * whose class loader is about to be replaced. {@link #adopt(Object)} receives that back in the
 * new generation and must tolerate null, which is what an unreadable saved state becomes.
 *
 * <p>Step 2 adds the shape; the features implement it as each is migrated.
 */
public interface HotReloadable {

    /** Restore the host to what it looked like before this feature touched it. */
    default void retire() {
    }

    /** What this feature needs the next generation to know, in saved-state-safe types. */
    default Object save() {
        return null;
    }

    /** Take over from the retired generation. Null means it could not tell us anything. */
    default void adopt(Object saved) {
    }
}
