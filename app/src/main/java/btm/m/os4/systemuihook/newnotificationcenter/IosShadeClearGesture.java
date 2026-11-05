// SPDX-License-Identifier: AGPL-3.0-or-later
package btm.m.os4.systemuihook.newnotificationcenter;

import android.graphics.Rect;
import android.util.Log;
import android.view.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import io.github.libxposed.api.XposedInterface;

/** Capture only gestures starting on the hosted clear button, before OEM interception. */
public final class IosShadeClearGesture implements XposedInterface.Hooker {
    private static WeakReference<View> target=new WeakReference<>(null);
    private static boolean installed;
    private WeakReference<View> pressed=new WeakReference<>(null);
    private int pointer=-1;
    private float startX,startY;
    private boolean cancelled;
    private final Rect bounds=new Rect();
    public static View button() { return target.get(); }
    public static void setTarget(View view) { target=new WeakReference<>(view); }
    public static void install(XposedInterface api,ClassLoader loader) {
        if(installed) return;
        try {
            Class<?> type=Class.forName("com.android.systemui.shade.NotificationShadeWindowView",false,loader);
            Method method=type.getDeclaredMethod("dispatchTouchEvent",MotionEvent.class);
            api.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(new IosShadeClearGesture());
            installed=true;
            Log.i("HyperChangerIosShadeFix","clear gesture root hook ready");
        } catch(ReflectiveOperationException error) { Log.w("HyperChangerIosShadeFix","clear gesture root unavailable",error); }
    }
    private boolean visible(View view) {
        if(view==null || !view.isAttachedToWindow() || !view.isShown() || !view.isEnabled()) return false;
        for(View current=view; current!=null;) {
            if(current.getAlpha()<0.1f) return false;
            ViewParent parent=current.getParent(); current=parent instanceof View?(View)parent:null;
        }
        return true;
    }
    @Override public Object intercept(XposedInterface.Chain chain) throws Throwable {
        MotionEvent event=(MotionEvent)chain.getArg(0);
        int action=event.getActionMasked();
        View view=target.get();
        if(action==MotionEvent.ACTION_DOWN) {
            View old=pressed.get(); if(old!=null) old.setPressed(false);
            pressed=new WeakReference<>(null); pointer=-1;
            if(!(chain.getThisObject() instanceof View) || !visible(view)
                || ((View)chain.getThisObject()).getRootView()!=view.getRootView()
                || !view.getGlobalVisibleRect(bounds) || !bounds.contains((int)event.getRawX(),(int)event.getRawY())) return chain.proceed();
            pressed=new WeakReference<>(view); pointer=event.getPointerId(0);
            startX=event.getRawX(); startY=event.getRawY(); cancelled=false;
            view.setPressed(true);
            Log.i("HyperChangerIosShadeFix","clear gesture captured at shade root");
            return true;
        }
        View button=pressed.get();
        if(pointer<0 || button==null) return chain.proceed();
        if(event.getPointerCount()>1 || action==MotionEvent.ACTION_CANCEL || !visible(button) || button!=view) cancelled=true;
        int slop=ViewConfiguration.get(button.getContext()).getScaledTouchSlop();
        if(Math.abs(event.getRawX()-startX)>slop || Math.abs(event.getRawY()-startY)>slop) cancelled=true;
        if(cancelled) button.setPressed(false);
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL) {
            boolean click=action==MotionEvent.ACTION_UP && !cancelled;
            pointer=-1; pressed=new WeakReference<>(null); button.setPressed(false);
            if(click) button.performClick();
        }
        return true;
    }
}

