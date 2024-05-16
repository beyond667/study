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

Activity <一对一> PhoneWindow <一对一>WindowManagerImpl<多对一> WindowManagerGlobal

Activity <一对一> PhoneWindow <一对一>ViewRootImpl<多对一>WindowManagerGlobal

```java
//ActivityThread.java 执行handleResumeActivity时
public void handleResumeActivity(ActivityClientRecord r...) {
    //通过PhoneWindow拿DecorView，此时还未绘制先让其不可见，设置窗口类型为TYPE_BASE_APPLICATION
    r.window = r.activity.getWindow();
    View decor = r.window.getDecorView();
    decor.setVisibility(View.INVISIBLE);
    ViewManager wm = a.getWindowManager();
    WindowManager.LayoutParams l = r.window.getAttributes();
    a.mDecor = decor;
    l.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
    l.softInputMode |= forwardBit;
	//调用WindowManagerImpl.addView,WindowManagerImpl又调WindowManagerGlobal.addView
    if (!a.mWindowAdded) {
        a.mWindowAdded = true;
        wm.addView(decor, l);
    }
}
//WindowManagerGlobal.java
public void addView(View view... Window parentWindow...) {
    //...
	//1 PhoneWindow在这里把具体的配置存到LayoutParams里
    final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams) params;
    if (parentWindow != null) {
        parentWindow.adjustLayoutParamsForSubWindow(wparams);
    }
    
    ViewRootImpl root;
    root = new ViewRootImpl(view.getContext(), display);

    //2 WindowManagerGlobal里缓存了该应用所有的decorview，viewrootimpl，LayoutParams
    mViews.add(view);
    mRoots.add(root);
    mParams.add(wparams);
    //3 调用viewrootimpl的setview调用wms添加窗口
    root.setView(view, wparams, panelParentView, userId);
}
//ViewRootImpl.java
final IWindowSession mWindowSession;
public ViewRootImpl(Context context, Display display) {
    //WindowManagerGlobal单例获取该应用唯一的Session，记录到本地mWindowSession中
    //关于Session看下一小节
    this(context, display, WindowManagerGlobal.getWindowSession(), new WindowLayout());
}
public void setView(View view, WindowManager.LayoutParams attrs...) {
	//...
    res = mWindowSession.addToDisplayAsUser(mWindow, mWindowAttributes...);
    //...
}
```
WindowManagerGlobal.addView方法比较重要，这里简单了解下其做的3件事

+ 注释1把PhoneWindow里的数据先填充到LayoutParams，在注释3调用ViewRootImpl时把此参数传到wms，以供WMS服务端创建对应的"窗口"数据
+ 注释2WindowManagerGlobal缓存了该应用所有的decorView，ViewRootImpl，LayoutParams，可以理解成WindowManagerGlobal是客户端所有view的大管家
+ 注释3调用viewrootimpl的setview到wms添加窗口，后面细讲

客户端的Window信息就存到了LayoutParams，并通过session调用到服务端WMS去管理

##### Session

由以上可知，ViewRootImpl和WMS就是通过Session来完成通信的，一个应用只有一个Session与WMS通信。

```java
//WindowManagerGlobal.java
public static IWindowSession getWindowSession() {
    synchronized (WindowManagerGlobal.class) {
        if (sWindowSession == null) {
            IWindowManager windowManager = getWindowManagerService();
            sWindowSession = windowManager.openSession(
                new IWindowSessionCallback.Stub() {
                    @Override
                    public void onAnimatorScaleChanged(float scale) {
                        ValueAnimator.setDurationScale(scale);
                    }
                });

        }
        return sWindowSession;
    }
}
```

ViewRootImpl里获取的Session是单例唯一的，并且是通过WMS.openSession来创建的。

```java
//WindowManagerService.java
//WMS缓存了所有的session信息，在调用wms.addWindow时会添加进此列表，后面分析
final ArraySet<Session> mSessions = new ArraySet<>();
public IWindowSession openSession(IWindowSessionCallback callback) {
    return new Session(this, callback);
}
```

Session肯定是个binder对象，要把代理传给ViewRootImpl调用的

> frameworks/base/services/core/java/com/android/server/wm/Session.java

```java
class Session extends IWindowSession.Stub implements IBinder.DeathRecipient {
    final WindowManagerService mService;
    final IWindowSessionCallback mCallback;
    public Session(WindowManagerService service, IWindowSessionCallback callback){
        mService = service;
        mCallback = callback;
        //...
        //监听客户端binder是否死掉，死的话需要关闭此Session
        mCallback.asBinder().linkToDeath(this, 0);
    }

    @Override
    public void binderDied() {
        synchronized (mService.mGlobalLock) {
            mClientDead = true;
            killSessionLocked();
        }
    }
    //关闭此Session时把WMS里缓存的此session删除
    private void killSessionLocked() {
 		//...
        mService.mSessions.remove(this);
    }
    //调用到WMS.addWindow
    public int addToDisplayAsUser(IWindow window, WindowManager.LayoutParams attrs...) {
        return mService.addWindow(this, window, attrs...);
    }
}
```

