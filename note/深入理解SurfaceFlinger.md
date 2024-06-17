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

SurfaceFlinger直接返回了Client的代理对象，并保存在注释14的mClient中。后面客户端通过SurfaceComposerClient创建surface是通过的Client的代理对象mClient来做的，实际上调用的还是在SurfaceFlinger中new的Client对象。这样，客户端就完成了与SurfaceFlinger的联系。  

总结下，应用在启动中初始化ViewRootImpl时会创建跟WMS的连接Session，之后在ViewRootImpl.setView中会通过session调用到WMS.addWindow，这里会创建WindowState，并执行其attach，首次会通过JNI创建SurfaceSession，其实是返回了SurfaceComposerClient对象的地址，此对象里持有的mClient对象是通过surfaceflinger创建的Client对象。

##### 2 Surface创建流程

下面分析View绘制到Surface创建过程。  

我们知道，view绘制时会调用ViewRootImpl.requestLayout 

```java
public void requestLayout() {
    if (!mHandlingLayoutInLayoutRequest) {
        //这个做了主线程检查，非主线程直接抛异常，所以只能在主线程更新UI
        checkThread();
        mLayoutRequested = true;
        scheduleTraversals();
    }
}

void scheduleTraversals() {
    if (!mTraversalScheduled) {
        //mTraversalScheduled标志先设为true，待绘制流程开始后会把此标志改为false
        mTraversalScheduled = true;
        //插入同步屏障，保证之后只有异步消息才会被执行
        mTraversalBarrier = mHandler.getLooper().getQueue().postSyncBarrier();
        //编舞者发布异步消息，post个runnable，保证此runnable会尽快执行
        mChoreographer.postCallback(
            Choreographer.CALLBACK_TRAVERSAL, mTraversalRunnable, null);
        notifyRendererOfFramePending();
        pokeDrawLockIfNeeded();
    }
}
final TraversalRunnable mTraversalRunnable = new TraversalRunnable();
final class TraversalRunnable implements Runnable {
    @Override
    public void run() {
        doTraversal();
    }
}

void doTraversal() {
    if (mTraversalScheduled) {
        //绘制流程开始，先把之前的标志设为false，并移除同步屏障，因为都到这里了，说明绘制流程已经可以开始了。
        mTraversalScheduled = false;
        mHandler.getLooper().getQueue().removeSyncBarrier(mTraversalBarrier);
        
        performTraversals();
    }
}

private void performTraversals() {
    //先准备窗口，再开始具体绘制流程
    relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);
	//具体绘制流程，performMeasure，performLayout，performDraw
}
```

这个过程较简单，绘制时scheduleTraversals时会先往handler的MessageQueue中插入个同步屏障，然后编舞者往handler里post个异步消息，此时同步消息会先不执行，而是先执行插进来的异步消息，即先执行此runnable的run方法，之后执行doTraversal->performTraversals，这里会先relayoutWindow，之后再开始真正的绘制流程。

```java
private int relayoutWindow(WindowManager.LayoutParams params, int viewVisibility,boolean insetsPending) {
//...
    final boolean relayoutAsync;
    if (LOCAL_LAYOUT
        && (mViewFrameInfo.flags & FrameInfo.FLAG_WINDOW_VISIBILITY_CHANGED) == 0
        && mWindowAttributes.type != TYPE_APPLICATION_STARTING
        && mSyncSeqId <= mLastSyncSeqId
        && winConfigFromAm.diff(winConfigFromWm, false /* compareUndefined */) == 0) {
        //...
        relayoutAsync = !positionChanged || !sizeChanged;
    }else{
        relayoutAsync = false;
    }
    
    //如果绘制的内容跟上次的位置和大小都没变化，relayoutAsync会为true，走relayoutAsync流程，否则走relayout流程
    if (relayoutAsync) {
        mWindowSession.relayoutAsync(mWindow, params,
                                     requestedWidth, requestedHeight, viewVisibility,
                                     insetsPending ? WindowManagerGlobal.RELAYOUT_INSETS_PENDING : 0, mRelayoutSeq,
                                     mLastSyncSeqId);
    } else {
        //15 调用session.relayout去请求SurfaceControl，注意，这里传的mSurfaceControl是直接new的没有内容的对象，是为了让WMS去往里面填充。
        relayoutResult = mWindowSession.relayout(mWindow, params,
                                                 requestedWidth, requestedHeight, viewVisibility,
                                                 insetsPending ? WindowManagerGlobal.RELAYOUT_INSETS_PENDING : 0, mRelayoutSeq,
                                                 mLastSyncSeqId, mTmpFrames, mPendingMergedConfiguration, mSurfaceControl,
                                                 mTempInsets, mTempControls, mRelayoutBundle);
    }
    
    //16 拿到的SurfaceControl数据传到Surface中
    if (!useBLAST()) {
        mSurface.copyFrom(mSurfaceControl);
    } else {
        updateBlastSurfaceIfNeeded();
    }
    //...
    return relayoutResult;
}

public final Surface mSurface = new Surface();
private final SurfaceControl mSurfaceControl = new SurfaceControl();

//SurfaceControl.java
public final class SurfaceControl implements Parcelable {}
//Surface.java
public class Surface implements Parcelable {}
```

