### 前言

在[深入理解WMS](https://github.com/beyond667/study/blob/master/note/%E6%B7%B1%E5%85%A5%E7%90%86%E8%A7%A3WMS.md)一节中，我们知道WMS只是负责窗口的管理，并不负责窗口里面的内容(具体的view)的渲染和显示，比如手机桌面上显示有状态栏，导航栏，桌面图标列表，壁纸等，这几个窗口是怎么合成并同时显示出来的？这就要靠SurfaceFlinger来负责。本文基于Android13。

### SurfaceFlinger原理

SurfaceFlinger和[installd守护进程](https://github.com/beyond667/study/blob/master/note/installd%E5%AE%88%E6%8A%A4%E8%BF%9B%E7%A8%8B.md)一样，都在其独立的进程中，而不是在System_Server进程。SurfaceFlinger作为核心服务，负责将应用程序和系统服务的图像界面合成并呈现在屏幕上，此过程可以理解成双生成者-消费者模型。

+ 对于客户端来说，SurfaceFlinger作为消费者，客户端作为生产者，客户端负责生产出Surface，SurfaceFlinger负责消费Surface，把这些Surface合成到一个layer中，再把此layer渲染到FrameBuffer缓冲区
+ 对屏幕来说，SurfaceFlinger作为生产者，把生产出的FrameBuffer传递给屏幕驱动，屏幕驱动负责消费这些FrameBuffer来显示到屏幕。

当然，上面是简化了的流程，还有些比较重要的概念，比如合成后的FrameBuffer实际是传递给屏幕驱动的后缓冲区，在等待下一个vsync信号后，会把后缓冲区显示到前台作为前缓冲区，之前显示的前缓冲区改为后缓冲区，等待新的FrameBuffer数据。我们先从SurfaceFlinger守护进程的启动来看。

#### SurfaceFlinger启动

跟Installd守护进程一样，SurfaceFlinger也是通过rc启动的。Android7.0之前是在init.rc中，之后都拆分到独立的rc文件中。

> frameworks/native/services/surfaceflinger/surfaceflinger.rc

```properties
service surfaceflinger /system/bin/surfaceflinger
    class core animation
    user system
    group graphics drmrpc readproc
    capabilities SYS_NICE
    onrestart restart --only-if-running zygote
    task_profiles HighPerformance
    socket pdx/system/vr/display/client     stream 0666 system graphics u:object_r:pdx_display_client_endpoint_socket:s0
    socket pdx/system/vr/display/manager    stream 0666 system graphics u:object_r:pdx_display_manager_endpoint_socket:s0
    socket pdx/system/vr/display/vsync      stream 0666 system graphics u:object_r:pdx_display_vsync_endpoint_socket:s0
```

`service surfaceflinger`代表启动surfaceflinger服务，surfaceflinger服务在同目录的Android.bp文件里定义

>frameworks/native/services/surfaceflinger/Android.bp

```properties
filegroup {
    name: "surfaceflinger_binary_sources",
    srcs: [
        ":libsurfaceflinger_sources",
        "main_surfaceflinger.cpp",
    ],
}
cc_binary {
    name: "surfaceflinger",
    defaults: ["libsurfaceflinger_binary"],
    init_rc: ["surfaceflinger.rc"],
    srcs: [
        ":surfaceflinger_binary_sources",
        "SurfaceFlingerFactory.cpp",
    ],
}
```

指定其启动文件为本目录的`main_surfaceflinger.cpp`

>frameworks/native/services/surfaceflinger/main_surfaceflinger.cpp

```cpp
int main(int, char**) {
    //忽略了SIGPIPE信号，因为在SurfaceFlinger的Client-Server模型中，或者说IPC机制中，很可能会触发SIGPIPE信号，而这个信号的默认动作是终止进程
    signal(SIGPIPE, SIG_IGN);
    //SF进程开启后，binder线程池最大为4
    ProcessState::self()->setThreadPoolMaxThreadCount(4);
    
    //启动线程池
    //大多数程序都是需要IPC的，这里也需要，但是使用Binder机制是很繁琐的，所以Android为程序进程使用Binder机制封装了两个实现类：ProcessState、IPCThreadState
    //其中ProcessState负责打开Binder驱动，进行mmap等准备工作；IPCThreadState负责具体线程跟Binder驱动进行命令交互。
    sp<ProcessState> ps(ProcessState::self());
    ps->startThreadPool();
    
    //1 创建SurfaceFlinger对象，并执行其init方法来初始化
    sp<SurfaceFlinger> flinger = surfaceflinger::createSurfaceFlinger();
    flinger->init();
    
    //获取ServiceManager对象
    sp<IServiceManager> sm(defaultServiceManager());
    //2 发布名为SurfaceFlinger服务
    sm->addService(String16(SurfaceFlinger::getServiceName()), flinger, false,
                   IServiceManager::DUMP_FLAG_PRIORITY_CRITICAL | IServiceManager::DUMP_FLAG_PROTO);
    //发布名为SurfaceFlingerAIDL服务
    sp<SurfaceComposerAIDL> composerAIDL = new SurfaceComposerAIDL(flinger);
    sm->addService(String16("SurfaceFlingerAIDL"), composerAIDL, false,
                   IServiceManager::DUMP_FLAG_PRIORITY_CRITICAL | IServiceManager::DUMP_FLAG_PROTO);
    
    startDisplayService(); // dependency on SF getting registered above
    
    //3 调用run方法，进入休眠
    flinger->run();
    return 0;
}

//SurfaceFlinger.h
static char const* getServiceName() ANDROID_API { return "SurfaceFlinger"; }
```

此main函数先做了准备工作，比如忽略了SIGPIPE信号，设置binder进程最大连接数，开启线程池等

+ 注释1调用surfaceflinger的createSurfaceFlinger创建surfaceflinger
+ 注释2发布名为SurfaceFlinger的系统服务，后面还发布了名为SurfaceFlingerAIDL的系统服务
+ 注释3调用其run方法，进入休眠

我们先看注释1 `surfaceflinger::createSurfaceFlinger`这里的surfaceflinger其实指的是SurfaceFlingerFactory

```c++
//SurfaceFlingerFactory.h
namespace surfaceflinger {
//...
    ANDROID_API sp<SurfaceFlinger> createSurfaceFlinger();
}

//SurfaceFlingerFactory.cpp
sp<SurfaceFlinger> createSurfaceFlinger() {
    static DefaultFactory factory;
    return new SurfaceFlinger(factory);
}
```

调用SurfaceFlinger的构造函数来实例化对象

>frameworks/native/services/surfaceflinger/surfaceflinger.cpp

```cpp
SurfaceFlinger::SurfaceFlinger(Factory& factory) : SurfaceFlinger(factory, SkipInitialization) {
    // 一些参数的初始化
    //...
}

SurfaceFlinger::SurfaceFlinger(Factory& factory, SkipInitializationTag)
      : mFactory(factory),
        mPid(getpid()),
        mInterceptor(mFactory.createSurfaceInterceptor()),
        mTimeStats(std::make_shared<impl::TimeStats>()),
        mFrameTracer(mFactory.createFrameTracer()),
        mFrameTimeline(mFactory.createFrameTimeline(mTimeStats, mPid)),
		//关注下这个mCompositionEngine的初始化
        mCompositionEngine(mFactory.createCompositionEngine()),
        mHwcServiceName(base::GetProperty("debug.sf.hwc_service_name"s, "default"s)),
        mTunnelModeEnabledReporter(new TunnelModeEnabledReporter()),
        mInternalDisplayDensity(getDensityFromProperty("ro.sf.lcd_density", true)),
        mEmulatedDisplayDensity(getDensityFromProperty("qemu.sf.lcd_density", false)),
        mPowerAdvisor(std::make_unique<Hwc2::impl::PowerAdvisor>(*this)),
        mWindowInfosListenerInvoker(sp<WindowInfosListenerInvoker>::make(*this)) {
}
```

构造函数里的其他参数初始化不再细看，主要看下mCompositionEngine（Composition译为作品，构图，组合等）的初始化。

> frameworks/native/services/surfaceflinger/SurfaceFlingerDefaultFactory.cpp 

```cpp
std::unique_ptr<compositionengine::CompositionEngine> DefaultFactory::createCompositionEngine() {
    return compositionengine::impl::createCompositionEngine();
}
//创建HWComposer，后面init方法有用到
std::unique_ptr<HWComposer> DefaultFactory::createHWComposer(const std::string& serviceName) {
    return std::make_unique<android::impl::HWComposer>(serviceName);
}

//frameworks/native/services/surfaceflinger/CompositionEngine/src/CompositionEngine.cpp
std::unique_ptr<compositionengine::CompositionEngine> createCompositionEngine() {
    return std::make_unique<CompositionEngine>();
}
```

> make_unique是C++14引入的一个函数模板,用于创建并返回一个指向动态分配对象的unique_ptr智能指针。它是为了简化代码,避免手动使用new和delete,以及确保资源的正确释放而设计的。其实就是创建并返回了个指定对象的智能指针。

继续看注释1处`flinger->init()`执行了SurfaceFlinger的init方法

```cpp
void SurfaceFlinger::init() {
    //...
    //基于build模式创建RenderEngine对象，再把其设置到mCompositionEngine里
    auto builder = renderengine::RenderEngineCreationArgs::Builder()
        .setPixelFormat(static_cast<int32_t>(defaultCompositionPixelFormat))
        .setImageCacheSize(maxFrameBufferAcquiredBuffers)
        .setUseColorManagerment(useColorManagement)
        .setEnableProtectedContext(enable_protected_contents(false))
        .setPrecacheToneMapperShaderOnly(false)
        .setSupportsBackgroundBlur(mSupportsBlur)
        .setContextPriority(
        useContextPriority
        ? renderengine::RenderEngine::ContextPriority::REALTIME
        : renderengine::RenderEngine::ContextPriority::MEDIUM);
    mCompositionEngine->setRenderEngine(renderengine::RenderEngine::create(builder.build()));
    
    mCompositionEngine->setTimeStats(mTimeStats);
	//创建HWComposer对象，再把其设置到mCompositionEngine里
    mCompositionEngine->setHwComposer(getFactory().createHWComposer(mHwcServiceName));
    mCompositionEngine->getHwComposer().setCallback(*this);
    
    //4 处理热插拔和显示更改的事件
    processDisplayHotplugEventsLocked();
    
    //初始化display
    initializeDisplays();
    
    //创建开机动画线程并执行开机动画过程
    mStartPropertySetThread = getFactory().createStartPropertySetThread(presentFenceReliable);

    if (mStartPropertySetThread->Start() != NO_ERROR) {
        ALOGE("Run StartPropertySetThread failed!");
    }
}
```

里面会先对CompositionEngine设置RenderEngine和HwComposer（通过HAL层的HWComposer硬件模块或者软件模拟产生Vsync信号），再看注释4处`processDisplayHotplugEventsLocked`方法

```c++
void SurfaceFlinger::processDisplayHotplugEventsLocked() {
    for (const auto& event : mPendingHotplugEvents) {
        std::optional<DisplayIdentificationInfo> info =
            getHwComposer().onHotplug(event.hwcDisplayId, event.connection);
        //...
        processDisplayChangesLocked();
    }
}

void SurfaceFlinger::processDisplayChangesLocked() {
    //...
    for (size_t i = 0; i < draw.size(); i++) {
        const ssize_t j = curr.indexOfKey(displayToken);
        if (j < 0) {
            //处理display移除事件
            processDisplayRemoved(displayToken);
        }else {
            //处理display更新事件
            const DisplayDeviceState& currentState = curr[j];
            const DisplayDeviceState& drawingState = draw[i];
            processDisplayChanged(displayToken, currentState, drawingState);
        }
    }
    for (size_t i = 0; i < curr.size(); i++) {
        const wp<IBinder>& displayToken = curr.keyAt(i);
        if (draw.indexOfKey(displayToken) < 0) {
            //处理display添加事件
            processDisplayAdded(displayToken, curr[i]);
        }
    }
}

void SurfaceFlinger::processDisplayAdded(const wp<IBinder>& displayToken,
                                         const DisplayDeviceState& state) {
    //...
    if (display->isPrimary()) {
        //5 进行Scheduler初始化
        initScheduler(display);
    }
}
```

注释5处执行initScheduler初始化Scheduler

```c++
void SurfaceFlinger::initScheduler(const sp<DisplayDevice>& display) {
    //如果初始化过就直接return
    if (mScheduler) {
        mScheduler->setRefreshRateConfigs(display->holdRefreshRateConfigs());
        return;
    }
	//获取当前屏幕的fps 刷新率即帧率
    const auto currRefreshRate = display->getActiveMode()->getFps();
    mRefreshRateStats = std::make_unique<scheduler::RefreshRateStats>(*mTimeStats, currRefreshRate,
                                                                      hal::PowerMode::OFF);
	//基于帧率创建vsync配置
    mVsyncConfiguration = getFactory().createVsyncConfiguration(currRefreshRate);
    mVsyncModulator = sp<VsyncModulator>::make(mVsyncConfiguration->getCurrentConfigs());
    
	//6 生成scheduler
    mScheduler = std::make_unique<scheduler::Scheduler>(static_cast<ICompositor&>(*this),
                                                        static_cast<ISchedulerCallback&>(*this),features);
    //...
    //7 创建app和appSf的连接
    mAppConnectionHandle =
        mScheduler->createConnection("app", mFrameTimeline->getTokenManager(),
                                     /*workDuration=*/configs.late.appWorkDuration,
                                     /*readyDuration=*/configs.late.sfWorkDuration,
                                     impl::EventThread::InterceptVSyncsCallback());
    mSfConnectionHandle =
        mScheduler->createConnection("appSf", mFrameTimeline->getTokenManager(),
                                     /*workDuration=*/std::chrono::nanoseconds(vsyncPeriod),
                                     /*readyDuration=*/configs.late.sfWorkDuration,
                                     [this](nsecs_t timestamp) {
                                         mInterceptor->saveVSyncEvent(timestamp);
                                     });
    //初始化vsync
    mScheduler->initVsync(mScheduler->getVsyncDispatch(), *mFrameTimeline->getTokenManager(),
                          configs.late.sfWorkDuration);
    
    //...
}
```

重点看注释7分别创建app和appSf连接

>frameworks/native/services/surfaceflinger/Scheduler/Scheduler.cpp

```c++
ConnectionHandle Scheduler::createConnection(
    const char* connectionName, frametimeline::TokenManager* tokenManager,
    std::chrono::nanoseconds workDuration, std::chrono::nanoseconds readyDuration,
    impl::EventThread::InterceptVSyncsCallback interceptCallback) {
    //8 创建DispSyncSource
    auto vsyncSource = makePrimaryDispSyncSource(connectionName, workDuration, readyDuration);
    auto throttleVsync = makeThrottleVsyncCallback();
    auto getVsyncPeriod = makeGetVsyncPeriodFunction();
    //9 基于DispSyncSource创建EventThread线程
    auto eventThread = std::make_unique<impl::EventThread>(std::move(vsyncSource), tokenManager,
                                                           std::move(interceptCallback),
                                                           std::move(throttleVsync),
                                                           std::move(getVsyncPeriod));
    //strcmp判断两个字符串是否相等，相等的话返回0，这里如果传的是app，strcmp即0，前面取非即1
    //也就是说如果是app，这个triggerRefresh为true
    bool triggerRefresh = !strcmp(connectionName, "app");
    return createConnection(std::move(eventThread), triggerRefresh);
}

ConnectionHandle Scheduler::createConnection(std::unique_ptr<EventThread> eventThread,
                                             bool triggerRefresh) {
    //10 创建ConnectionHandle
    const ConnectionHandle handle = ConnectionHandle{mNextConnectionHandleId++};
    //11 创建EventThreadConnection
    auto connection = createConnectionInternal(eventThread.get(), triggerRefresh);

    std::lock_guard<std::mutex> lock(mConnectionsLock);
    //12 往mConnections缓存ConnectionHandle和Connection
    //其中Connection里包含了EventThreadConnection和eventThread
    mConnections.emplace(handle, Connection{connection, std::move(eventThread)});
    return handle;
}

sp<EventThreadConnection> Scheduler::createConnectionInternal(EventThread* eventThread,
                                                              bool triggerRefresh, ISurfaceComposer::EventRegistrationFlags eventRegistration) {
    // Refresh need to be triggered from app thread alone.
    // Triggering it from sf connection can result in infinite loop due to requestnextvsync.
    //上面英文注释说明，刷新操作只能从app线程触发，如果从sf线程触发，会由于requestnextvsync导致无限循环
    if (triggerRefresh) {
        return eventThread->createEventConnection([&] { resyncAndRefresh(); }, eventRegistration);
    } else {
        return eventThread->createEventConnection([&] { resync(); }, eventRegistration);
    }
}
```

+ 注释8和9会创建DispSyncSource，并根据DispSyncSource创建EventThread线程，这里会创建app和appSf
+ 注释10会创建ConnectionHandle
+ 注释11基于9处创建的EventThread来创建EventThreadConnection
+ 注释12以ConnectionHandle作为key，EventThreadConnection作为value缓存到mConnections里

> app线程负责接收vsync信号并且上报给app，app开始画图，即负责通知app渲染
>
> sf线程用于接收vsync信号用于合成。
>
> 两个线程同时收到vsync信号，如果同时工作的话第一个线程还没渲染完第二个线程就没办法合成，所以这里会对这两个线程配置不同的时间偏移量，保证第一个线程执行完后再执行第二个。

注意一点的是，Android12之前偏移量在createConnection时就会传进来

```cpp
//Android11的surfaceflinger.cpp
void SurfaceFlinger::init() {
    //...
    mAppConnectionHandle =
        mScheduler->createConnection("app", mPhaseConfiguration->getCurrentOffsets().late.app,
                                     impl::EventThread::InterceptVSyncsCallback());
    mSfConnectionHandle =
        mScheduler->createConnection("sf", mPhaseConfiguration->getCurrentOffsets().late.sf,
                                     [this](nsecs_t timestamp) {
                                         mInterceptor->saveVSyncEvent(timestamp);
                                     });
    //...
}
```

可以看到11在SurfaceFlinger的init里就直接createConnection，并且createConnection传了offsets，而Android12即以后createConnection放到了initScheduler里，并且偏移量也不是直接传进去的

```c++
//Android13的surfaceflinger.cpp
void SurfaceFlinger::init() {
    //...
    startUnifiedDraw();
}
void SurfaceFlinger::startUnifiedDraw() {
  createPhaseOffsetExtn();
}

void SurfaceFlinger::createPhaseOffsetExtn() {
    //...
    const auto vsyncConfig =
        mVsyncModulator->setVsyncConfigSet(mVsyncConfiguration->getCurrentConfigs());
    ALOGI("VsyncConfig sfOffset %" PRId64 "\n", vsyncConfig.sfOffset);
    ALOGI("VsyncConfig appOffset %" PRId64 "\n", vsyncConfig.appOffset);  
}
  
```

最上面注释1处的init过程就看完了，surfaceflinger初始化时会创建两个eventthread线程：app和appsf，分别用来接收vsync后通知app完成绘制和sf来完成合成，两个线程基于不同的偏移量，保证app线程执行完渲染后再由appsf线程完成合成。

注释2处是把surfaceflinger发布到ServiceManager，绑定时会回调surfaceflinger的binderDied方法

```cpp
void SurfaceFlinger::binderDied(const wp<IBinder>&) {
    // the window manager died on us. prepare its eulogy.
    mBootFinished = false;

    // Sever the link to inputflinger since it's gone as well.
    static_cast<void>(mScheduler->schedule([=] { mInputFlinger = nullptr; }));

    // restore initial conditions (default device unblank, etc)
    initializeDisplays();

    // restart the boot-animation
    startBootAnim();
}
```

如果绑定到ServerManager的surfaceflinger服务挂掉的话，这里会重新执行开机动画。

注释3会调用run方法，进入休眠

```c++
//SurfaceFlinger.cpp
void SurfaceFlinger::run() {
    mScheduler->run();
}

//Scheduler.cpp
void Scheduler::run() {
    while (true) {
        waitMessage();
    }
}
```

Android12之后Scheduler就是继承于MessageQueue，所以这里调用的是MessageQueue的waitMessage

```c++
//Scheduler.h
class Scheduler : impl::MessageQueue {
    using Impl = impl::MessageQueue;
}   
//MessageQueue.java
void MessageQueue::waitMessage() {
    do {
        IPCThreadState::self()->flushCommands();
        int32_t ret = mLooper->pollOnce(-1);
        switch (ret) {
            case Looper::POLL_WAKE:
            case Looper::POLL_CALLBACK:
                continue;
            case Looper::POLL_ERROR:
                ALOGE("Looper::POLL_ERROR");
                continue;
            case Looper::POLL_TIMEOUT:
                // timeout (should not happen)
                continue;
            default:
                // should not happen
                ALOGE("Looper::pollOnce() returned unknown status %d", ret);
                continue;
        }
    } while (true);
} 
```

可以看到SurfaceFlinger的主线程通过死循环执行waitMessage，而其内部是通过mLooper->pollOnce去获取消息。这块的Looper，MessageQueue和java层的不是同一个对象，此处的Looper和MQ是专门为SurfaceFlinger设计的。  

到这里先总结下，SurfaceFlinger启动过程是从SurfaceFlinger.rc配置执行了main_surfaceflinger.cpp的main方法，这里会先创建SurfaceFlinger对象并执行其init方法，这里会初始化scheduler，在scheduler初始化时会创建两个EventThread线程：app，appsf线程，接收到vsync信号后app线程通知客户端执行绘制流程，然后appsf线程在一段时间后执行合成流程。初始化完之后surfaceflinger会在主线程执行waitMessage等待消息，内部是通过Looper.poolOnce去获取消息。  

#### SurfaceFlinger工作流程

> 启动过程有几个比较重要的对象没有细看，比如，DispSyncSource，HWComposer，在SurfaceFlinger工作流程中将会再关注到这几个对象。

我们分析下从应用启动流程到屏幕显示出画面的过程。

##### 1 与SurfaceFlinger创建连接

先复习下，在[深入理解WMS](https://github.com/beyond667/study/blob/master/note/%E6%B7%B1%E5%85%A5%E7%90%86%E8%A7%A3WMS.md)一节的Session中可知，ActivityThread.handleResumeActivity -> WindowManagerImpl.addView -> WindowManagerGlobal.addView -> new ViewRootImpl ->WindowManagerGlobal.getWindowSession()，应用初始化ViewRootImpl时会去获取Session，而Session在应用中是单例存在的，即一个应用只有一个Session。

```java
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

可以看到第一次获取Session会调用WMS.openSession去获取Session，然后缓存到当前进程。

> frameworks/base/services/core/java/com/android/server/wm/WindowManagerService.java

```java
public IWindowSession openSession(IWindowSessionCallback callback) {
    //Session肯定是个Binder对象，要把其代理对象返回给客户端
    return new Session(this, callback);
}
//Session.java
class Session extends IWindowSession.Stub implements IBinder.DeathRecipient {}
```

然后在viewRootImpl.setView -> session.addToDisplayAsUser -> wms.addWindow，wms会创建WindowState，并执行其attach

```java
public int addWindow(Session session, IWindow client, LayoutParams attrs...){
    //...
    final WindowState win = new WindowState(this,session...);
    win.attach();
    //...
}
void attach() {
    mSession.windowAddedLocked();
}

//Session.java
private int mNumWindow = 0;
void windowAddedLocked() {
    //从这里可以看到只有应用第一次添加窗口时才会创建一次SurfaceSession
    if (mSurfaceSession == null) {
        //11 这里会创建SurfaceSession
        mSurfaceSession = new SurfaceSession();
        mService.mSessions.add(this);
        if (mLastReportedAnimatorScale != mService.getCurrentAnimatorScale()) {
            mService.dispatchNewAnimatorScaleLocked(this);
        }
    }
    mNumWindow++;
}
```

注释11会创建SurfaceSession，我们从这个开始看。

> frameworks/base/core/java/android/view/SurfaceSession.java

```java
private long mNativeClient; // SurfaceComposerClient*
public SurfaceSession() {
    mNativeClient = nativeCreate();
}
```

调用了JNI的nativeCreate方法，并且把返回的long指针地址保存起来，后面再调用JNI方法就把这个指针地址传过去就能直接访问JNI层创建的SurfaceComposerClient对象

> frameworks/base/core/jni/android_view_SurfaceSession.cpp

```cpp
static jlong nativeCreate(JNIEnv* env, jclass clazz) {
    SurfaceComposerClient* client = new SurfaceComposerClient();
    client->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(client);
}
```

直接创建了SurfaceComposerClient对象

> frameworks/native/libs/gui/SurfaceComposerClient.cpp

```c++
//初始化时状态为NO_INIT
SurfaceComposerClient::SurfaceComposerClient() : mStatus(NO_INIT) {}

void SurfaceComposerClient::onFirstRef() {
    //12 获取SurfaceFlinger的代理对象
    sp<ISurfaceComposer> sf(ComposerService::getComposerService());
    //13 SurfaceFlinger不为空，并且SurfaceComposerClient状态为NO_INIT时创建连接
    if (sf != nullptr && mStatus == NO_INIT) {
        sp<ISurfaceComposerClient> conn;
        conn = sf->createConnection();
        if (conn != nullptr) {
            //14 把返回的ISurfaceComposerClient的代理对象保存到mClinet中
            mClient = conn;
            mStatus = NO_ERROR;
        }
    }
}
```

SurfaceComposerClient的构造函数中设置状态为NO_INIT，由于SurfaceComposerClient继承自RefBase所以会执行onFirstRef，这里会去拿SurfaceFlinger（注释12），如果不为空，并且状态是NO_INIT，会去执行SurfaceFlinger.createConnection来创建跟SurfaceFlinger的连接。我们先看下注释12

```c++
//SurfaceComposerClient.cpp
/*static*/ sp<gui::ISurfaceComposer> ComposerServiceAIDL::getComposerService() {
    ComposerServiceAIDL& instance = ComposerServiceAIDL::getInstance();
    std::scoped_lock lock(instance.mMutex);
    if (instance.mComposerService == nullptr) {
        //如果instance.mComposerService为空，就执行ComposerService单例对象的connectLocked
        if (ComposerServiceAIDL::getInstance().connectLocked()) {
            ALOGD("ComposerServiceAIDL reconnected");
        }
    }
    //instance.mComposerService即SurfaceFlinger的代理对象
    return instance.mComposerService;
}
bool ComposerService::connectLocked() {
    //获取SurfaceFlinger的代理对象，并缓存到单例对象的mComposerService中
    const String16 name("SurfaceFlinger");
    mComposerService = waitForService<ISurfaceComposer>(name);
    if (mComposerService == nullptr) {
        return false; // fatal error or permission problem
    }

    // Create the death listener.
    //...
    mDeathObserver = new DeathObserver(*const_cast<ComposerService*>(this));
    IInterface::asBinder(mComposerService)->linkToDeath(mDeathObserver);
    return true;
}

```

instance.mComposerService即SurfaceFlinger的代理对象，如果为空，就会调connectLocked去获取SurfaceFlinger的代理对象，并绑定死亡监听。再继续看注释13处`sf->createConnection`

```cpp
sp<ISurfaceComposerClient> SurfaceFlinger::createConnection() {
    const sp<Client> client = new Client(this);
    return client->initCheck() == NO_ERROR ? client : nullptr;
}

//Client.h
//Client肯定是个Binder对象，以Bn开头
class Client : public BnSurfaceComposerClient{}

//ISurfaceComposer.h
class BnSurfaceComposerClient : public SafeBnInterface<ISurfaceComposerClient> {
    status_t onTransact(uint32_t code, const Parcel& data, Parcel* reply, uint32_t flags) override;
}
```

SurfaceFlinger直接返回了Client的代理对象，并保存在注释14的mClient中。后面客户端通过SurfaceComposerClient创建surface实际上是通过的mClient来做的，而mClient又是SurfaceFlinger的代理对象，这样，客户端就完成了与SurfaceFlinger的联系。