以上可知，Session可以理解成客户端与wms沟通的“会话”，一个应用只有一个Session与WMS通信。

##### WindowToken

一个WindowToken就代表一个应用组件，应用组件包括Activity，InputMethod等。比如一个应该启动了两个Activity，这两个Activity在WMS里就有两个WindowToken，这样在WMS对窗口进行ZOrder排序时，会将同一个应用的WindowToken排在一起。另外，WindowToken还有令牌的作用，在WMS.addWindow时会根据应用传过来的windowToken来鉴权，传进来的WindowToken必须与该应用的窗口类型必须保持一致，比如一个普通应用addWindow时传过来的WindowToken是系统类型的窗口，或者直接不传WindowToken都会报错。如果是系统类型的窗口，可以不用提供WindowToken，WMS会为该系统创建，但是需要此应用有创建该类型窗口的权限。

```java
//WMS.java
public int addWindow(Session session, IWindow client, LayoutParams attrs...){
    //...
    //1 如果有父窗口，就用父窗口的窗口参数，比如windowToken和窗口类型type
    WindowToken token = displayContent.getWindowToken(hasParent ? parentWindow.mAttrs.token : attrs.token);
    final int rootType = hasParent ? parentWindow.mAttrs.type : type;
    if (token == null) {
        //如果token没传，通过unprivilegedAppCanCreateTokenWith去判断是不是系统指定的窗口
        if (!unprivilegedAppCanCreateTokenWith(parentWindow, callingUid, type,rootType, attrs.token, attrs.packageName)) {
            return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
        }
        //是系统窗口，如果有父窗口用父窗口的token，否则创建一个WindowToken
        if (hasParent) {
            token = parentWindow.mToken;
        } else if (mWindowContextListenerController.hasListener(windowContextToken)) {
             //...
        } else {
            final IBinder binder = attrs.token != null ? attrs.token : client.asBinder();
            token = new WindowToken.Builder(this, binder, type)
                .setDisplayContent(displayContent)
                .setOwnerCanManageAppTokens(session.mCanAddInternalSystemWindow)
                .setRoundedCornerOverlay(isRoundedCornerOverlay)
                .build();
        }
    } else if (rootType >= FIRST_APPLICATION_WINDOW && rootType <= LAST_APPLICATION_WINDOW) {
        //...
    } else if (rootType == TYPE_INPUT_METHOD) {
        //...
    } else if (rootType == TYPE_WALLPAPER) {
        //...
    } else if (rootType == TYPE_ACCESSIBILITY_OVERLAY) {
        //...
    } else if (type == TYPE_TOAST) {
        //...
    } 
    
    //2 创建WindowState时把token传过去，记录到其mToken变量
    final WindowState win = new WindowState(this, session, client, token...);
    //...
    //3 WindowToken作为容器添加此WindowState
    win.mToken.addWindow(win);
    //...
}

private boolean unprivilegedAppCanCreateTokenWith(int rootType...) {
    //如果是应用窗口，输入框窗口，壁纸窗口，直接返回false，这类型的窗口需要传windowtoken
    if (rootType >= FIRST_APPLICATION_WINDOW && rootType <= LAST_APPLICATION_WINDOW) {
        return false;
    }
    if (rootType == TYPE_INPUT_METHOD) {
        return false;
    }
    if (rootType == TYPE_WALLPAPER) {
        return false;
    }
    //...
    return true;
}
```

WMS.addWindow是view绘制很重要的一个方法，这里暂只关注跟WindowToken相关。

+ 注释1处通过displayContent去获取windowToken，如果没传，需判断是否是系统窗口，如果是的话就创建个WindowToken，如果不是就直接报错
+ 注释2创建WindowState，我们后面重点讲这个。这里把WindowToken传进去，记录到其mToken变量里
+ 注释3把WindowToken作为容器添加了WindowState

那我们再看看WindowToken.java

```java
//WindowToken.java
//继承了window容器，并且容器装的是WindowState
class WindowToken extends WindowContainer<WindowState> {
    final IBinder token;
    final int windowType;
    protected DisplayContent mDisplayContent;
    protected WindowToken(WindowManagerService service, IBinder _token, int type,DisplayContent dc...) {
        super(service);
        token = _token;
        windowType = type;
        mOptions = options;
        mPersistOnEmpty = persistOnEmpty;
        mOwnerCanManageAppTokens = ownerCanManageAppTokens;
        mRoundedCornerOverlay = roundedCornerOverlay;
        mFromClientToken = fromClientToken;
        if (dc != null) {
            dc.addWindowToken(token, this);
        }
    }
}
//DisplayContent.java
void addWindowToken(IBinder binder, WindowToken token) {
    //...
    mTokenMap.put(binder, token);
}
```

从WindowToken类的定义看，其本质是个装WindowToken的容器，内部持有了wms和token，从其构造函数可知，token本质就是个binder对象，并通过DisplayContent.addWindowToken在DisplayContent中记录了token和WindowToken的对应关系。这里传进来的binder本质上是客户端ViewRootImpl.setView时通过Session.addToDisplayAsUser传过来的IWindow.Stub对象

```
//
```