+ 注释15调用session.relayout去请求SurfaceControl，需要注意的是此时传的mSurfaceControl是直接new的没有内容的对象，是为了让WMS去往里面填充。
+ 注释16不管useBLAST是否为真都会创建Surface，不同的是如果为false就直接拷贝，为true的话就通过updateBlastSurfaceIfNeeded去创建Surface，这块在下一小节再细看。Surface和SurfaceControl本质都是Parcelable。

继续看注释15的session.relayout

```java
//Session.java
public int relayout(IWindow window, WindowManager.LayoutParams attrs){
    int res = mService.relayoutWindow(this, window, attrs...);
}
//WMS.java
public int relayoutWindow(Session session, IWindow client, LayoutParams attrs...){
    synchronized (mGlobalLock) {
        final WindowState win = windowForClientLocked(session, client, false);
        if (win == null) {
            return 0;
        }
        //...
        
        final boolean shouldRelayout = viewVisibility == View.VISIBLE &&
            (win.mActivityRecord == null || win.mAttrs.type == TYPE_APPLICATION_STARTING
             || win.mActivityRecord.isClientVisible());
        
         if (shouldRelayout && outSurfaceControl != null) {
             //17 创建SurfaceControl，把客户端传过来的无内容的SurfaceControl传进去进行赋值
             result = createSurfaceControl(outSurfaceControl, result, win, winAnimator);
         }
        //...  
    }
}

private int createSurfaceControl(SurfaceControl outSurfaceControl, int result,
                                 WindowState win, WindowStateAnimator winAnimator) {
    WindowSurfaceController surfaceController;
    try {
        //18 通过WindowStateAnimator.createSurfaceLocked创建WindowSurfaceController
        surfaceController = winAnimator.createSurfaceLocked();
    } finally {}
    
     if (surfaceController != null) {
         //19 这里对客户端传过来的SurfaceControl进行了赋值
         surfaceController.getSurfaceControl(outSurfaceControl);
     }
    return result;
}

//WindowStateAnimator.java
WindowSurfaceController createSurfaceLocked() {
    if (mSurfaceController != null) {
        return mSurfaceController;
    }
    //20 直接new WindowSurfaceController
    mSurfaceController = new WindowSurfaceController(attrs.getTitle().toString(), format,
                                                     flags, this, attrs.type);
    //...
    return mSurfaceController;
}
```

注释17-20可知，客户端往WMS请求SurfaceControl，其实主要是根据参数new了WindowSurfaceController，此对象里已经包括了客户端需要的SurfaceControl对象，再把此对象copy到客户端的对象即可。

```java
class WindowSurfaceController {
    SurfaceControl mSurfaceControl;
    
    WindowSurfaceController(String name, int format, int flags, WindowStateAnimator animator,int windowType) {
        title = name;
        mService = animator.mService;
        final WindowState win = animator.mWin;
        mWindowType = windowType;
        mWindowSession = win.mSession;

        //构建者模式创建其Builder对象，再build创建实例
        final SurfaceControl.Builder b = win.makeSurface()
            .setParent(win.getSurfaceControl())
            .setName(name)
            .setFormat(format)
            .setFlags(flags)
            .setMetadata(METADATA_WINDOW_TYPE, windowType)
            .setMetadata(METADATA_OWNER_UID, mWindowSession.mUid)
            .setMetadata(METADATA_OWNER_PID, mWindowSession.mPid)
            .setCallsite("WindowSurfaceController");

        mSurfaceControl = b.build();
    }
}
```

构建者模式创建其Builder对象，再build创建实例

```java
public final class SurfaceControl implements Parcelable {
    //21 4种Surface类型
    public static final int FX_SURFACE_NORMAL   = 0x00000000;
    public static final int FX_SURFACE_EFFECT = 0x00020000;
    public static final int FX_SURFACE_CONTAINER = 0x00080000;
    public static final int FX_SURFACE_BLAST = 0x00040000;
    //Mask used for FX values above.
    public static final int FX_SURFACE_MASK = 0x000F0000;
    private static native long nativeCreate(SurfaceSession session, String name,
                                            int w, int h, int format, int flags, long parentObject, Parcel metadata);
    
    
    public SurfaceControl build() {
        //22 校验参数
        if (mWidth < 0 || mHeight < 0) {
            throw new IllegalStateException("width and height must be positive or unset");
        }
        if ((mWidth > 0 || mHeight > 0) && (isEffectLayer() || isContainerLayer())) {
            throw new IllegalStateException("Only buffer layers can set a valid buffer size.");
        }
		
        if ((mFlags & FX_SURFACE_MASK) == FX_SURFACE_NORMAL) {
            setBLASTLayer();
        }

        return new SurfaceControl(
            mSession, mName, mWidth, mHeight, mFormat, mFlags, mParent, mMetadata,
            mLocalOwnerView, mCallsite);
    }

    
    private SurfaceControl(SurfaceSession session, String name, int w, int h, int format, int flags,
                           SurfaceControl parent, SparseIntArray metadata, WeakReference<View> localOwnerView,
                           String callsite){

        mName = name;
        mWidth = w;
        mHeight = h;
        mLocalOwnerView = localOwnerView;
        Parcel metaParcel = Parcel.obtain();

        //23 通过JNI创建SurfaceControl
        mNativeObject = nativeCreate(session, name, w, h, format, flags,
                                     parent != null ? parent.mNativeObject : 0, metaParcel);

        mNativeHandle = nativeGetHandle(mNativeObject);
    }
}

public Builder setBLASTLayer() {
    return setFlags(FX_SURFACE_BLAST, FX_SURFACE_MASK);
}
```

