// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.newnotificationcenter;

import android.os.SystemClock;
import android.util.Log;
import android.view.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

/** Single-dispatch clearing, finite feedback and bounded clear-button lookup. */
public final class IosShadeClearAction implements View.OnTouchListener {
    private static final String TAG="HyperChangerIosShadeFix";
    private static final IosShadeClearAction TOUCH=new IosShadeClearAction();
    private static WeakReference<View> scanRoot=new WeakReference<>(null);
    private static long lastScan, lastClear;
    public static boolean allowClearScan(View root,long now) {
        if(scanRoot.get()!=root || now-lastScan>=250L) {
            scanRoot=new WeakReference<>(root); lastScan=now; return true;
        }
        return false;
    }
    public static void bindClear(View clear) { clear.setOnTouchListener(TOUCH); IosShadeClearGesture.setTarget(clear); Log.i(TAG,"clear touch target bound"); }
    public static boolean hitClear(View clear,MotionEvent event) {
        if(clear==null || event==null || event.getActionMasked()!=MotionEvent.ACTION_DOWN || !clear.isShown()) return false;
        android.graphics.Rect r=new android.graphics.Rect();
        boolean hit=clear.getGlobalVisibleRect(r) && r.contains((int)event.getRawX(),(int)event.getRawY());
        return hit;
    }
    public static void raiseHeader(View header) {
        if(header==null || !(header.getParent() instanceof ViewGroup)) return;
        ViewGroup group=(ViewGroup)header.getParent();
        if(group.getChildCount()>0 && group.getChildAt(group.getChildCount()-1)!=header) header.bringToFront();
    }
    @Override public boolean onTouch(View view,MotionEvent event) {
        ViewParent parent=view.getParent();
        int action=event.getActionMasked();
        if(parent!=null && action==MotionEvent.ACTION_DOWN) parent.requestDisallowInterceptTouchEvent(true);
        if(parent!=null && (action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)) parent.requestDisallowInterceptTouchEvent(false);
        return false; // keep normal click, slop cancellation, accessibility and feedback
    }
    public static void clear(View window,View stack,View nativeView,View.OnClickListener listener,View button) {
        long now=SystemClock.uptimeMillis();
        if(now-lastClear<250L) return;
        if(listener!=null && nativeView!=null && nativeView.isAttachedToWindow() && folded(listener)) {
            nativeClick(listener,nativeView,now); return;
        }
        View live=stack;
        if(window!=null) {
            int id=window.getResources().getIdentifier("notification_stack_scroller","id","com.android.systemui");
            View resolved=id==0?null:window.findViewById(id);
            if(resolved!=null) live=resolved;
        }
        if(live!=null && live.isAttachedToWindow() && (window==null || live.getRootView()==window.getRootView())) {
            try {
                Boolean available=hasClearable(live);
                if(Boolean.FALSE.equals(available)) {
                    lastClear=now; feedback(true); Log.i(TAG,"clear empty: no eligible notifications"); return;
                }
                Method method=live.getClass().getMethod("clearNotifications",int.class,boolean.class,boolean.class);
                method.invoke(live,0,true,true);
                lastClear=now; feedback(false); Log.i(TAG,"clear dispatched once to live stack"); return;
            } catch(java.lang.reflect.InvocationTargetException error) {
                Log.w(TAG,"native stack rejected clear",error.getCause()); return;
            } catch(ReflectiveOperationException error) { Log.w(TAG,"live stack clear unavailable",error); }
        }
        // A fallback runs only when the preferred dispatch is unavailable, never after it.
        if(listener!=null && nativeView!=null && nativeView.isAttachedToWindow()) {
            nativeClick(listener,nativeView,now); return;
        }
        if(button!=null && button.isAttachedToWindow()) {
            try { button.performClick(); lastClear=now; }
            catch(RuntimeException error) { Log.w(TAG,"clear view rejected click",error); }
        }
    }
    private static void nativeClick(View.OnClickListener listener,View view,long now) {
        try { listener.onClick(view); lastClear=now; feedback(false); Log.i(TAG,"clear dispatched once to native listener"); }
        catch(RuntimeException error) { Log.w(TAG,"native listener rejected clear",error); }
    }
    private static Boolean hasClearable(View stack) {
        try {
            Class<?> row=Class.forName("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow",false,stack.getClass().getClassLoader());
            Method include=stack.getClass().getMethod("includeChildInClearAll",row,int.class);
            return eligible(stack,row,include);
        } catch(ReflectiveOperationException error) {
            Log.w(TAG,"eligibility unavailable; retaining native clear behavior",error); return null;
        }
    }
    private static boolean eligible(View view,Class<?> row,Method include) throws ReflectiveOperationException {
        if(row.isInstance(view) && Boolean.TRUE.equals(include.invoke(null,view,0))) return true;
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) if(eligible(group.getChildAt(i),row,include)) return true;
        }
        return false;
    }
    private static void feedback(boolean empty) {
        View button=IosShadeClearGesture.button();
        if(button==null || !button.isAttachedToWindow()) return;
        button.performHapticFeedback(empty?HapticFeedbackConstants.REJECT:HapticFeedbackConstants.CONFIRM);
        animateFeedback(button,empty);
        if(button.getParent() instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)button.getParent();
            for(int i=0;i<group.getChildCount();i++) {
                View sibling=group.getChildAt(i);
                if(sibling!=button && sibling.getClass()==View.class && Math.abs(sibling.getWidth()-button.getWidth())<=2) animateFeedback(sibling,empty);
            }
        }

    }
    private static void animateFeedback(View view,boolean empty) {
        if(empty) {
            float d=3f*view.getResources().getDisplayMetrics().density;
            android.animation.ObjectAnimator animation=android.animation.ObjectAnimator.ofFloat(view,"translationX",0,-d,d,-d/2f,0);
            animation.setDuration(180L); animation.start();
        } else {
            android.animation.ObjectAnimator x=android.animation.ObjectAnimator.ofFloat(view,"scaleX",1f,.94f,1f);
            android.animation.ObjectAnimator y=android.animation.ObjectAnimator.ofFloat(view,"scaleY",1f,.94f,1f);
            x.setDuration(160L); y.setDuration(160L); x.start(); y.start();
        }
    }
    private static Object field(Object owner,String name) throws ReflectiveOperationException {
        java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
    private static boolean folded(View.OnClickListener listener) {
        try { return Boolean.TRUE.equals(field(field(field(listener,"this$0"),"foldManager"),"isShowingFold")); }
        catch(ReflectiveOperationException ignored) { return false; }
    }
}
