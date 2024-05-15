#### 前言

WMS（WindowManagerService）是继AMS,PMS之后一个非常复杂又非常重要的系统服务，主要负责管理系统中所有的窗口。WMS错综复杂，要一篇文章分析透彻是比较困难的，因为其与AMS，InputManagerService，SurfaceFlinger关系紧密，本文只关注跟WMS相关的：WMS启动过程，添加窗口过程。显示是由SurfaceFlinger负责，响应用户操作是由InputManager负责。代码基于Android13。

#### 一些基本概念

先有个模糊的过程：用户定义的layout在启动activity时通过setContentView加载，此过程会把xml布局“翻译”成具体的view，客户端通知WMS添加此view，WMS构建个此“窗口”，这里有可能有多个应用同时显示，比如状态栏，导航栏，所以wms可能同时构建几个要显示的“窗口”，就需要把这几个窗口做个合并，哪个窗口再哪里显示，拼接出来的数据再通知SurfaceFlinger去显示。

上面简化的流程是为了引出一些基本的概念。

+ Window
+ Session
+ WindowToken
+ DisplayContent
+ WindowState
+ 主序、子序、窗口类型

##### Window

Android系统中的窗口是屏幕上一块用户绘制各种UI元素并响应用户输入的一块矩形区域，从原理上来讲，窗口是独自占有一块Surface实例的显示区域，例如，activity，dialog，壁纸，状态栏，toast等都是窗口。（Surface可以理解成一块画布，应用可以通过Canvas或OpenGL在上作画，再通过SurfaceFlinger将多块Surface按特定是顺序（z-order）合并后输出到FrameBuffer，再通过屏幕驱动显示到屏幕）

窗口是个抽象类，实现类是PhoneWindow，是所有View的直接管理者，客户端把xml布局翻译成view后嵌到以content为id的DecorView中，PhoneWindow管理此DecorView，所以事件传递也是由window-> DecorView>具体的view。

```java
//Activity.java
final void attach(){
    //...
    mWindow = new PhoneWindow(this, window, activityConfigCallback); 
    mWindow.setWindowManager(...);
    //...
}
//Window.java
public abstract class Window {
    public void setWindowManager(WindowManager wm, IBinder appToken, String appName) {
        setWindowManager(wm, appToken, appName, false);
    }
    public void setWindowManager(WindowManager wm, IBinder appToken, String appName,boolean hardwareAccelerated) {
        if (wm == null) {
            wm = (WindowManager)mContext.getSystemService(Context.WINDOW_SERVICE);
        }
        mWindowManager = ((WindowManagerImpl)wm).createLocalWindowManager(this);
    }
}
//WindowManagerImpl.java
public WindowManagerImpl createLocalWindowManager(Window parentWindow) {
    return new WindowManagerImpl(mContext, parentWindow, mWindowContextToken);
}
//调用activity.setContentView时
//PhoneWindow.java
public void setContentView(int layoutResID) {
    installDecor();
    //...
}
 private void installDecor() {
     //...
     //生成DecorView
      mDecor = generateDecor(-1);
     //根据decorview生成具体的容器
      mContentParent = generateLayout(mDecor);
     //...
 }
protected ViewGroup generateLayout(DecorView decor) {
    //...
    //com.android.internal.R.id.content，即以content为key的viewgrop
    ViewGroup contentParent = (ViewGroup)findViewById(ID_ANDROID_CONTENT);
    //...
}
```

PhoneWindow的创建是在Activity的attach里，并调用其setWindowManager去创建WindowManagerImpl，此WindowManagerImpl持有个全局的单例WindowManagerGlobal，WindowManagerGlobal里持有WMS的代理对象，调用其addview时会创建ViewRootImpl，并通过wms.openSession创建跟应用的Session，一个应用只有一个session。

这里对应关系如下：

Activity <一对一> PhoneWindow <一对一>WindowManagerImpl<多对一> WindowManagerGlobal<一对多>ViewRootImpl<多对一>Session

