package com.android.systemui.statusbar.notification.mediacontrol;
import android.view.*;
public class MiuiMediaHeaderView extends ViewGroup {
 public static class Holder { public View mediaBg = new View(); }
 public final Holder holder = new Holder();
 public MiuiMediaHeaderView(){addView(holder.mediaBg);}
 public Holder getMediaViewHolder(){return holder;}
}
