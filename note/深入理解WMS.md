#### 前言

WMS（WindowManagerService）是继AMS,PMS之后一个非常复杂又非常重要的系统服务，主要负责管理系统中所有的窗口。WMS错综复杂，要一篇文章分析透彻是比较困难的，因为其与AMS，InputManagerService，SurfaceFlinger关系紧密，本文只关注跟WMS相关的：WMS启动过程，添加窗口过程。显示是由SurfaceFlinger负责，响应用户操作是由InputManager负责。代码基于Android13。

#### 一些基本概念

先有个模糊的过程：用户定义的layout在启动activity时通过setContentView加载，此过程会把xml布局“翻译”成具体的view，客户端通知WMS添加此view，WMS构建个此“窗口”，这里有可能有多个应用同时显示，比如状态栏，导航栏，所以wms可能同时构建几个要显示的“窗口”，就需要把这几个窗口做个合并，哪个窗口再哪里显示，拼接出来的数据再通知SurfaceFlinger去显示。

上面简化的流程是为了引出一些基本的概念。  

如果本身就了解概念的，可以直接跳过此节，到WMS启动流程。

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
    //指定activity的窗口类型为1
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
View mView;
public ViewRootImpl(Context context, Display display) {
    //WindowManagerGlobal单例获取该应用唯一的Session，记录到本地mWindowSession中
    //关于Session看下一小节
    this(context, display, WindowManagerGlobal.getWindowSession(), new WindowLayout());
}
public void setView(View view, WindowManager.LayoutParams attrs...) {
    //这个view也就是DecorView，一个ViewRootImpl对应一个DecorView
    mView = view;
	//...
    // Schedule the first layout -before- adding to the window
    // manager, to make sure we do the relayout before receiving
    // any other events from the system.
    // 添加窗口前先完成第一次layout布局，以确保收到任何系统事件后重新布局，
    // 此方法最终会调performTraversals来完成view绘制（view绘制的入口）
    requestLayout();
    //...
    // 调用session.addToDisplayAsUser通知wms添加窗口
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

一个WindowToken就代表一个应用组件，应用组件包括Activity，InputMethod等。比如一个应该启动了两个Activity，这两个Activity在WMS里就有两个WindowToken，这样在WMS对窗口进行ZOrder排序时，会将同一个WindowToken包的WindowState排在一起。另外，WindowToken还有令牌的作用，在WMS.addWindow时会根据应用传过来的windowToken来鉴权，传进来的WindowToken必须与该应用的窗口类型必须保持一致，比如一个普通应用addWindow时传过来的WindowToken是系统类型的窗口，或者直接不传WindowToken都会报错。如果是系统类型的窗口，可以不用提供WindowToken，WMS会为该系统创建，但是需要此应用有创建该类型窗口的权限。

```java
//WMS.java
public int addWindow(Session session, IWindow client, LayoutParams attrs...){
    //...
    //1 如果有父窗口，就用父窗口的窗口参数，比如windowToken和窗口类型type
    WindowToken token = displayContent.getWindowToken(hasParent ? parentWindow.mAttrs.token : attrs.token);
    final int rootType = hasParent ? parentWindow.mAttrs.type : type;
    if (token == null) {
        //如果从displayContent拿不到WindowToken，通过unprivilegedAppCanCreateTokenWith去判断是不是系统指定的窗口
        if (!unprivilegedAppCanCreateTokenWith(parentWindow, callingUid, type,rootType, attrs.token, attrs.packageName)) {
            return WindowManagerGlobal.ADD_BAD_APP_TOKEN;
        }
        //是系统窗口，如果有父窗口用父窗口的token，否则创建一个WindowToken
        if (hasParent) {
            token = parentWindow.mToken;
        } else if (mWindowContextListenerController.hasListener(windowContextToken)) {
             //...
        } else {
            //2 为系统窗口创建windowToken时如果LayoutParams里有token就用LayoutParams里的，没有的话就用Iwindow这个binder的
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
    
    //3 创建WindowState时把windowToken传过去，记录到其mToken变量
    final WindowState win = new WindowState(this, session, client, token...);
    //...
    //4 WindowToken作为容器添加此WindowState
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
+ 注释2在为系统窗口创建windowToken容器时，如果LayoutParams里有token（binder）就用，没有的话就用IWindow的binder
+ 注释3创建WindowState，我们后面重点讲这个。这里把WindowToken传进去，记录到其mToken变量里
+ 注释4把WindowToken作为容器添加了WindowState

根据注释4可猜测，WindowToken其实本质就是个装WindowState的容器，WindowToken里面持有的token其实就是binder对象。再根据注释1推测displayContent里有以binder为key，WindowToken为value的缓存

那我们基于以上猜测来看看WindowToken和DisplayContent

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
WindowToken getWindowToken(IBinder binder) {
    return mTokenMap.get(binder);
}
```

从WindowToken类的定义看，其本质是个装WindowState的容器，从其构造函数可知，内部持有的token本质就是个binder对象，并通过DisplayContent.addWindowToken在DisplayContent中记录了token和WindowToken的对应关系。

对于应用来说，WindowToken其实就是ActivityRecord（其继承于WindowToken），代表了客户端一个具体的Activity。

DisplayContent.addWindowToken添加binder和WindowToken是在启动应用时，调用流程如下：

```java
ActivityStarter.startActivityUnchecked -> startActivityInner  -> addOrReparentStartingActivity  ->Task.addChild -> TaskFragment.addChild  -> WindowContainer.addChild ->  WindowContainer.setParent->ActivityRecord.onDisplayChanged -> WindowToken.onDisplayChanged->DisplayContent.reParentWindowToken ->DisplayContent.addWindowToken
```

调用流程的代码不再细看，我们关注的是DisplayContent.addWindowToken时填加的token和windowToken。

```java
//ActivityRecord.java
//继承WindowToken
public final class ActivityRecord extends WindowToken {
    private ActivityRecord(ActivityTaskManagerService _service, WindowProcessController _caller...){
        //添加到DisplayContent里的token，其实就是这里随意new的一个本地binder，里面啥也没，其实就是作为此ActivityRecord的一个凭证，wms就可以通过这个binder找到此WindowToken，也就是此ActivityRecord
        super(_service.mWindowManager, new Token(), TYPE_APPLICATION, true,null, false );
    }
    private static class Token extends Binder {
        public String toString() {
            return "Token{...}";
        }
    }
}

//父类WindowToken.java
final IBinder token;
protected WindowToken(WindowManagerService service, IBinder _token...){
    token = _token;
    //...
}

//DisplayContent.java
private final HashMap<IBinder, WindowToken> mTokenMap = new HashMap();
void reParentWindowToken(WindowToken token) {
    final DisplayContent prevDc = token.getDisplayContent();
    if (prevDc == this) {
        return;
    }
    //1 这里往DisplayContent里绑定了token和WindowToken
    addWindowToken(token.token, token);
}
void addWindowToken(IBinder binder, WindowToken token) {
    //...
    mTokenMap.put(binder, token);
}
```

从注释1可以看到ActivityRecord里的token作为key，ActivityRecord这个WindowToken作为value存进了DisplayContent里。

综上，一个activity对应服务端的一个ActivityRecord（WindowToken），打开多个Activity就有多个ActivityRecord。如果在某个Activity里弹dialog时，此dialog会用此Activity的WindowToken，在wms.addWindow时会创建此dialog的WindowState，把此WindowState添加到依附的WindowToken里，所以WindowToken本质是个WindowState的容器，负责装WindowState，在绘制时会根据此容器的所有的WindowState来组合显示。

##### DisplayContent

上面WindowToken如果理解的话，DisplayContent和WindowState就很好理解了。

DisplayContent是Android4.2为支持多屏幕显示而提出的概念，一个DisplayContent对象就代表了一块屏幕信息，属于同一个DisplayContent的window对象会被绘制在同一块屏幕上，在添加窗口时可以指定要添加到哪个DisplayContent对应的id里，即在哪块屏幕显示。虽然手机只有一个显示屏，但是可以创建多个DisplayContent对象，比如投屏时可以创建一个虚拟的DisplayContent。

DisplayContent的初始化调用流程如下：

SystemServer.startOtherServices -> ams.setWindowManager -> ams.setWindowManager -> RootWindowContainer.setWindowManager->new DisplayContent(display,this)

```java
//RootWindowContainer.java
public class RootWindowContainer extends WindowContainer<DisplayContent>{
    void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
        mDisplayManager = mService.mContext.getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(this, mService.mUiHandler);
        //1 通过DisplayManager获取所有的Display
        final Display[] displays = mDisplayManager.getDisplays();
        for (int displayNdx = 0; displayNdx < displays.length; ++displayNdx) {
            final Display display = displays[displayNdx];
            //2 基于display和RootWindowContainer创建DisplayContent
            final DisplayContent displayContent = new DisplayContent(display, this);
            //3 把displayContent添加到本容器中
            addChild(displayContent, POSITION_BOTTOM);
            if (displayContent.mDisplayId == DEFAULT_DISPLAY) {
                mDefaultDisplay = displayContent;
            }
        }
        //...
    }
}

//父类WindowContainer.java
// List of children for this window container. List is in z-order as the children appear on
// screen with the top-most window container at the tail of the list.
protected final WindowList<E> mChildren = new WindowList<E>();
void addChild(E child, int index) {
    //...
    mChildren.add(index, child);
}
//WindowList.java 
class WindowList<E> extends ArrayList<E> {}
```

+ 注释1通过displayManager去获取所有的display信息，只有一个显示的话只返回一个id为0的display对象，用户也可以在同一块屏幕上创建虚拟的display

+ 注释2基于display和本容器创建DisplayContent对象
+ 注释3把displayContent添加到本容器，RootWindowContainer理解成一个装DisplayContent的容器

基于以上可以认为，如果有多块屏幕，肯定拿到的是多个display，但是即使只有一块屏幕，也有可能创建多个display，一个display关联一个displayContent，并且都加到同一个RootWindowContainer容器里。其实多屏显示就是通过DisplayManager去管理多个display，关于多屏幕显示会在后面再研究。

再看DisplayContent的构造函数

> frameworks/base/services/core/java/com/android/server/wm/DisplayContent.java

```java
DisplayContent(Display display, RootWindowContainer root) {
    mRootWindowContainer = root;
    mAtmService = mWmService.mAtmService;
    mDisplay = display;
    mDisplayId = display.getDisplayId();
    //display id为0代表默认显示屏
    isDefaultDisplay = mDisplayId == DEFAULT_DISPLAY;
    if (isDefaultDisplay) {
        mWmService.mPolicy.setDefaultDisplay(this);
    }
    //...
    //显示策略
    mDisplayPolicy = new DisplayPolicy(mWmService, this);
    //旋转角度，可以控制显示横竖屏
    mDisplayRotation = new DisplayRotation(mWmService, this);
   //...
}
```

可以看到DisplayContent其实就是对Display做了包装，控制该display的所有信息，比如显示策略，旋转角度，以及在此屏幕上要显示的所有的WindowToken等。

##### WindowState

WindowState表示一个窗口的所有属性，是WMS事实上的窗口。

我们继续看下wms.addWindow中关于WindowState的部分

```java
//wms.java
final HashMap<IBinder, WindowState> mWindowMap = new HashMap<>();
public int addWindow(Session session, IWindow client, LayoutParams attrs){
    //...省略前面以及WindowToken部分，可以看上面WindowToken部分
    //1 基于session，windowtoken和IWindow等初始化windowState
    final WindowState win = new WindowState(this, session, client, token, parentWindow,
                                            appOp[0], attrs, viewVisibility, session.mUid, userId,
                                            session.mCanAddInternalSystemWindow);
    
    //2 内部通过Session把记录的窗口数加1
    win.attach();
    //3 wms也缓存了binder和windowState
    mWindowMap.put(client.asBinder(), win);
    
    //在windowToken小节已经解释了，把WindowState的容器WindowToken添加新new出来的WindowState
    //其实就是WindowState和WindowToken进行了双向绑定，既可以通过WindowState获取WindowToken容器
    //也可以根据WindowToken去遍历所有的WindowState
    win.mToken.addWindow(win);
    
    //设置窗口动画
    final WindowStateAnimator winAnimator = win.mWinAnimator;
    winAnimator.mEnterAnimationPending = true;
    winAnimator.mEnteringAnimation = true;

    //处理输入和焦点
    displayContent.getInputMonitor().setUpdateInputWindowsNeededLw();
    boolean focusChanged = false;
    if (win.canReceiveKeys()) {
        focusChanged = updateFocusedWindowLocked(UPDATE_FOCUS_WILL_ASSIGN_LAYERS,false);
        if (focusChanged) {
            imMayMove = false;
        }
    }
    //...到这里基本上WMS.addWindow大部分工作都做完了
}
```

+ 注释1在new WindowState时是基于session，windowtoken和IWindow等来初始化的
+ 注释2调用WindowState通知session把窗口数加1
+ 注释3 wms也缓存了IWindow这个binder和WindowState的映射关系

再看WindowState

```java
//WindowState.java
WindowState(WindowManagerService service, Session s, IWindow c, WindowToken token...){
    mSession = s;
    mClient = c;
    mToken = token;
    DeathRecipient deathRecipient = new DeathRecipient();
    c.asBinder().linkToDeath(deathRecipient, 0);
    //基于窗口类型计算主序，子序
    if (mAttrs.type >= FIRST_SUB_WINDOW && mAttrs.type <= LAST_SUB_WINDOW) {
        mBaseLayer = mPolicy.getWindowLayerLw(parentWindow)
            * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
        mSubLayer = mPolicy.getSubWindowLayerFromTypeLw(a.type);
    } else {
        mBaseLayer = mPolicy.getWindowLayerLw(this)
            * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
        mSubLayer = 0;
    }
    mWinAnimator = new WindowStateAnimator(this);
    mWinAnimator.mAlpha = a.alpha;
}
void attach() {
    mSession.windowAddedLocked();
}

//Session.java
private int mNumWindow = 0;
void windowAddedLocked() {
    //从这里可以看到只有应用第一次添加窗口时才会创建一次SurfaceSession
    if (mSurfaceSession == null) {
        //1 这里会创建SurfaceSession
        mSurfaceSession = new SurfaceSession();
        mService.mSessions.add(this);
        if (mLastReportedAnimatorScale != mService.getCurrentAnimatorScale()) {
            mService.dispatchNewAnimatorScaleLocked(this);
        }
    }
    mNumWindow++;
}
void windowRemovedLocked() {
    mNumWindow--;
    killSessionLocked();
}
private void killSessionLocked() {
    //只要窗口数大于0就不清除此Session
    if (mNumWindow > 0 || !mClientDead) {
        return;
    }
    mService.mSessions.remove(this);
    //...
}
```

WindowState的构造函数中绑定了Iwindow，Session，WindowToken，并计算了窗口的主序和子序等。

注释1在调用WindowState的attach方法时，会在Session里把窗口数加1，如果是应用首次添加窗口，会在native层创建Surface，关于SurfaceFlinger的后面讲的时候会把链接放这里。

另需注意WindowState里绑的IWindow其实是客户端的IWindow.Stub

```java
//ViewRootImpl.java
final W mWindow;
public void setView(...){
    //...
    mWindowSession.addToDisplayAsUser(mWindow,...);
    //...
}
static class W extends IWindow.Stub {
    private final WeakReference<ViewRootImpl> mViewAncestor;
    private final IWindowSession mWindowSession;

    W(ViewRootImpl viewAncestor) {
        mViewAncestor = new WeakReference<ViewRootImpl>(viewAncestor);
        mWindowSession = viewAncestor.mWindowSession;
    }
    public void resized(){}
    public void showInsets(){}
    public void hideInsets(){}
}
```

从上面可以看到IWindow其实就是客户端的IWindow.Stub这个binder的代理端，WMS拿到此binder方便操作客户端的“窗口”，W内部是持有了ViewRootImpl的弱引用，所以本质上是WMS通过操作WindowState里的IWindow这个binder，来控制客户端的ViewRootImpl，ViewRootImpl持有了DecorView，后续的窗口resize，事件分发等就通过ViewRootImpl来控制。

##### 主序，子序，窗口类型

> 手机屏幕以左上角为原点，向右为X轴方向，向下为Y轴方向，为方便窗口管理的显示次序，手机的屏幕被扩展了一个三维的空间，即多定义了一个Z轴，其方向为垂直于屏幕指向屏幕外，多个窗口按照前后顺序排列在这个虚拟的Z轴上，此窗口的显示次序又被称为Z序（Z-Order）

在上面WindowState构造函数中根据窗口类型计算了主序，子序

```java
//基于窗口类型计算主序，子序 TYPE_LAYER_MULTIPLIER==10000 TYPE_LAYER_OFFSET==1000
if (mAttrs.type >= FIRST_SUB_WINDOW && mAttrs.type <= LAST_SUB_WINDOW) {
    //如果是子窗口，就用父窗口的主序  
    mBaseLayer = mPolicy.getWindowLayerLw(parentWindow)
        * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
    //根据窗口类型获取子序
    mSubLayer = mPolicy.getSubWindowLayerFromTypeLw(a.type);
} else {
    //非子窗口，就根据窗口类型来计算主序
    mBaseLayer = mPolicy.getWindowLayerLw(this)
        * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
    //子序设为0
    mSubLayer = 0;
}
```

这里牵涉到3个概念，窗口类型，主序，子序。

窗口分为以下几类：

+ 应用窗口（1-99）：常见的Activity窗口，dialog即属于此区间（已验证，在同一activity弹dialog，activity的窗口类型为1，dialog为2，popupwindow为1000）
+ 子窗口（1000-1999）：PopupWindow，ContextMenu即属于此区间。子窗口不能独立存在，必须依附主窗口
+ 系统窗口（2000-2999）：状态栏，导航栏，输入法，壁纸，Toast等系统窗口。

一般来说，窗口的层级越大的显示在最顶部，但是也不绝对，比如系统的壁纸窗口就在底部。

> frameworks/base/core/java/android/view/WindowManager.java

```java
//WindowManager内部类LayoutParams
public static class LayoutParams extends ViewGroup.LayoutParams implements Parcelable {
    //应用窗口1-99
    //应用的base窗口，主要就是Activity
    public static final int TYPE_BASE_APPLICATION   = 1;
    //普通应用窗口，但是必须要指定此窗口属于哪个activity token。Dialog默认会用此窗口类型
    public static final int TYPE_APPLICATION        = 2;
    //为应用启动时显示的特殊窗口，其实主要为了Anroid12后提出的Splash Screen功能
    public static final int TYPE_APPLICATION_STARTING = 3;
    //应用窗口的结束窗口
    public static final int LAST_APPLICATION_WINDOW = 99;
    
    //子窗口1000-1999
    //子窗口的开始标记。PopupWindow即属于此
    public static final int FIRST_SUB_WINDOW = 1000;
    //后面都是子窗口的一些扩展窗口。比如子窗口的panel，media窗口
    public static final int TYPE_APPLICATION_PANEL = FIRST_SUB_WINDOW;
    public static final int TYPE_APPLICATION_MEDIA = FIRST_SUB_WINDOW + 1;
    public static final int TYPE_APPLICATION_SUB_PANEL = FIRST_SUB_WINDOW + 2;
    public static final int TYPE_APPLICATION_ATTACHED_DIALOG = FIRST_SUB_WINDOW + 3;
    public static final int TYPE_APPLICATION_MEDIA_OVERLAY  = FIRST_SUB_WINDOW + 4;
    public static final int TYPE_APPLICATION_ABOVE_SUB_PANEL = FIRST_SUB_WINDOW + 5;
    public static final int LAST_SUB_WINDOW = 1999;
    
    //系统窗口2000-2999
    //系统窗口开始标记
    public static final int FIRST_SYSTEM_WINDOW     = 2000;
    //状态栏
    public static final int TYPE_STATUS_BAR         = FIRST_SYSTEM_WINDOW;
    public static final int TYPE_SEARCH_BAR         = FIRST_SYSTEM_WINDOW+1;
    //通话窗口，已过时，用TYPE_APPLICATION_OVERLAY
    @Deprecated
    public static final int TYPE_PHONE              = FIRST_SYSTEM_WINDOW+2;
    //系统警告窗口，已过时，用TYPE_APPLICATION_OVERLAY
    @Deprecated
    public static final int TYPE_SYSTEM_ALERT       = FIRST_SYSTEM_WINDOW+3;
    //锁屏窗口
    public static final int TYPE_KEYGUARD           = FIRST_SYSTEM_WINDOW+4;
    //Toast窗口，已过时，用TYPE_APPLICATION_OVERLAY
    @Deprecated
    public static final int TYPE_TOAST              = FIRST_SYSTEM_WINDOW+5;
    //系统overlay窗口，Android8.0之后用这个代替过时的系统窗口
    public static final int TYPE_APPLICATION_OVERLAY = FIRST_SYSTEM_WINDOW + 38;
    public static final int TYPE_SYSTEM_DIALOG      = FIRST_SYSTEM_WINDOW+8;
    public static final int TYPE_KEYGUARD_DIALOG    = FIRST_SYSTEM_WINDOW+9;
    //输入法窗口
    public static final int TYPE_INPUT_METHOD       = FIRST_SYSTEM_WINDOW+11;
    //壁纸窗口
    public static final int TYPE_WALLPAPER          = FIRST_SYSTEM_WINDOW+13;
    public static final int LAST_SYSTEM_WINDOW      = 2999;
    //...
}
```

这里有个细节，比如我们在Activity中弹出Dialog，并没有指定其窗口类型也能正常弹出来，打印时Activity窗口类型为1，此Dialog窗口类型为2，而显示PopupWindow时的窗口类型为1000，这里有个疑问，为什么dialog不是子窗口类型，而PopupWindow是子窗口呢？这里可以这两个控件的设计初衷来分析下，PopupWindow的显示必须指定一个View，即通过showAtLocation时必须传个view，代表此PopupWindow相对于此view的位置，即PopupWindow和此view是完全绑定的，可以理解成其“子窗口”，而dialog并不依赖于具体view，而是需要传Context上下文，不止在activity中能调，在其他组件，比如Service也能调，但是此时需要指定其窗口类型为TYPE_SYSTEM_ALERT等系统类型(系统应用才可以)，dialog并不需要依赖父窗口。

```java
//PopupWindow.java
private int mWindowLayoutType = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL;
public void showAtLocation(View parent, int gravity, int x, int y) {
    mParentRootView = new WeakReference<>(parent.getRootView());
    showAtLocation(parent.getWindowToken(), gravity, x, y);
}
public void showAtLocation(IBinder token, int gravity, int x, int y) {
    if (isShowing() || mContentView == null) {
        return;
    }
    //...
    final WindowManager.LayoutParams p = createPopupLayoutParams(token);
    preparePopup(p);
    p.x = x;
    p.y = y;
    //把此PopupWindow的窗口通过wms添加进去
    invokePopup(p);
    //...
}
protected final WindowManager.LayoutParams createPopupLayoutParams(IBinder token) {
    final WindowManager.LayoutParams p = new WindowManager.LayoutParams();
    //mWindowLayoutType已定义窗口类型为：TYPE_APPLICATION_PANEL（1000）
    p.type = mWindowLayoutType;
    //...给其他参数赋值
    return p;
}

private void invokePopup(WindowManager.LayoutParams p) {
    //...
    mWindowManager.addView(decorView, p);
    //...
}
```

再看下Dialog里的窗口类型

```java
//Dialog.java
Dialog(Context context, int themeResId,boolean createContextThemeWrapper) {
	//...
    final Window w = new PhoneWindow(mContext);
    mWindow = w;
    //...
}
//PhoneWindow的父类Window.java
private final WindowManager.LayoutParams mWindowAttributes =
    new WindowManager.LayoutParams();
//WindowManager的内部类LayoutParams
public LayoutParams() {
    super(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT);
    type = TYPE_APPLICATION; //2
    format = PixelFormat.OPAQUE;
}
```

new PhoneWindow默认的窗口类型就是2  

再看下Activity的窗口类型

```java
//ActivityThread.java
public void handleResumeActivity(){
    //...
    WindowManager.LayoutParams l = r.window.getAttributes();
    a.mDecor = decor;
    //指定Activity的窗口类型为1
    l.type = WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
    //...
    wm.addView(decor, l);
    //...
}
```

通过对比Activity，Dialog，PopupWindow可知，默认的窗口类型就是2，只不过Activity和PopupWindow都会在addView前设置下窗口类型。

再继续看主序和子序

+ 主序：用来描述窗口及其子窗口在所有窗口中的显示位置。主序越大，则相对于其他窗口越靠前
+ 子序：描述一个子窗口在其兄弟窗口中的显示位置。子序越大，相对于其兄弟窗口越靠前。

再重新看下这块计算主序和子序

```java
//基于窗口类型计算主序，子序 TYPE_LAYER_MULTIPLIER==10000 TYPE_LAYER_OFFSET==1000
if (mAttrs.type >= FIRST_SUB_WINDOW && mAttrs.type <= LAST_SUB_WINDOW) {
    //如果是子窗口，就用父窗口的主序  
    mBaseLayer = mPolicy.getWindowLayerLw(parentWindow)
        * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
    //根据窗口类型获取子序
    mSubLayer = mPolicy.getSubWindowLayerFromTypeLw(a.type);
} else {
    //非子窗口，就根据窗口类型来计算主序
    mBaseLayer = mPolicy.getWindowLayerLw(this)
        * TYPE_LAYER_MULTIPLIER + TYPE_LAYER_OFFSET;
    //子序设为0
    mSubLayer = 0;
}
```

计算主序是根据getWindowLayerLw获取窗口类型对应的值，乘以10000再加1000的偏移量。计算子序是有子窗口时根据子窗口类型去获取对应的偏移量，没有子窗口时子序默认为0。

> frameworks/base/services/core/java/com/android/server/policy/WindowManagerPolicy.java

```java
default int getWindowLayerLw(WindowState win) {
    return getWindowLayerFromTypeLw(win.getBaseType(), win.canAddInternalSystemWindow());
}
default int getWindowLayerFromTypeLw(int type, boolean canAddInternalSystemWindow) {
    return getWindowLayerFromTypeLw(type, canAddInternalSystemWindow,false);
}
default int getWindowLayerFromTypeLw(int type, boolean canAddInternalSystemWindow,boolean roundedCornerOverlay) {
    //如果是应用窗口，直接返回2
    if (type >= FIRST_APPLICATION_WINDOW && type <= LAST_APPLICATION_WINDOW) {
        return APPLICATION_LAYER;
    }
    switch (type) {
        case TYPE_WALLPAPER:
            // wallpaper is at the bottom, though the window manager may move it.
            return  1;
        case TYPE_PRESENTATION:
        case TYPE_PRIVATE_PRESENTATION:
        case TYPE_DOCK_DIVIDER:
        case TYPE_QS_DIALOG:
        case TYPE_PHONE:
            return  3;
        case TYPE_SEARCH_BAR:
            return  4;
        case TYPE_INPUT_CONSUMER:
            return  5;
        case TYPE_SYSTEM_DIALOG:
            return  6;
        case TYPE_TOAST:
            return  7;
        case TYPE_PRIORITY_PHONE:
            // SIM errors and unlock.  Not sure if this really should be in a high layer.
            return  8;
        case TYPE_SYSTEM_ALERT:
            // like the ANR / app crashed dialogs
            // Type is deprecated for non-system apps. For system apps, this type should be
            // in a higher layer than TYPE_APPLICATION_OVERLAY.
            return  canAddInternalSystemWindow ? 12 : 9;
        case TYPE_APPLICATION_OVERLAY:
            return  11;
        case TYPE_INPUT_METHOD:
            // on-screen keyboards and other such input method user interfaces go here.
            return  13;
        case TYPE_INPUT_METHOD_DIALOG:
            // on-screen keyboards and other such input method user interfaces go here.
            return  14;
        case TYPE_STATUS_BAR:
            return  15;
        case TYPE_STATUS_BAR_ADDITIONAL:
            return  16;
        case TYPE_NOTIFICATION_SHADE:
            return  17;
        case TYPE_STATUS_BAR_SUB_PANEL:
            return  18;
        case TYPE_KEYGUARD_DIALOG:
            return  19;
        case TYPE_VOICE_INTERACTION_STARTING:
            return  20;
        case TYPE_VOICE_INTERACTION:
            // voice interaction layer should show above the lock screen.
            return  21;
        case TYPE_VOLUME_OVERLAY:
            // the on-screen volume indicator and controller shown when the user
            // changes the device volume
            return  22;
        case TYPE_SYSTEM_OVERLAY:
            // the on-screen volume indicator and controller shown when the user
            // changes the device volume
            return  canAddInternalSystemWindow ? 23 : 10;
            //...
        case TYPE_POINTER:
            // the (mouse) pointer layer
            return  35;
        default:
            Slog.e("WindowManager", "Unknown window type: " + type);
            return 3;
    }
}
```

从这里可以看到为什么壁纸窗口会在所有窗口的底部，其返回值为1，而默认应用窗口直接返回2。所以应用窗口的默认主序为2x10000+1000=21000，而壁纸窗口为1x10000+1000=11000。

再看计算子序

```java
int APPLICATION_MEDIA_SUBLAYER = -2;
int APPLICATION_MEDIA_OVERLAY_SUBLAYER = -1;
int APPLICATION_PANEL_SUBLAYER = 1;
int APPLICATION_SUB_PANEL_SUBLAYER = 2;
int APPLICATION_ABOVE_SUB_PANEL_SUBLAYER = 3;
default int getSubWindowLayerFromTypeLw(int type) {
    switch (type) {
        case TYPE_APPLICATION_PANEL:
        case TYPE_APPLICATION_ATTACHED_DIALOG:
            return APPLICATION_PANEL_SUBLAYER;
        case TYPE_APPLICATION_MEDIA:
            return APPLICATION_MEDIA_SUBLAYER;
        case TYPE_APPLICATION_MEDIA_OVERLAY:
            return APPLICATION_MEDIA_OVERLAY_SUBLAYER;
        case TYPE_APPLICATION_SUB_PANEL:
            return APPLICATION_SUB_PANEL_SUBLAYER;
        case TYPE_APPLICATION_ABOVE_SUB_PANEL:
            return APPLICATION_ABOVE_SUB_PANEL_SUBLAYER;
    }
    Slog.e("WindowManager", "Unknown sub-window type: " + type);
    return 0;
}
```

判断是子窗口类型的话，返回-2到3之间的值，其实就是相对于父窗口的位置，在其前或者后几位。

```java
//以下是dumpsys window后看到的窗口信息
//状态栏
Window #1 Window{3ce83ce u0 StatusBar}:
mBaseLayer=151000 mSubLayer=0    mToken=WindowToken{3cdc0c9 type=2000 android.os.BinderProxy@a60d182}
//...

//Demo的activity窗口弹出的PopupWindow
Window #6 Window{c24c4aa u0 PopupWindow:9c5a292}:
mBaseLayer=21000 mSubLayer=1    mToken=ActivityRecord{f7f4f25 u0 com.paul.test/.SecondActivity} t89}
mActivityRecord=ActivityRecord{f7f4f25 u0 com.paul.test/.SecondActivity} t89}
//...

//Demo的Activity窗口
Window #7 Window{ba4aa87 u0 com.paul.test/com.paul.test.SecondActivity}:
mBaseLayer=21000 mSubLayer=0    mToken=ActivityRecord{f7f4f25 u0 com.paul.test/.SecondActivity} t89}
mActivityRecord=ActivityRecord{f7f4f25 u0 com.paul.test/.SecondActivity} t89}
//...

//壁纸窗口
Window #12 Window{43d8bee u0 com.android.systemui.wallpapers.ImageWallpaper}:
mBaseLayer=11000 mSubLayer=0    mToken=WallpaperWindowToken{8556705 token=android.os.Binder@457e27c}
//...

```

可以看到状态栏在最上面，activity的popupwindow作为子窗口在activity的上面，壁纸在最底部。  

上面这些概念理解通的话，WMS管理窗口的原理基本上就理解了一大部分了，下面把漏掉的细节补上。

#### WMS启动流程

在[开机流程](https://github.com/beyond667/study/blob/master/note/%E5%BC%80%E6%9C%BA%E6%B5%81%E7%A8%8B.md)中已经分析过，WMS是在`SystemServer`的`startOtherServices()`里启动的，本文只关注跟WMS相关的。

```java
private void startOtherServices(@NonNull TimingsTraceAndSlog t) {
    //...
    //通过wms.main方法初始化WMS
    wm = WindowManagerService.main(context, inputManager, !mFirstBoot, mOnlyCore,
                                   new PhoneWindowManager(), mActivityManagerService.mActivityTaskManager);
    //AMS里关联此WMS
    mActivityManagerService.setWindowManager(wm);
    //执行wms.onInitReady通知初始化完成
    wm.onInitReady();
    //...
    //wms已经完全启动好，把关联的服务也执行其systemReady
    wm.systemReady();
    //...
}
```

wms通过执行其main方法实现初始化，再关联到ams中，之后在调用其onInitReady通知初始化已完成。  

这里注意传的参数里有个new PhoneWindowManager()

```java
//wms.java
public static WindowManagerService main(final Context context, final InputManagerService im,
                                        final boolean showBootMsgs, final boolean onlyCore, WindowManagerPolicy policy,
                                        ActivityTaskManagerService atm) {
    return main(context...SurfaceControl.Transaction::new, SurfaceControl.Builder::new);
}
public static WindowManagerService main(...) {
    final WindowManagerService[] wms = new WindowManagerService[1];
    //Display线程运行new WindowManagerService
    DisplayThread.getHandler().runWithScissors(() ->
                                               wms[0] = new WindowManagerService(context...),0);
    return wms[0];
}
```

main方法里通过runWithScissors往display线程发送消息，并等待display线程执行完此消息后再继续往下走。

> runWithScissors方法会在当前线程new一个BlockingRunnable，并执行其postAndWait，在这里会先往目标线程的hander post要执行的runnable，之后本线程wait等待，直到目标线程执行完此runnable，并唤醒调用线程，调用线程才继续往下执行。代码不再细看。

```java
private WindowManagerService(Context context, InputManagerService inputManager...){
	//...
    //参数本地化
    mInputManager = inputManager; 
    //SurfaceControl工厂
    mSurfaceControlFactory = surfaceControlFactory;
    //mPolicy即new PhoneWindowManager()
    mPolicy = policy;
    //管理所有的窗口动画
    mAnimator = new WindowAnimator(this);
    //所有窗口的根容器
    mRoot = new RootWindowContainer(this);
    
    mDisplayManager = (DisplayManager)context.getSystemService(Context.DISPLAY_SERVICE);
    //...
}
public void onInitReady() {
    initPolicy();
    //添加看门狗监控WMS
    Watchdog.getInstance().addMonitor(this);
}
private void initPolicy() {
    UiThread.getHandler().runWithScissors(new Runnable() {
        @Override
        public void run() {
            WindowManagerPolicyThread.set(Thread.currentThread(), Looper.myLooper());
            //1 执行PhoneWindowManager.init，以完成PhoneWindowManager的初始化
            mPolicy.init(mContext, WindowManagerService.this);
        }
    }, 0);
}

public void systemReady() {
    //关联的服务也执行其systemReady
    mSystemReady = true;
    mPolicy.systemReady();
    mRoot.forAllDisplayPolicies(DisplayPolicy::systemReady);
    mTaskSnapshotController.systemReady();
    //...
}
```

这里注意下注释1处，在wms.onInitReady里执行了initPolicy，这里跟上面一样，往UI线程post一条消息，来完成mPolicy的初始化，即PhoneWindowManager的初始化。在wms启动好后执行systemReady时，也同样执行了mPolicy的systemReady。另在wms.onInitReady里添加了看门狗来监控WMS的运行，可参照[深入理解WatchDog](https://github.com/beyond667/study/blob/master/note/%E6%B7%B1%E5%85%A5%E7%90%86%E8%A7%A3WatchDog%E5%AE%9E%E7%8E%B0%E5%8E%9F%E7%90%86.md)

##### WMS管理窗口

从客户端角度，不管是Activity，dialog，还是popupWindow等自定义的view，在显示前都会调用WindowManagerImpl.addView（可能会先设置窗口类型大小位置等参数），这里会调用全局单例类WindowManagerGlobal.addView，此方法会先把窗口参数缓存到params里，再构建个ViewRootImpl对象，再把params，viewrootImpl和decorView都缓存起来，最后调用viewRootImpl.setView，此方法会先调用requestLayout先完成一次View绘制，此时view还没添加到窗口，然后通过session.addToDisplayAsUser调用服务端的session去完成窗口添加。

从服务端角度来看，客户端调用Session.addToDisplayAsUser,其实也就是调用wms.addWindow（此方法就这一行代码），我们再从完整的角度看下wms.addWindow

```java
//wms.java
public int addWindow(Session session, IWindow client, LayoutParams attrs...){
    //1 先检查要添加的窗口的应用是否有权限
    int res = mPolicy.checkAddPermission(attrs.type, isRoundedCornerOverlay, attrs.packageName,appOp);
    if (res != ADD_OKAY) {
        return res;
    }

    WindowState parentWindow = null;
    final int type = attrs.type;

    //2 校验DisplayContent，开机启动时已经new了DisplayContent后添加到RootWindowContainer
    final DisplayContent displayContent = getDisplayContentOrCreate(displayId, attrs.token);
    if (displayContent == null) {
        return WindowManagerGlobal.ADD_INVALID_DISPLAY;
    }

    //3 已经添加过此窗口的话就不再添加直接return
    if (mWindowMap.containsKey(client.asBinder())) {
        return WindowManagerGlobal.ADD_DUPLICATE_ADD;
    }
    
    //4 如果要添加的窗口是子窗口，需校验其父窗口，如果为空或者父窗口的类型也是子窗口，也直接返回校验失败
    if (type >= FIRST_SUB_WINDOW && type <= LAST_SUB_WINDOW) {
        parentWindow = windowForClientLocked(null, attrs.token, false);
        if (parentWindow == null) {
            return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
        }
        if (parentWindow.mAttrs.type >= FIRST_SUB_WINDOW
            && parentWindow.mAttrs.type <= LAST_SUB_WINDOW) {
            return WindowManagerGlobal.ADD_BAD_SUBWINDOW_TOKEN;
        }
    }
    //...省略其他type的校验
    
    //5 WindowToken校验，这步后一定会有windowToken
    ActivityRecord activity = null;
    final boolean hasParent = parentWindow != null;
    WindowToken token = displayContent.getWindowToken(hasParent ? parentWindow.mAttrs.token : attrs.token);
    if (token == null) {
        //...
    }else if(rootType >= FIRST_APPLICATION_WINDOW&& rootType <= LAST_APPLICATION_WINDOW){
        //...
    }//...
    
    //6 构建WindowState
    final WindowState win = new WindowState(this, session, client, token, parentWindow,
                                            appOp[0], attrs, viewVisibility, session.mUid, userId,
                                            session.mCanAddInternalSystemWindow);

    //...
    res = ADD_OKAY;
    //7 如果应用是冷启动，会在native层创建SurfaceSession
    win.attach();
    //缓存该Windowstate
    mWindowMap.put(client.asBinder(), win);
    //...
    win.mToken.addWindow(win);
    displayPolicy.addWindowLw(win, attrs);

    return res;
}
```

+ 注释1-4都是校验过程，不再细看。
+ 注释5关于WindowToken的校验，可以参照上面WindowToken部分
+ 注释6关于WindowState的创建，可以参照上面WindowState部分
+ 注释7调用windowState.attach，如果该应用是冷启动，会创建SurfaceSession。关于SurfaceFlinger部分会专门详解。

关于移除Window，在activity销毁，dialog或者popupwindow隐藏等都会调用流程如下（这里只关注WMS，不关注客户端方面）：

Session.remove -> wms.removeWindow -> WindowState.removeIfPossible-> removeImmediately->postWindowRemoveCleanupLocked.postWindowRemoveCleanupLocked 

```java
void postWindowRemoveCleanupLocked(WindowState win) {
    //wms移除WindowState记录
    mWindowMap.remove(win.mClient.asBinder());
    //移除WindowToken
    final WindowToken token = win.mToken;
    if (token.isEmpty() && !token.mPersistOnEmpty) {
        token.removeImmediately();
    }
}
```

综上WMS主要是为了维护WindowState对象。  

#### 总结

添加窗口的过程可以从客户端和服务端两端分别分析。

对客户端来说，不管是启动activity还是打开dialog或者popupwindow，都会设置其窗口类型，比如Avtivity是1，dialog用的是PhoneWindow的默认是2，popupwindow作为子窗口默认是1000，再调用WindowManagerImpl.addView ->WindowManagerGlobal.addView，WindowManagerGlobal是客户端的单例类，管理客户端该应用所有的窗口，这里会先把PhoneWindow里的参数取出放到WindowManager.LayoutParams，再构建个ViewRootImpl，可以理解成一个activity对应一个ViewRootImpl，再在WindowManagerGlobal里缓存ViewRootImpl，decorView，LayoutParams这3个对象，方便后面管理。最终调用ViewRootImpl.setView->Session.addToDisplayAsUser，这里会传窗口的参数params和ViewRootImpl的内部binder对象IWindow.Stub，此binder对象持有外部类ViewRootImpl的弱引用，方便服务端来控制客户端的此ViewRootImpl即Activity或者dialog的窗口。  另外注意一个应用只有一个Session，只有应用第一次启动时才会去服务端拿Session的代理。

对服务端来说，session.addToDisplayAsUser直接调用wms.addWindow，会先做些校验工作，比如该应用是否有添加窗口的权限，如果添加的是子窗口，父窗口不能为空或者也是子窗口等。之后会去校验WindowToken，系统应用可以不用传WindowToken，不传的话会为其自动生成WindowToken，普通应用必须传WindowToken。WindowToken可以理解成一个装WindowState的容器，在其构造方法中传了token作为凭证，对普通应用来说，在其启动流程startActivityUnchecked中最终会调用到DisplayContent.addWindowToken去添加binder和WindowToken的绑定关系，普通应用这里的WindowToken实际上是ActivityRecord，其继承于WindowToken。wms.addWindow这里会通过binder来拿WindowToken进行校验。之后根据WindowState，session，IWindow等创建WindowState，代表了WMS端一个真正的窗口，WMS实际就是维护此对象，比如添加，删除更新，焦点，事件传递等。在第一次启动应用时会在WindowState.attach里去创建SurfaceSession，具体的绘制就是通过此SurfaceSession关联的SurfaceFlinger来做的。