注释21和22先对Surface参数进行了校验，比如宽高不能为负数，宽高大于0时surface类型不能是Effect和Container类型，如果是Normal类型的直接设置为blast类型，这里介绍下几个Surface类型

+ FX_SURFACE_NORMAL，代表了一个标准Surface，这个是默认设置。
+ FX_SURFACE_EFFECT，代表了一个有纯色或者阴影效果的Surface。
+ FX_SURFACE_CONTAINER，代表了一个容器类Surface，这种Surface没有缓冲区，只是用来作为其他Surface的容器，或者是它自己的InputInfo的容器。
+ FX_SURFACE_BLAST。结合上面代码可知，其等同于FX_SURFACE_NORMAL。我们用到的大部分即是这种。
+ FX_SURFACE_MASK。标识位。某个Surface类型与其标识位做位运算来计算出具体的Surface类型。

注释23通过JNI创建了SurfaceControl

> frameworks/base/core/jni/android_view_SurfaceControl.cpp

```cpp
static jlong nativeCreate(JNIEnv* env, jclass clazz, jobject sessionObj,
                          jstring nameStr, jint w, jint h, jint format, jint flags, jlong parentObject,
                          jobject metadataParcel) {
    sp<SurfaceComposerClient> client;
    //24 基于SurfaceSession去获取SurfaceComposerClient
    if (sessionObj != NULL) {
        client = android_view_SurfaceSession_getClient(env, sessionObj);
    } else {
        client = SurfaceComposerClient::getDefault();
    }
    sp<SurfaceControl> surface;
	//...
    //25 SurfaceComposerClient.createSurfaceChecked去创建SurfaceControl
    status_t err = client->createSurfaceChecked(String8(name.c_str()), w, h, format, &surface,flags, parentHandle, std::move(metadata));
    
    
    return reinterpret_cast<jlong>(surface.get());
}
```

+ 注释24会通过java层传过来的SurfaceSession的地址获取SurfaceComposerClient，由上面与SurfaceFlinger创建连接可知，此SurfaceComposerClient是在windowState第一次attach时通过jni创建，java层持有了其地址
+ 注释25通过SurfaceComposerClient.createSurfaceChecked去创建SurfaceControl，传新创建的surface地址以供创建后赋值

> frameworks/native/libs/gui/SurfaceComposerClient.cpp

```cpp
status_t SurfaceComposerClient::createSurfaceChecked(const String8& name, uint32_t w, uint32_t h,
                                                     PixelFormat format,
                                                     sp<SurfaceControl>* outSurface, uint32_t flags,
                                                     const sp<IBinder>& parentHandle,
                                                     LayerMetadata metadata,
                                                     uint32_t* outTransformHint) {
    sp<SurfaceControl> sur;
    status_t err = mStatus;

    if (mStatus == NO_ERROR) {
        sp<IBinder> handle;
        sp<IGraphicBufferProducer> gbp;

        uint32_t transformHint = 0;
        int32_t id = -1;
        //26 调用服务端SurfaceFlinger进程Client的createSurface
        err = mClient->createSurface(name, w, h, format, flags, parentHandle, std::move(metadata),
                                     &handle, &gbp, &id, &transformHint);

        if (outTransformHint) {
            *outTransformHint = transformHint;
        }

        if (err == NO_ERROR) {
            //27 基于注释26返回的handle，system_server进程中创建个SurfaceControl
            *outSurface =
                new SurfaceControl(this, handle, gbp, id, w, h, format, transformHint, flags);
        }
    }
    return err;
}
```

+ 注释26通过binder通信调用到SurfaceFlinger服务端Client这个binder的createSurface方法，这里传了个新创建的handle这个IBinder的地址进去，其实新创建的Surface，对SurfaceFlinger来说是Layer，会被存在这个Binder的真实对象中。
+ 注释27基于26的返回handle，在system_server进程中创建个SurfaceControl

再看注释26中SurfaceFlinger进程创建Surface，即Layer的过程

> frameworks/native/services/surfaceflinger/Client.cpp

