// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook;

import java.util.Arrays;
import java.util.Collections;

/** Host regression for paging, stock Flow collection and coroutine cancellation. */
public final class WidgetCatalogRegression {
    public interface Continuation {}
    public interface Collector { Object emit(Object value, Continuation continuation); }
    public interface Flow { Object d(Collector collector, Continuation continuation); }
    public static final class Single implements Flow {
        final Object a;
        public Single(Object value) { a = value; }
        public Object d(Collector collector, Continuation continuation) { return collector.emit(a, continuation); }
    }
    public static final class Other implements Flow {
        public Object d(Collector collector, Continuation continuation) { return null; }
    }
    public interface Key {}
    public interface Element {}
    public interface Context { Element get(Key key); }
    public interface RenamedJob extends Element {
        boolean isActive();
        final class ChangedKey implements Key { public static final ChangedKey x = new ChangedKey(); }
    }
    public interface AmbiguousJob extends Element {
        boolean isActive();
        final class A implements Key { public static final A x = new A(); }
        final class B implements Key { public static final B x = new B(); }
    }
    private static void check(boolean value) { if (!value) throw new AssertionError(); }

    public static void main(String[] args) throws Throwable {
        var cloud = Arrays.<Object>asList("c1", "c2", "c3", "c4", "c5", "c6");
        var first = new LocalWidgetCatalogPage(cloud, true, 1);
        first.expand(Arrays.<Object>asList("c1", "c2", "c3", "c4", "c5", "c6", "local"));
        check(cloud.size() == 6 && first.size() == 6 && first.expanded.size() == 7);
        var offline = new LocalWidgetCatalogPage(Collections.emptyList(), true, 1);
        offline.expand(Arrays.<Object>asList("local1", "local2"));
        check(!offline.isEmpty() && offline.size() < 6 && offline.expanded.size() == 2);
        var last = new LocalWidgetCatalogPage(Collections.emptyList(), false, 1);
        last.expand(Collections.emptyList()); check(last.isEmpty());

        var adapter = new LocalWidgetCatalogFlow(Single.class, Flow.class, Collector.class, Continuation.class);
        var original = new Single(cloud);
        check(adapter.map(original, value -> value) == original);
        var mapped = (Flow) adapter.map(original, value -> first.expanded);
        Object suspended = new Object();
        check(mapped.d((value, continuation) -> {
            check(value == first.expanded); return suspended;
        }, null) == suspended);
        check(original.a == cloud);
        var other = new Other();
        check(adapter.map(other, value -> { throw new AssertionError("Must preserve foreign flow"); }) == other);
        check(adapter.map(null, value -> { throw new AssertionError("Must preserve null"); }) == null);

        var job = new LocalWidgetCatalogJob(Context.class, RenamedJob.class);
        RenamedJob cancelled = () -> false;
        Context context = key -> key == job.key ? cancelled : null;
        check(job.key == RenamedJob.ChangedKey.x);
        check(job.contextGet.invoke(context, job.key) == cancelled);
        check(Boolean.FALSE.equals(job.active.invoke(cancelled)));
        try {
            new LocalWidgetCatalogJob(Context.class, AmbiguousJob.class);
            throw new AssertionError("Must reject ambiguous Job.Key");
        } catch (ReflectiveOperationException expected) { /* original host remains owner */ }

        check(!LocalWidgetCatalogPolicy.local("cloud/product"));
        check(LocalWidgetCatalogPolicy.local(LocalWidgetCatalogPolicy.PREFIX + "a.b/Receiver"));
        check(LocalWidgetCatalogPolicy.providerKey("a.b", ".Receiver")
                .equals(LocalWidgetCatalogPolicy.providerKey("a.b", "a.b.Receiver")));
        check(!LocalWidgetCatalogPolicy.previewUri("a.b", 0, 0, 0).isEmpty());
        System.out.println("PASS: catalogue paging, Flow suspension/passthrough, cancellation and provider identity");
    }
}
