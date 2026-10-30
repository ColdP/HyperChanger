package android.view; import io.github.libxposed.api.XposedInterface; public class View {
 public boolean failTypeRead, failModeRead, failRadiusRead, failPassRead, failContainRead;
 public int id=-1;
 public View getParent(){return parent;}
 public View parent; public int mode=0,type=2,radius=0,viewMode=1,writes=0; public boolean pass=true,disabled=false;
 // Model ViewRootImpl's pass-window texture map separately from the View's flags.
 // A disabled mode can retain pass=true while its texture entry is invisible.
 public boolean textureRegistered=true,textureVisible=false;
 public View getRootView(){return parent==null?this:parent.getRootView();} public boolean isShown(){return true;} public int getId(){return id;} public android.content.res.Resources getResources(){return new android.content.res.Resources();}
 public void setMiBackgroundBlurMode(int v){XposedInterface.dispatch(this,"setMiBackgroundBlurMode",v,a->{int next=(Integer)a;if(mode!=next){if(pass){textureRegistered=true;textureVisible=next!=0;}mode=next;}writes++;});} public int getMiBackgroundBlurMode(){if(failModeRead)throw new UnsupportedOperationException();return mode;}
 public void setMiBackgroundBlurType(int v){XposedInterface.dispatch(this,"setMiBackgroundBlurType",v,a->{type=(Integer)a;writes++;});} public int getMiBackgroundBlurType(){if(failTypeRead)throw new UnsupportedOperationException();return type;}
 public void setMiBackgroundBlurRadius(int v){XposedInterface.dispatch(this,"setMiBackgroundBlurRadius",v,a->{radius=(Integer)a;writes++;});} public int getMiBackgroundBlurRadius(){if(failRadiusRead)throw new UnsupportedOperationException();return radius;}
 public void setPassWindowBlurEnabled(boolean v){XposedInterface.dispatch(this,"setPassWindowBlurEnabled",v,a->{boolean next=(Boolean)a;if(pass!=next){textureRegistered=next;textureVisible=next;pass=next;}else if(next&&!textureRegistered){textureRegistered=true;textureVisible=true;}writes++;});} public boolean getPassWindowBlurEnabled(){if(failPassRead)throw new UnsupportedOperationException();return pass;}
 public void disableMiBackgroundContainBelow(boolean v){XposedInterface.dispatch(this,"disableMiBackgroundContainBelow",v,a->{disabled=(Boolean)a;writes++;});} public boolean getMiBackgroundContainBelow(){if(failContainRead)throw new UnsupportedOperationException();return !disabled;}
 public void setMiViewBlurMode(int v){XposedInterface.dispatch(this,"setMiViewBlurMode",v,a->viewMode=(Integer)a);}
}