```c++
status_t Client::createSurface(const String8& name, uint32_t /* w */, uint32_t /* h */,
                               PixelFormat /* format */, uint32_t flags,
                               const sp<IBinder>& parentHandle, LayerMetadata metadata,
                               sp<IBinder>* outHandle, sp<IGraphicBufferProducer>* /* gbp */,
                               int32_t* outLayerId, uint32_t* outTransformHint) {

    return mFlinger->createLayer(args, outHandle, parentHandle, outLayerId, nullptr,
                                 outTransformHint);
}
```

Client.createSurface调用到了SurfaceFlinger.createLayer，从方法名称上也可以理解，`对SurfaceFlinger来说，创建Surface也就是创建layer`

> frameworks/native/services/surfaceflinger/SurfaceFlinger.cpp

```cpp
status_t SurfaceFlinger::createLayer(LayerCreationArgs& args, sp<IBinder>* outHandle,
                                     const sp<IBinder>& parentHandle, int32_t* outLayerId,
                                     const sp<Layer>& parentLayer, uint32_t* outTransformHint) {
    //根据不同的Surface类型，创建不同的layer
    switch (args.flags & ISurfaceComposerClient::eFXSurfaceMask) {
        case ISurfaceComposerClient::eFXSurfaceBufferQueue:
        case ISurfaceComposerClient::eFXSurfaceBufferState: {
            //28 创建BufferStateLayer
            result = createBufferStateLayer(args, outHandle, &layer);
            std::atomic<int32_t>* pendingBufferCounter = layer->getPendingBufferCounter();
            if (pendingBufferCounter) {
                std::string counterName = layer->getPendingBufferCounterName();
                mBufferCountTracker.add((*outHandle)->localBinder(), counterName,
                                        pendingBufferCounter);
            }
        } break;
        case ISurfaceComposerClient::eFXSurfaceEffect:
            result = createEffectLayer(args, outHandle, &layer);
            break;
        case ISurfaceComposerClient::eFXSurfaceContainer:
            result = createContainerLayer(args, outHandle, &layer);
            break;
        default:
            result = BAD_VALUE;
            break;
    }
    
    if (result != NO_ERROR) {
        return result;
    }

    bool addToRoot = args.addToRoot && callingThreadHasUnscopedSurfaceFlingerAccess();
    //30 添加客户端的layer
    result = addClientLayer(args.client, *outHandle, layer, parent, addToRoot, outTransformHint);
    if (result != NO_ERROR) {
        return result;
    }
    *outLayerId = layer->sequence;
    return result;
}

status_t SurfaceFlinger::createBufferStateLayer(LayerCreationArgs& args, sp<IBinder>* handle,
                                                sp<Layer>* outLayer) {
    args.textureName = getNewTexture();
    //29 通过默认工厂createBufferStateLayer来创建
    *outLayer = getFactory().createBufferStateLayer(args);
    *handle = (*outLayer)->getHandle();
    return NO_ERROR;
}
```

SurfaceFlinger创建layer的核心代码。

首先，根据不同的surface类型创建不同的layer，跟我们上面看到的4种surface类型一一对应。对于大部分应都是走注释28创建BufferStateLayer，其是通过注释29处getFactory().createBufferStateLayer来创建BufferStateLayer的。注释30在创建完layer后会通过addClientLayer来记录客户端的layer。

> frameworks/native/services/surfaceflinger/SurfaceFlingerDefaultFactory.cpp

```cpp
sp<ContainerLayer> DefaultFactory::createContainerLayer(const LayerCreationArgs& args) {
    return new ContainerLayer(args);
}

sp<BufferQueueLayer> DefaultFactory::createBufferQueueLayer(const LayerCreationArgs& args) {
    return new BufferQueueLayer(args);
}

sp<BufferStateLayer> DefaultFactory::createBufferStateLayer(const LayerCreationArgs& args) {
    return new BufferStateLayer(args);
}

```

SurfaceFlinger的默认工厂SurfaceFlingerDefaultFactory创建这3个类型的layer只是new了其对象，他们都继承于Layer，其初始化时会先执行onFirstRef

```cpp
//Layer.cpp
void Layer::onFirstRef() {
    mFlinger->onLayerFirstRef(this);
}

//SurfaceFlinger.cpp
std::atomic<size_t> mNumLayers = 0;
void SurfaceFlinger::onLayerFirstRef(Layer* layer) {
    mNumLayers++;
    //如果当前layer没有父layer，调用mScheduler->registerLayer注册
    if (!layer->isRemovedFromCurrentState()) {
        mScheduler->registerLayer(layer);
    }
}
```

SurfaceFlinger里的mNumLayers记录所有的layer数量，此时BufferStateLayer已经创建好了。  

我们继续看注释29处基于layer获取Handle的方法getHandle

```cpp
//Layer.cpp
sp<IBinder> Layer::getHandle() {
    if (mGetHandleCalled) {
        return nullptr;
    }
    mGetHandleCalled = true;
    return new Handle(mFlinger, this);
}
```

这个写法，只有第一次调用才返回了new handler，再调用就返回空，即getHandle期望用户只在创建layer时调用一次。

