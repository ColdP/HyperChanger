package io.github.libxposed.api;
import java.lang.reflect.Method; import java.util.*; import java.util.function.Consumer;
public class XposedInterface {
 public enum ExceptionMode { PROTECTIVE }
 public interface Hooker { Object intercept(Chain c) throws Throwable; }
 static final Map<String,Hooker> hooks=new HashMap<>();
 public Builder hook(Method m){return new Builder(m.getName());}
 public static class Builder { String name; Builder(String n){name=n;} public Builder setExceptionMode(ExceptionMode x){return this;} public void intercept(Hooker h){hooks.put(name,h);} }
 public static class Chain { Object view,arg; Consumer<Object> nativeWrite; Chain(Object v,Object a,Consumer<Object> w){view=v;arg=a;nativeWrite=w;} public Object getThisObject(){return view;} public List<Object> getArgs(){return Arrays.asList(arg);} public Object proceed(){nativeWrite.accept(arg);return null;} public Object proceed(Object[] args){nativeWrite.accept(args[0]);return null;} }
 public static void dispatch(Object v,String n,Object a,Consumer<Object>w){ try{Hooker h=hooks.get(n); if(h==null)w.accept(a);else h.intercept(new Chain(v,a,w));}catch(Throwable t){throw new RuntimeException(t);} }
}