```cpp
//layer.h
class Handle : public BBinder, public LayerCleaner {
    public:
    Handle(const sp<SurfaceFlinger>& flinger, const sp<Layer>& layer)
        : LayerCleaner(flinger, layer, this), owner(layer) {}
    const String16& getInterfaceDescriptor() const override { return kDescriptor; }

    static const String16 kDescriptor;
    wp<Layer> owner;
};
```

Handle其实就是个binder对象，相当于SurfaceFlinger把此对象的代理对象返回给SurfaceComposerClient的进程，即wms所在的进程-system_server进程，再在注释27处基于此binder对象来创建SurfaceControl对象，再把SurfaceControl对象的内存地址传给wms进程的java端，后面WMS就可以基于此地址来操作SurfaceControl，即变相操作SurfaceFlinger的layer完成合成等操作。

到这里完成了layer和handle的创建以及在handle里绑定了layer，但是Client和SurfaceFlinger并不清楚其关系，所以在注释30处SurfaceFlinger::createLayer调用addClientLayer来完成client和SurfaceFlinger对两者的记录。

```c++
//SurfaceFlinger.h
enum {
    eTransactionNeeded = 0x01,
    eTraversalNeeded = 0x02,
    eDisplayTransactionNeeded = 0x04,
    eTransformHintUpdateNeeded = 0x08,
    eTransactionFlushNeeded = 0x10,
    eTransactionMask = 0x1f,
};
struct LayerCreatedState {
    LayerCreatedState(const wp<Layer>& layer, const wp<Layer> parent, bool addToRoot)
        : layer(layer), initialParent(parent), addToRoot(addToRoot) {}
    wp<Layer> layer;
    //如果有父类layer，会记录在这里
    wp<Layer> initialParent;
    bool addToRoot;
};
//记录SurfaceFlinger所有新创建的layer
std::vector<LayerCreatedState> mCreatedLayers GUARDED_BY(mCreatedLayersLock);

//SurfaceFlinger.cpp
status_t SurfaceFlinger::
    addClientLayer(const sp<Client>& client, const sp<IBinder>& handle,
                                        const sp<Layer>& layer, const wp<Layer>& parent,
                                        bool addToRoot, uint32_t* outTransformHint) {
	//...
    {
        std::scoped_lock<std::mutex> lock(mCreatedLayersLock);
        //31 把新创建的layer缓存到SurfaceFlinger的mCreatedLayers中
        mCreatedLayers.emplace_back(layer, parent, addToRoot);
    }
    if (client != nullptr) {
        //32 Client里记录handle和layer
        client->attachLayer(handle, layer);
    }
    //33 设置当前事务的标记为eTransactionNeeded
    setTransactionFlags(eTransactionNeeded);
    return NO_ERROR;
}

//Client.cpp
DefaultKeyedVector< wp<IBinder>, wp<Layer> > mLayers;
void Client::attachLayer(const sp<IBinder>& handle, const sp<Layer>& layer)
{
    Mutex::Autolock _l(mLock);
    mLayers.add(handle, layer);
}
```

+ 注释31是SurfaceFlinger里的mCreatedLayers缓存了所有新创建的layer。
+ 注释32是Client里记录Handle和layer的关系。可以看到在Client.cpp里由成员变量mLayers记录。
+ 注释33会通过改变事务的flag来触发SurfaceFlinger来管理此layer。需要注意的是Android11,12,13的实现均不完全一样，在Android13上是设置了事务的标记为eTransactionNeeded（0x01）

```c++
void SurfaceFlinger::setTransactionFlags(uint32_t mask, TransactionSchedule schedule,
                                         const sp<IBinder>& applyToken, FrameHint frameHint) {
    modulateVsync(&VsyncModulator::setTransactionSchedule, schedule, applyToken);
    if (const bool scheduled = mTransactionFlags.fetch_or(mask) & mask; !scheduled) {
        scheduleCommit(frameHint);
    }
}
void SurfaceFlinger::scheduleCommit(FrameHint hint) {
    if (hint == FrameHint::kActive) {
        mScheduler->resetIdleTimer();
    }
    notifyDisplayUpdateImminent();
    mScheduler->scheduleFrame();
}
void SurfaceFlinger::notifyDisplayUpdateImminent() {
    if (!mEarlyWakeUpEnabled) {
        mPowerAdvisor->notifyDisplayUpdateImminent();
        return;
    }
    #ifdef EARLY_WAKEUP_FEATURE
    //...
    mDisplayExtnIntf->NotifyEarlyWakeUp(true, false);
    #endif
}
```

Android13上c++事务这块调用关系(由scheduleCommit到commit的过程)没完全看懂。  

不过既然是基于事务有提交scheduleCommit，就有处理的地方，最终会调用到其commit方法。

```cpp
bool SurfaceFlinger::commit(nsecs_t frameTime, int64_t vsyncId, nsecs_t expectedVsyncTime) FTL_FAKE_GUARD(kMainThreadContext) {
    //...这里只关注layer创建的事务
    needsTraversal |= commitCreatedLayers();
    needsTraversal |= flushTransactionQueues(vsyncId);
    //...
}
bool SurfaceFlinger::commitCreatedLayers() {
    std::vector<LayerCreatedState> createdLayers;
    {
        std::scoped_lock<std::mutex> lock(mCreatedLayersLock);
        //先把SurfaceFlinger上面缓存的所有新创建的Layer拿出来存到本地变量createdLayers中
        createdLayers = std::move(mCreatedLayers);
        //清空SurfaceFlinger的缓存
        mCreatedLayers.clear();
        if (createdLayers.size() == 0) {
            return false;
        }
    }
    //遍历新创建的layer
    for (const auto& createdLayer : createdLayers) {
        handleLayerCreatedLocked(createdLayer);
    }
    //清空本地缓存
    createdLayers.clear();
    mLayersAdded = true;
    return true;
}
void SurfaceFlinger::handleLayerCreatedLocked(const LayerCreatedState& state) {
    //...
	//34 如果没有父layer，在mCurrentState.layersSortedByZ里添加layer
    if (parent == nullptr && addToRoot) {
        layer->setIsAtRoot(true);
        mCurrentState.layersSortedByZ.add(layer);
    } else if (parent == nullptr) {
        layer->onRemovedFromCurrentState();
    } else if (parent->isRemovedFromCurrentState()) {
        parent->addChild(layer);
        layer->onRemovedFromCurrentState();
    } else {
        parent->addChild(layer);
    }
}


bool SurfaceFlinger::flushTransactionQueues(int64_t vsyncId) {
   	//...
    return applyTransactions(transactions, vsyncId);
}
bool SurfaceFlinger::applyTransactions(std::vector<TransactionState>& transactions,int64_t vsyncId) {
    for (auto& transaction : transactions) {
        needsTraversal |=
            applyTransactionState(transaction.frameTimelineInfo, transaction.states,
                                  transaction.displays, transaction.flags,
                                  transaction.inputWindowCommands,
                                  transaction.desiredPresentTime, transaction.isAutoTimestamp,
                                  transaction.buffer, transaction.postTime,
                                  transaction.permissions, transaction.hasListenerCallbacks,
                                  transaction.listenerCallbacks, transaction.originPid,
                                  transaction.originUid, transaction.id);
    }
    return needsTraversal;
}
bool SurfaceFlinger::applyTransactionState(...Vector<ComposerState>& states...){
    //...
    for (int i = 0; i < states.size(); i++) {
        //35 设置layer的具体信息
        clientStateFlags |= setClientStateLocked(frameTimelineInfo, state, desiredPresentTime,
                                                 isAutoTimestamp, postTime, permissions);
        
        if ((flags & eAnimation) && state.state.surface) {
            //可以直接通过fromHandle来根据handle拿layer
            if (const auto layer = fromHandle(state.state.surface).promote()) {
                using LayerUpdateType = scheduler::LayerHistory::LayerUpdateType;
                mScheduler->recordLayerHistory(layer.get(),
                                               isAutoTimestamp ? 0 : desiredPresentTime,
                                               LayerUpdateType::AnimationTX);
            }
        }

    }
}

uint32_t SurfaceFlinger::setClientStateLocked(... ComposerState& composerState...){
    layer_state_t& s = composerState.state;
    //...
    const uint64_t what = s.what;
    sp<Layer> layer = nullptr;
    if (s.surface) {
        layer = fromHandle(s.surface).promote();
    } 
    if (layer == nullptr) {
        return 0;  
    }
    
    //根据参数对layer进行设置
    if (what & layer_state_t::ePositionChanged) {
        if (layer->setPosition(s.x, s.y)) {
            flags |= eTraversalNeeded;
        }
    }
    if (what & layer_state_t::eSizeChanged) {
        if (layer->setSize(s.w, s.h)) {
            flags |= eTraversalNeeded;
        }
    }
    //...省略其他属性设置
    return flags;
}
```

commit这块稍微有点复杂，通过commitCreatedLayers和flushTransactionQueues来判断needsTraversal

+ 注释34处是commitCreatedLayers里往SurfaceFlinger的mCurrentState.layersSortedByZ里缓存了所有的layer，相当于SurfaceFlinger中记录了所有管理的layer
+ 注释35是取出事务处理的流程，在setClientStateLocked里会SF会根据配置来对Layer进行处理。需要注意的是layer_state_t的surface参数其实就是handle。

```c++
//LayerState.h
struct ComposerState {
    layer_state_t state;
    status_t write(Parcel& output) const;
    status_t read(const Parcel& input);
};
struct layer_state_t {
    status_t write(Parcel& output) const;
    status_t read(const Parcel& input);
    sp<IBinder> surface;
    uint32_t w;
    uint32_t h;
    sp<SurfaceControl> reparentSurfaceControl;
    sp<SurfaceControl> relativeLayerSurfaceControl;
    float shadowRadius;
    //...
}
```

这个layer_state_t里包括了layer的所有信息。

再看注释27中SurfaceComposerClient创建完Surface后基于Handle构建new SurfaceControl

```c++
//SurfaceComposerClient.cpp
*outSurface = new SurfaceControl(this, handle, gbp, id, w, h, format, transformHint, flags);

//SurfaceControl.cpp
SurfaceControl::SurfaceControl(const sp<SurfaceComposerClient>& client, const sp<IBinder>& handle,
                               const sp<IGraphicBufferProducer>& gbp, int32_t layerId,
                               uint32_t w, uint32_t h, PixelFormat format, uint32_t transform,
                               uint32_t flags)
    : mClient(client),
mHandle(handle),
mGraphicBufferProducer(gbp),
mLayerId(layerId),
mTransformHint(transform),
mWidth(w),
mHeight(h),
mFormat(format),
mCreateFlags(flags) {}

//SurfaceControl.h
sp<SurfaceComposerClient>   mClient;
sp<IBinder>                 mHandle;
sp<IGraphicBufferProducer>  mGraphicBufferProducer;
```

handle这个binder对象保存在SurfaceControl中并把SurfaceControl的地址返回给java层，其存在WindowSurfaceController的mSurfaceControl，再把其数据拷贝给客户端传过来的SurfaceControl中，相当于客户端的ViewRootImpl和服务端的WindowSurfaceController持有的同一个由JNI层创建的SurfaceControl对象地址，并且都持有了SF创建的layer的代理地址即handle。

到这里创建Layer的过程就结束了。  

做个小结：

+ 客户端进程：在performTraversals完成绘制流程中，会先判断窗口是否有改变，有的话会通过session.relayout调用wms.relayoutWindow去处理窗口，这里会把客户端新建的SurfaceControl传进去以供WMS里面获取后传过来，如果wms返回了数据，会把其存到本地的Surface对象中。
+ WMS进程relayoutWindow里会先拿之前创建的WindowState判断是否要重新布局，需要的话就创建个WindowSurfaceController对象，在其构造函数中会基于构建者模式创建SurfaceControl，SurfaceControl的构造函数会通过JNI去创建SurfaceControl。如果JNI创建成功，就会把SurfaceControl数据拷贝到客户端传进来的对象里。
+ 再看JNI创建SurfaceControl的过程，此时还在wms进程，会先通过之前与SF创建连接时拿到的SurfaceComposerClient调用SF进程Client服务端的createSurfaceChecked方法，其会调用SF的createLayer，主要先根据不同的surface类型创建不同的layer，大部分情况下都是创建BufferStateLayer，然后再通过layer.getHandle获取一个Binder对象，此方法只在创建layer时调用一次，再次调用会返回空。此Handle主要是存到给WMS进程返回的SurfaceControl中，以供WMS通过Handle来操作具体的layer

##### Surface的初始化

上一小节中，客户端和WMS内部的SurfaceControl都已关联了SF创建的SurfaceControl的地址，还有handle的代理对象的地址。我们继续看ViewRootImpl.relayoutWindow后面的流程注释16处 

```java
//ViewRootImpl.relayoutWindow
private int relayoutWindow(WindowManager.LayoutParams params...){
    // 注释16 认情况下useBLAST都是返回true
    if (!useBLAST()) {
        mSurface.copyFrom(mSurfaceControl);
    } else {
        updateBlastSurfaceIfNeeded();
    }
}

void updateBlastSurfaceIfNeeded() {
    if (!mSurfaceControl.isValid()) {
        return;
    }

    //如果已经创建过mBlastBufferQueue，并且执行其update返回的false，即无更新时直接返回
    if (mBlastBufferQueue != null && mBlastBufferQueue.isSameSurfaceControl(mSurfaceControl)) {
        mBlastBufferQueue.update(mSurfaceControl,
                                 mSurfaceSize.x, mSurfaceSize.y,
                                 mWindowAttributes.format);
        return;
    }
    if (mBlastBufferQueue != null) {
        mBlastBufferQueue.destroy();
    }
    //36 这里会创建BLASTBufferQueue
    mBlastBufferQueue = new BLASTBufferQueue(mTag, mSurfaceControl,
                                             mSurfaceSize.x, mSurfaceSize.y, mWindowAttributes.format);
    mBlastBufferQueue.setTransactionHangCallback(sTransactionHangCallback);
    ScrollOptimizer.setBLASTBufferQueue(mBlastBufferQueue);
    //37 执行BLASTBufferQueue的createSurface来创建Surface
    Surface blastSurface = mBlastBufferQueue.createSurface();
    mSurface.transferFrom(blastSurface);
}

```

注释36会先创建BLASTBufferQueue，再在注释37处通过执行其createSurface来创建Surface。我们先看BLASTBufferQueue的构造函数

> frameworks/base/graphics/java/android/graphics/BLASTBufferQueue.java

```java
/** Create a new connection with the surface flinger. */
public BLASTBufferQueue(String name, SurfaceControl sc, int width, int height,
                        @PixelFormat.Format int format) {
    this(name, true /* updateDestinationFrame */);
    update(sc, width, height, format);
}

public BLASTBufferQueue(String name, boolean updateDestinationFrame) {
    mNativeObject = nativeCreate(name, updateDestinationFrame);
}
```

通过JNI创建了BLASTBufferQueue

> frameworks/base/core/jni/android_graphics_BLASTBufferQueue.cpp

```cpp
static jlong nativeCreate(JNIEnv* env, jclass clazz, jstring jName,
                          jboolean updateDestinationFrame) {
    ScopedUtfChars name(env, jName);
    //直接new了BLASTBufferQueue并返回其地址
    sp<BLASTBufferQueue> queue = new BLASTBufferQueue(name.c_str(), updateDestinationFrame);
    queue->incStrong((void*)nativeCreate);
    return reinterpret_cast<jlong>(queue.get());
}
```

这里直接new了BLASTBufferQueue并返回其地址

> frameworks/native/libs/gui/BLASTBufferQueue.cpp

```cpp
sp<IGraphicBufferConsumer> mConsumer;
sp<IGraphicBufferProducer> mProducer;
BLASTBufferQueue::BLASTBufferQueue(const std::string& name, bool updateDestinationFrame)
    : mSurfaceControl(nullptr),
mSize(1, 1),
mRequestedSize(mSize),
mFormat(PIXEL_FORMAT_RGBA_8888),
mTransactionReadyCallback(nullptr),
mSyncTransaction(nullptr),
mUpdateDestinationFrame(updateDestinationFrame) {
    if (name.find("SurfaceView") != std::string::npos) {
        sLayerName = name;
        pthread_once(&sCheckAppTypeOnce, initAppType);
    }
    //37 创建BufferQueue
    createBufferQueue(&mProducer, &mConsumer);
    // since the adapter is in the client process, set dequeue timeout
    // explicitly so that dequeueBuffer will block
    mProducer->setDequeueTimeout(std::numeric_limits<int64_t>::max());

    // safe default, most producers are expected to override this
    //设置生产者执行一次dequeue可以获得的最大缓冲区数为2
    mProducer->setMaxDequeuedBufferCount(2);
    //38 把mConsumer包装到BLASTBufferItemConsumer，并为其设置缓冲区被释放后的监听为自己（即BLASTBufferQueue）
    mBufferItemConsumer = new BLASTBufferItemConsumer(mConsumer,
                                                      GraphicBuffer::USAGE_HW_COMPOSER |
                                                      GraphicBuffer::USAGE_HW_TEXTURE,
                                                      1, false, this);
    static int32_t id = 0;
    mName = name + "#" + std::to_string(id);
    auto consumerName = mName + "(BLAST Consumer)" + std::to_string(id);
    mQueuedBufferTrace = "QueuedBuffer - " + mName + "BLAST#" + std::to_string(id);
    id++;
    mBufferItemConsumer->setName(String8(consumerName.c_str()));
    // 设置当一个新的帧变为可用后会被通知的监听器对象。
    mBufferItemConsumer->setFrameAvailableListener(this);
    // 设置当一个旧的缓冲区被释放后会被通知的监听器对象为自己
    mBufferItemConsumer->setBufferFreedListener(this);

    // ComposerService::getComposerService()即拿到SF,这里获取的缓冲区的数量。  
    ComposerService::getComposerService()->getMaxAcquiredBufferCount(&mMaxAcquiredBuffers);
    //设置消费者可以一次获取的缓冲区的最大值，默认为1
    mBufferItemConsumer->setMaxAcquiredBufferCount(mMaxAcquiredBuffers);
    mCurrentMaxAcquiredBufferCount = mMaxAcquiredBuffers;

    //...
}
```

BLASTBufferQueue的构造函数注释37处通过createBufferQueue创建BufferQueue，传进去的mProducer和mConsumer即是IGraphicBufferProducer和IGraphicBufferConsumer类型，然后在注释38处为mConsumer包装成BLASTBufferItemConsumer，并为其设置监听。先看注释37处createBufferQueue

```cpp
void BLASTBufferQueue::createBufferQueue(sp<IGraphicBufferProducer>* outProducer,
                                         sp<IGraphicBufferConsumer>* outConsumer) {
    //39 先创建BufferQueueCore，再根据创建的BufferQueueCore创建BBQBufferQueueProducer和BufferQueueConsumer
    sp<BufferQueueCore> core(new BufferQueueCore());
    sp<IGraphicBufferProducer> producer(new BBQBufferQueueProducer(core));

    sp<BufferQueueConsumer> consumer(new BufferQueueConsumer(core));
    consumer->setAllowExtraAcquire(true);

    *outProducer = producer;
    *outConsumer = consumer;
}
```

注释39会先创建BufferQueueCore，再根据创建的BufferQueueCore创建BBQBufferQueueProducer和BufferQueueConsumer，再赋值给传进来的outProducer和outConsumer。注意，此时是在客户端的进程中创建的BufferQueue的生产者和消费者。
