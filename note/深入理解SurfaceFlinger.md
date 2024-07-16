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

构造函数里的其他参数初始化不再细看，主要看下mCompositionEngine（合成引擎）的初始化。

> frameworks/native/services/surfaceflinger/SurfaceFlingerDefaultFactory.cpp 

```cpp
std::unique_ptr<compositionengine::CompositionEngine> DefaultFactory::createCompositionEngine() {
    return compositionengine::impl::createCompositionEngine();
}
//创建HWComposer，后面init方法有用到，这里先粘贴过来
std::unique_ptr<HWComposer> DefaultFactory::createHWComposer(const std::string& serviceName) {
    return std::make_unique<android::impl::HWComposer>(serviceName);
}

//frameworks/native/services/surfaceflinger/CompositionEngine/src/CompositionEngine.cpp
std::unique_ptr<compositionengine::CompositionEngine> createCompositionEngine() {
    return std::make_unique<CompositionEngine>();
}
```

> make_unique是C++14引入的一个函数模板,用于创建并返回一个指向动态分配对象的unique_ptr智能指针。它是为了简化代码,避免手动使用new和delete,以及确保资源的正确释放而设计的。其实就是创建并返回了个指定对象的智能指针。

这里直接在surfaceflinger的构造函数中创建了合成引擎。继续看注释1处`flinger->init()`执行了SurfaceFlinger的init方法

```cpp
void SurfaceFlinger::init() {
    //...
    //基于build模式创建RenderEngine（渲染引擎），再把其设置到mCompositionEngine（合成引擎）里
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
	//创建HWComposer（硬件合成）对象，再把其设置到mCompositionEngine里
    mCompositionEngine->setHwComposer(getFactory().createHWComposer(mHwcServiceName));
    //设置硬件合成的监听为自己
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

init时会先对CompositionEngine设置RenderEngine和HwComposer（通过HAL层的HWComposer硬件模块或者软件模拟产生Vsync信号），再看注释4处`processDisplayHotplugEventsLocked`方法 **创建与初始化FramebufferSurface（消费者）和RenderSurface（生产者）流程**

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
    sp<compositionengine::DisplaySurface> displaySurface;
    sp<IGraphicBufferProducer> producer;
    sp<IGraphicBufferProducer> bqProducer;
    sp<IGraphicBufferConsumer> bqConsumer;
    //创建BufferQueue,获取到生产者和消费者
    getFactory().createBufferQueue(&bqProducer, &bqConsumer, false);
    //5-1 创建了FramebufferSurface对象，FramebufferSurface继承自compositionengine::DisplaySurface
    //FramebufferSurface是作为消费者的角色工作的，消费SF GPU合成后的图形数据
    displaySurface = sp<FramebufferSurface>::make(getHwComposer(), *displayId, bqConsumer,
                                     state.physical->activeMode->getResolution(),
                                     ui::Size(maxGraphicsWidth, maxGraphicsHeight));
    producer = bqProducer;
    //5-2 创建DisplayDevice，其又去创建RenderSurface，作为生产者角色工作，displaySurface就是FramebufferSurface对象
    auto display = setupNewDisplayDeviceInternal(displayToken, std::move(compositionDisplay), state,displaySurface, producer);

    if (display->isPrimary()) {
        //5-3 进行Scheduler初始化
        initScheduler(display);
    }
}
```

+ 注释5-1处创建并初始化了FramebufferSurface，其作为消费者消费SF GPU合成后的图形数据

+ 注释5-2处创建DisplayDevice，其又去创建RenderSurface，作为生产者角色工作，这里把注释5-1创建的FramebufferSurface传进去

+ 注释5-3处执行initScheduler初始化Scheduler

先看注释5-1和2处

```c++
//FramebufferSurface.cpp
//其构造函数主要对传进来的消费者进行了初始化
FramebufferSurface::FramebufferSurface(HWComposer& hwc, PhysicalDisplayId displayId,
                                       const sp<IGraphicBufferConsumer>& consumer,
                                       const ui::Size& size, const ui::Size& maxSize){
    mName = "FramebufferSurface";
    mConsumer->setConsumerName(mName);
    mConsumer->setConsumerUsageBits(GRALLOC_USAGE_HW_FB |
                                       GRALLOC_USAGE_HW_RENDER |
                                       GRALLOC_USAGE_HW_COMPOSER);
    const auto limitedSize = limitSize(size);
    mConsumer->setDefaultBufferSize(limitedSize.width, limitedSize.height);
    mConsumer->setMaxAcquiredBufferCount(
            SurfaceFlinger::maxFrameBufferAcquiredBuffers - 1);
}
sp<DisplayDevice> SurfaceFlinger::setupNewDisplayDeviceInternal(...){
    //创建DisplayDevice构造时需的参数
    DisplayDeviceCreationArgs creationArgs(this, getHwComposer(), displayToken, compositionDisplay);
    //displaySurface即FramebufferSurface
    creationArgs.displaySurface = displaySurface;
    //producer是前面processDisplayAdded中创建的
    auto nativeWindowSurface = getFactory().createNativeWindowSurface(producer);
    auto nativeWindow = nativeWindowSurface->getNativeWindow();
    creationArgs.nativeWindow = nativeWindow;
    //...省略构建其他参数过程
    
	//根据上面构建的参数创建DisplayDevice
    //creationArgs.nativeWindow会把前面创建的producer关联到了DisplayDevice
    sp<DisplayDevice> display = getFactory().createDisplayDevice(creationArgs);
}

DisplayDevice::DisplayDevice(DisplayDeviceCreationArgs& args){
    mCompositionDisplay->editState().isSecure = args.isSecure;
    //创建RenderSurface，args.nativeWindow 即为producer,指向生产者
    mCompositionDisplay->createRenderSurface(
        compositionengine::RenderSurfaceCreationArgsBuilder()
        .setDisplayWidth(ANativeWindow_getWidth(args.nativeWindow.get()))
        .setDisplayHeight(ANativeWindow_getHeight(args.nativeWindow.get()))
        .setNativeWindow(std::move(args.nativeWindow))
        .setDisplaySurface(std::move(args.displaySurface))
        .setMaxTextureCacheSize(
            static_cast<size_t>(SurfaceFlinger::maxFrameBufferAcquiredBuffers))
        .build());
    //...
    //执行initialize初始化RenderSurface
    mCompositionDisplay->getRenderSurface()->initialize();
}
//RenderSurface.cpp
RenderSurface::RenderSurface(const CompositionEngine& compositionEngine, Display& display,
                             const RenderSurfaceCreationArgs& args)
      : mCompositionEngine(compositionEngine),
        mDisplay(display),
		//指向生产者
        mNativeWindow(args.nativeWindow),
		//即FramebufferSurface,指向消费者
        mDisplaySurface(args.displaySurface),
        mSize(args.displayWidth, args.displayHeight),
        mMaxTextureCacheSize(args.maxTextureCacheSize) {
    LOG_ALWAYS_FATAL_IF(!mNativeWindow);
}
void RenderSurface::initialize() {
    ANativeWindow* const window = mNativeWindow.get();
    //作为生产者和BufferQueue建立连接
    int status = native_window_api_connect(window, NATIVE_WINDOW_API_EGL);
    //并设置了format为RGBA_8888，usage为GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE
    status = native_window_set_buffers_format(window, HAL_PIXEL_FORMAT_RGBA_8888);
    status = native_window_set_usage(window, DEFAULT_USAGE);
}
constexpr auto DEFAULT_USAGE = GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_TEXTURE;
```

创建DisplayDevice时会去创建RenderSurface，RenderSurface在初始化时会作为生产者与BufferQueue建立连接，另外RenderSurface还持有了消费者FramebufferSurface的引用DisplaySurface。这里先有个印象，FramebufferSurface是作为消费者消费SF GPU合成后的图形数据，而RenderSurface作为生产者生产图形数据。盗一张别人的图来说明RenderSurface和FramebufferSurface的关系

![RenderSurface和FrameBufferSurface的关系](img/RenderSurface和FrameBufferSurface.png)

再看注释5-3处执行initScheduler

```c++
void SurfaceFlinger::initScheduler(const sp<DisplayDevice>& display) {
    //如果初始化过就直接return，所以后面初始化Scheduler只会执行一次
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

最上面注释1处的init过程就看完了，先做个小结

+ surfaceflinger的构造函数中会先创建CompositionEngine

+ 在init方法里会先创建RenderEngine和HwComposer，再把其关联到CompositionEngine中。之后创建与初始化FramebufferSurface（消费者）和RenderSurface（生产者）

+ 在initScheduler中初始化时会创建两个eventthread线程：app和appsf，分别用来接收vsync后通知app完成绘制和sf来完成合成，两个线程基于不同的偏移量，保证app线程执行完渲染后再由appsf线程完成合成。

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

如果绑定到ServerManager的surfaceflinger服务挂掉的话，这里会重新执行开机动画，表现出来的就是机器又重启了。

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

到这里再完整总结下，SurfaceFlinger启动过程是从SurfaceFlinger.rc配置执行了main_surfaceflinger.cpp的main方法，这里会先创建SurfaceFlinger对象并执行其init方法，这里会先创建渲染引擎，硬件合成，再把其关联到合成引擎中，然后创建FrameBufferSurface（消费者）和RenderSurface（生产者） ，初始化scheduler，此时会创建两个EventThread线程：app，appsf线程，接收到vsync信号后app线程通知客户端执行绘制流程，appsf线程在一段时间后执行合成流程。初始化完之后surfaceflinger会发布到ServiceManager中，然后执行run在主线程执行waitMessage等待消息，内部是通过Looper.poolOnce去获取消息。  

SurfaceFlinger准备好后，就等待其他进程的召唤了。

#### SurfaceFlinger工作流程

我们分析下从应用启动流程到屏幕显示出画面的过程。

##### SurfaceSession的创建

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

总结下，应用在启动中初始化ViewRootImpl时会创建跟WMS的连接Session，之后在ViewRootImpl.setView中会通过session调用到WMS.addWindow，这里会创建WindowState，并执行其attach，首次会通过JNI创建SurfaceSession，其实是返回了SurfaceComposerClient对象的地址，此对象里持有的mClient对象是通过surfaceflinger创建的Client对象，也就是说WMS所在是system_server进程持有了SF创建的Client代理对象。

##### SurfaceControl创建流程

下面分析View绘制到Surface的创建过程。Surface创建需要先了解SurfaceControl的创建。    

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
    //...
    //先准备窗口，再开始具体绘制流程
    relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);
	//具体绘制流程，performMeasure，performLayout，performDraw
    //...
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
        err = mClient->createSurface(name, w, h, format, flags, parentHandle, std::move(metadata),&handle, &gbp, &id, &transformHint);

        if (outTransformHint) {
            *outTransformHint = transformHint;
        }

        if (err == NO_ERROR) {
            //27 基于注释26返回的handle，system_server进程中创建个SurfaceControl
            *outSurface = new SurfaceControl(this, handle, gbp, id, w, h, format, transformHint, flags);
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

Client.createSurface调用到了SurfaceFlinger.createLayer，从方法名称上也可以理解为，`对SurfaceFlinger来说，创建Surface也就是创建layer`

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

SurfaceFlinger创建layer的流程。

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

Handle其实就是个binder对象，相当于SurfaceFlinger把此对象的代理对象返回给SurfaceComposerClient的进程，即wms所在的进程-system_server进程，再在注释27处基于此binder对象来创建SurfaceControl对象，再把SurfaceControl对象的内存地址传给wms进程的java端，再返回给客户端，客户端就可以基于此地址来操作SurfaceControl，即变相操作SurfaceFlinger的layer完成合成等操作。

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
+ 注释33会通过改变事务的flag来触发SurfaceFlinger来管理此layer。需要注意的是Android11,12,13的实现均不完全一样，在Android13上是设置了事务的标记为eTransactionNeeded（0x01），事务的相关逻辑在后面提交事务到SF里详细讲。


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

handle这个binder对象保存在SurfaceControl中并把SurfaceControl的地址返回给java层，其存在WindowSurfaceController的mSurfaceControl，再把其数据拷贝给客户端传过来的SurfaceControl中，相当于客户端的ViewRootImpl和服务端的WindowSurfaceController持有的同一个由JNI层创建的SurfaceControl对象地址，此SurfaceControl对象持有了SF创建的layer的代理地址即handle。

到这里创建Layer的过程就结束了。  

做个小结：

+ 客户端进程：在performTraversals完成绘制流程中，会先判断窗口是否有改变，有的话会通过session.relayout调用wms.relayoutWindow去处理窗口，这里会把客户端新建的SurfaceControl传进去以供WMS里面获取后传过来，如果wms返回了数据，会把其存到本地的Surface对象中。
+ WMS进程relayoutWindow里会先拿之前创建的WindowState判断是否要重新布局，需要的话就创建个WindowSurfaceController对象，在其构造函数中会基于构建者模式创建SurfaceControl，SurfaceControl的构造函数会通过JNI去创建SurfaceControl。如果JNI创建成功，就会把SurfaceControl数据拷贝到客户端传进来的对象里。
+ 再看JNI创建SurfaceControl的过程，此时还在wms进程，会先通过之前与SF创建连接时拿到的SurfaceComposerClient调用SF进程Client服务端的createSurfaceChecked方法，其会调用SF的createLayer，主要先根据不同的surface类型创建不同的layer，大部分情况下都是创建BufferStateLayer，然后再通过layer.getHandle获取一个Binder对象，此方法只在创建layer时调用一次，再次调用会返回空。此Handle主要是存到给WMS进程返回的SurfaceControl中，以供WMS通过Handle来操作具体的layer

##### Surface的创建

上一小节创建SurfaceControl过程中，客户端和WMS内部的SurfaceControl都已关联了jni创建的SurfaceControl的地址，还有SF创建的layer代理对象handle的地址。我们继续看ViewRootImpl.relayoutWindow后面的流程注释16处 

```java
//ViewRootImpl.relayoutWindow
private int relayoutWindow(WindowManager.LayoutParams params...){
    // 注释16 默认情况下useBLAST都是返回true
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

注释36会先创建BLASTBufferQueue（BBQ），再在注释37处通过执行其createSurface来创建Surface。我们先看BLASTBufferQueue的构造函数

> frameworks/base/graphics/java/android/graphics/BLASTBufferQueue.java

```java
/** Create a new connection with the surface flinger. */
public BLASTBufferQueue(String name, SurfaceControl sc, int width, int height,
                        @PixelFormat.Format int format) {
    //JNI层先创建BLASTBufferQueue
    this(name, true /* updateDestinationFrame */);
    //在把SurfaceControl关联进BLASTBufferQueue
    update(sc, width, height, format);
}

public BLASTBufferQueue(String name, boolean updateDestinationFrame) {
    mNativeObject = nativeCreate(name, updateDestinationFrame);
}
public void update(SurfaceControl sc, int width, int height, @PixelFormat.Format int format) {
    nativeUpdate(mNativeObject, sc.mNativeObject, width, height, format);
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
    //38 创建BufferQueue
    createBufferQueue(&mProducer, &mConsumer);
    // since the adapter is in the client process, set dequeue timeout
    // explicitly so that dequeueBuffer will block
    mProducer->setDequeueTimeout(std::numeric_limits<int64_t>::max());

    // safe default, most producers are expected to override this
    //设置生产者执行一次dequeue可以获得的最大缓冲区数为2
    mProducer->setMaxDequeuedBufferCount(2);
    //39 把mConsumer包装到BLASTBufferItemConsumer，并为其设置缓冲区被释放后的监听为自己（即BLASTBufferQueue）
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
    // 添加图形缓冲区可消费状态监听
    mBufferItemConsumer->setFrameAvailableListener(this);
    // 添加图形缓冲区可生产状态监听
    mBufferItemConsumer->setBufferFreedListener(this);

    // ComposerService::getComposerService()即拿到SF,这里获取的缓冲区的数量。  
    ComposerService::getComposerService()->getMaxAcquiredBufferCount(&mMaxAcquiredBuffers);
    //设置消费者可以一次获取的缓冲区的最大值，默认为1
    mBufferItemConsumer->setMaxAcquiredBufferCount(mMaxAcquiredBuffers);
    mCurrentMaxAcquiredBufferCount = mMaxAcquiredBuffers;

    //...
}
```

BLASTBufferQueue的构造函数注释38处通过createBufferQueue创建BufferQueue，传进去的mProducer和mConsumer即是IGraphicBufferProducer和IGraphicBufferConsumer类型，然后在注释39处为mConsumer包装成BLASTBufferItemConsumer，并为其设置监听。先看注释38处createBufferQueue

```cpp
void BLASTBufferQueue::createBufferQueue(sp<IGraphicBufferProducer>* outProducer,
                                         sp<IGraphicBufferConsumer>* outConsumer) {
    //40 先创建BufferQueueCore，再根据创建的BufferQueueCore创建BBQBufferQueueProducer和BufferQueueConsumer
    sp<BufferQueueCore> core(new BufferQueueCore());
    sp<IGraphicBufferProducer> producer(new BBQBufferQueueProducer(core));

    sp<BufferQueueConsumer> consumer(new BufferQueueConsumer(core));
    consumer->setAllowExtraAcquire(true);

    *outProducer = producer;
    *outConsumer = consumer;
}
```

注释40会先创建BufferQueueCore，再根据创建的BufferQueueCore创建BBQBufferQueueProducer和BufferQueueConsumer，再赋值给传进来的outProducer和outConsumer。注意，此时是在客户端的进程中创建的BufferQueue的生产者和消费者。再看注释39处创建BLASTBufferItemConsumer

```c++
//BufferItemConsumer.cpp
BufferItemConsumer::BufferItemConsumer(
        const sp<IGraphicBufferConsumer>& consumer, uint64_t consumerUsage,
        int bufferCount, bool controlledByApp) :
    ConsumerBase(consumer, controlledByApp)
{
    status_t err = mConsumer->setConsumerUsageBits(consumerUsage);
    if (bufferCount != DEFAULT_MAX_BUFFERS) {
        err = mConsumer->setMaxAcquiredBufferCount(bufferCount);
    }
}

//BufferItemConsumer.h
//BufferItemConsumer继承于ConsumerBase，调用BufferItemConsumer的构造函数时也会调用ConsumerBase的构造函数
class BufferItemConsumer: public ConsumerBase{}

//ConsumerBase.h
//ConsumerBase继承于ConsumerListener
class ConsumerBase : public virtual RefBase,protected ConsumerListener{}
            
//ConsumerBase.cpp
ConsumerBase::ConsumerBase(const sp<IGraphicBufferConsumer>& bufferQueue, bool controlledByApp) :
mAbandoned(false),
mConsumer(bufferQueue),
mPrevFinalReleaseFence(Fence::NO_FENCE) {
    mName = String8::format("unnamed-%d-%d", getpid(), createProcessUniqueId());

    wp<ConsumerListener> listener = static_cast<ConsumerListener*>(this);
    sp<IConsumerListener> proxy = new BufferQueue::ProxyConsumerListener(listener);

    //把mConsumer关联到BufferItemConsumer
    status_t err = mConsumer->consumerConnect(proxy, controlledByApp);
    mConsumer->setConsumerName(mName);
}

//BufferQueueConsumer.h
virtual status_t consumerConnect(const sp<IConsumerListener>& consumer,bool controlledByApp) {
    return connect(consumer, controlledByApp);
}

//BufferQueueConsumer.cpp
status_t BufferQueueConsumer::connect(
        const sp<IConsumerListener>& consumerListener, bool controlledByApp) {
	//...
    //41 BufferQueueConsumer里的mCore.mConsumerListener也记录BufferItemConsumer
    mCore->mConsumerListener = consumerListener;
    mCore->mConsumerControlledByApp = controlledByApp;
    return NO_ERROR;
}
```

在注释41处把BufferQueueConsumer里的mCore.mConsumerListener也记录BufferItemConsumer，mCore即创建BufferQueueConsumer时传进来的BufferQueueCore，这样就完成了BLASTBufferItemConsumer到BufferQueue的连接。这个过程其实就是准备了BBQBufferQueueProducer和BufferQueueConsumer（被包装到BLASTBufferItemConsumer里）  

小结：客户端进程创建BBQ还是通过JNI创建，先创建BufferQueueCore，BBQBufferQueueProducer（生产者）和BufferQueueConsumer（消费者），然后在把SurfaceControl关联进BLASTBufferQueue   

我们继续看注释37 执行BLASTBufferQueue的createSurface来创建Surface的过程

```java
//BLASTBufferQueue.java
public long mNativeObject; // BLASTBufferQueue*
private static native Surface nativeGetSurface(long ptr, boolean includeSurfaceControlHandle);
public Surface createSurface() {
    return nativeGetSurface(mNativeObject, false /* includeSurfaceControlHandle */);
}
```

通过上一小节创建的BLASTBufferQueue去创建JNI层的Surface，注意传的includeSurfaceControlHandle 为false

> frameworks/base/core/jni/android_graphics_BLASTBufferQueue.cpp

```cpp
static jobject nativeGetSurface(JNIEnv* env, jclass clazz, jlong ptr,
                                jboolean includeSurfaceControlHandle) {
    sp<BLASTBufferQueue> queue = reinterpret_cast<BLASTBufferQueue*>(ptr);
    //先通过queue->getSurface获取Surface，再通过android_view_Surface_createFromSurface调用java层的构造函数来创建java层的Surface
    return android_view_Surface_createFromSurface(env,queue->getSurface(includeSurfaceControlHandle));
}

sp<Surface> BLASTBufferQueue::getSurface(bool includeSurfaceControlHandle) {
    std::unique_lock _lock{mMutex};
    sp<IBinder> scHandle = nullptr;
    //includeSurfaceControlHandle传的是false，所以scHandle为空
    if (includeSurfaceControlHandle && mSurfaceControl) {
        scHandle = mSurfaceControl->getHandle();
    }
    //42 JNI层创建BBQSurface,传的scHandle为空
    return new BBQSurface(mProducer, true, scHandle, this);
}

//android_view_Surface.cpp
jobject android_view_Surface_createFromSurface(JNIEnv* env, const sp<Surface>& surface) {
    //43通过调用java层的构造函数创建java层的Surface，这里传了BBQSurface的地址，所以调用的是Surface.java的带long类型的构造函数
    jobject surfaceObj = env->NewObject(gSurfaceClassInfo.clazz,
            gSurfaceClassInfo.ctor, (jlong)surface.get());
    if (surfaceObj == NULL) {
        return NULL;
    }
    surface->incStrong(&sRefBaseOwner);
    return surfaceObj;
}
//env->NewObject里传的gSurfaceClassInfo.ctor即java层Surface.java的构造函数
jclass clazz = FindClassOrDie(env, "android/view/Surface");
gSurfaceClassInfo.ctor = GetMethodIDOrDie(env, gSurfaceClassInfo.clazz, "<init>", "(J)V");

//Surface.java
private Surface(long nativeObject) {
    synchronized (mLock) {
        setNativeObjectLocked(nativeObject);
    }
}
private void setNativeObjectLocked(long ptr) {
    //记录JNI层传过来的BBQSurface的地址
    if (mNativeObject != ptr) {
        //...
        mNativeObject = ptr;
    }
}
```

可以看到注释42返回给JNI层创建的是BBQSurface，构建BBQSurface时传进来了mProducer和BLASTBufferQueue，然后在注释43处通过调用java端Surface.java带long类型的构造函数，记录了JNI层创建的BBQSurface的地址。到这里我们需理解下Surface到底是什么。

```cpp
//BLASTBufferQueue.cpp
class BBQSurface : public Surface {
    sp<BLASTBufferQueue> mBbq;
    BBQSurface(const sp<IGraphicBufferProducer>& igbp, bool controlledByApp,
               const sp<IBinder>& scHandle, const sp<BLASTBufferQueue>& bbq)
          : Surface(igbp, controlledByApp, scHandle), mBbq(bbq) {}
}

//Surface.h
class Surface : public ANativeObjectBase<ANativeWindow, Surface, RefBase>{}

//ANativeObjectBase.h
template <typename NATIVE_TYPE, typename TYPE, typename REF,
        typename NATIVE_BASE = android_native_base_t>
//ANativeObjectBase继承于模版定义的NATIVE_TYPE，即传进来的ANativeWindow
class ANativeObjectBase : public NATIVE_TYPE, public REF{}
```

可以看到Surface本质就是个ANativeWindow。根据其构造函数传的IGraphicBufferProducer和BufferQueue可以猜测其主要是通过图形缓冲区生产者（IGraphicBufferProducer）先从BufferQueue里获取容器，再把graphicBuffer放到容器后返回给BufferQueue，以供消费者消费，这里的生产者是客户端，当前的消费者是BLASTBufferQueue里包装了IGraphicBufferConsumer的BLASTBufferItemConsumer，其最终的消费者还是SF。到这里，客户端已经创建好了BBQSurface，我们继续看绘制流程。

##### 绘制流程

在ViewRootImpl执行完relayoutWindow后，此时本地已经获取到了BBQSurface，但是此时还没从BufferQueue里传Buffer数据，下面就分析下客户端作为生产者的流程。

```java
private void performTraversals() {
	//...
    relayoutResult = relayoutWindow(params, viewVisibility, insetsPending);
    //...省略具体View绘制流程
    performMeasure();
    performLayout();
    performDraw();
}
private boolean performDraw() {
    //...
    boolean canUseAsync = draw(fullRedrawNeeded, usingAsyncReport && mSyncBuffer);
    //...
}
private boolean draw(boolean fullRedrawNeeded, boolean forceDraw) {
    //...
    if (isHardwareEnabled()) {
		//...通过硬件加速的方式
         mAttachInfo.mThreadedRenderer.draw(mView, mAttachInfo, this);
    }else{
        //...
        //不使用硬件加速，即使用cpu绘制
        if (!drawSoftware(surface, mAttachInfo, xOffset, yOffset,
                          scalingRequired, dirty, surfaceInsets)) {
            return false;
        }
    }
   
}
```

在客户端绘制流程的draw的最后，调用了drawSoftware，目前只分析用cpu绘制，暂不考虑硬件加速

```java
private boolean drawSoftware(Surface surface, AttachInfo attachInfo, int xoff, int yoff,
                             boolean scalingRequired, Rect dirty, Rect surfaceInsets) {
	//...
    //43 获取canvas,dirty是绘制区域,一般而言需要更新的childview或者整块contentview显示区域
    canvas = mSurface.lockCanvas(dirty);
    //...
    //最终调用到view.onDraw方法
    mView.draw(canvas);
    //...
    //44 释放并post canvas
    surface.unlockCanvasAndPost(canvas);
    //...
}
```

我们知道view绘制的时候需要canvas，而canvas是通过注释43处surface.lockCanvas来获取。绘制过程可以简单描述为以下3步：  

+ 通过Surface获取一个有效的Canvas
+ view通过canvas进行绘制
+ Surface释放并发送此canvas

我们再看下这3个步骤，先看

```java
//Surface.java
private final Canvas mCanvas = new CompatibleCanvas();
long mNativeObject;//Surface的内存地址
private long mLockedObject;//JNI层新锁的Surface的内存地址
public Canvas lockCanvas(Rect inOutDirty)
    throws Surface.OutOfResourcesException, IllegalArgumentException {
    synchronized (mLock) {
        //45 native层锁Canvas，即锁一块内存区域
        mLockedObject = nativeLockCanvas(mNativeObject, mCanvas, inOutDirty);
        return mCanvas;
    }
}

//Canvas.java
public class Canvas extends BaseCanvas {
    //此成员变量在父类BaseCanvas里定义，保存JNI层创建的SKCanvas的地址
    protected long mNativeCanvasWrapper;
    public Canvas() {
        if (!isHardwareAccelerated()) {
            // 0 means no native bitmap
            //46 JNI层创建native层的canvas并返回其内存地址保存到mNativeCanvasWrapper
            mNativeCanvasWrapper = nInitRaster(0);
            mFinalizer = NoImagePreloadHolder.sRegistry.registerNativeAllocation(
                this, mNativeCanvasWrapper);
        } else {
            mFinalizer = null;
        }
    }
}
```

在注释45处native层锁Canvas时传了Surface类的成员变量即直接new的CompatibleCanvas，CompatibleCanvas继承于Canvas，Canvas的构造函数中会通过注释46在jni层创建native层的Canvas并返回其地址，保存到canvas的成员变量mNativeCanvasWrapper里，我们先看注释46的nInitRaster

> frameworks/base/libs/hwui/jni/android_graphics_Canvas.cpp

```cpp
static const JNINativeMethod gMethods[] = {
    //...nInitRaster方法映射为initRaster
    {"nInitRaster", "(J)J", (void*) CanvasJNI::initRaster},
    //...
}
static jlong initRaster(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap bitmap;
    //传过来的bitmapHandle为0，所以此时创建的bitmap为空
    if (bitmapHandle != 0) {
        bitmap::toBitmap(bitmapHandle).getSkBitmap(&bitmap);
    }
    return reinterpret_cast<jlong>(Canvas::create_canvas(bitmap));
}

// frameworks/base/libs/hwui/SkiaCanvas.cpp
Canvas* Canvas::create_canvas(const SkBitmap& bitmap) {
    return new SkiaCanvas(bitmap);
}
```

在JNI层创建了SkiaCanvas。此时传入的SkBitmap是空的，没有任何有效信息。再看注释45处nativeLockCanvas

> frameworks/base/core/jni/android_view_Surface.cpp

```cpp
static jlong nativeLockCanvas(JNIEnv* env, jclass clazz,
                              jlong nativeObject, jobject canvasObj, jobject dirtyRectObj) {
    // 获取对应native层的Surface对象
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));
    //...获取绘制区域的左上右下
    Rect dirtyRect(Rect::EMPTY_RECT);
    Rect* dirtyRectPtr = NULL;
    if (dirtyRectObj) {
        dirtyRect.left   = env->GetIntField(dirtyRectObj, gRectClassInfo.left);
        dirtyRect.top    = env->GetIntField(dirtyRectObj, gRectClassInfo.top);
        dirtyRect.right  = env->GetIntField(dirtyRectObj, gRectClassInfo.right);
        dirtyRect.bottom = env->GetIntField(dirtyRectObj, gRectClassInfo.bottom);
        dirtyRectPtr = &dirtyRect;
    }

    ANativeWindow_Buffer buffer;
    // 47 surface调用lock函数锁定一块buffer
    status_t err = surface->lock(&buffer, dirtyRectPtr);
    //...
    //48 通过Java层的canvas对象初始化一个native层的canvas对象，即SKCanvas
    graphics::Canvas canvas(env, canvasObj);
    //49 设置buffer，然后canvas把buffer转换为SKBitmap
    canvas.setBuffer(&buffer, static_cast<int32_t>(surface->getBuffersDataSpace()));
    //...
    // 创建一个新的Surface引用。返回到Java层
    sp<Surface> lockedSurface(surface);
    lockedSurface->incStrong(&sRefBaseOwner);
    return (jlong) lockedSurface.get();
}

//frameworks/native/libs/nativewindow/include/android/native_window.h
typedef struct ANativeWindow_Buffer {
    int32_t width;
    int32_t height;
    int32_t stride;
    int32_t format;
    void* bits;
    uint32_t reserved[6];
} ANativeWindow_Buffer;
```

nativeLockCanvas主要分了三步：

+ 注释47 申请并锁定一块内存
+ 注释48 获取native层的canvas对象，即SKCanvas
+ 注释49 把buffer设置进canvas，其中SKCanvas会把buffer转换为SKBitmap，SKBitmap可以被canvas绘制

其实就是先申请一块buffer，再通过java层的canvas获取个native层的SKCanvas，再把buffer设置进SKCanvas，SKCanvas里会把buffer转换成SKBitmap，SKBitmap就可以被canvas直接绘制。

先看注释47 surface->lock

```cpp
status_t Surface::lock(ANativeWindow_Buffer* outBuffer, ARect* inOutDirtyBounds){
    ANativeWindowBuffer* out;
    int fenceFd = -1;
    //50 dequeueBuffer 从输入缓冲队列中获取一块内存
    status_t err = dequeueBuffer(&out, &fenceFd);
    if (err == NO_ERROR) {
        //当前申请的图形内存区域作为backBuffer
        sp<GraphicBuffer> backBuffer(GraphicBuffer::getSelf(out));
        const Rect bounds(backBuffer->width, backBuffer->height);

        Region newDirtyRegion;
        if (inOutDirtyBounds) {
            newDirtyRegion.set(static_cast<Rect const&>(*inOutDirtyBounds));
            newDirtyRegion.andSelf(bounds);
        } else {
            newDirtyRegion.set(bounds);
        }

        // figure out if we can copy the frontbuffer back
        // mPostedBuffer作为正在显示的一块图像区域
        const sp<GraphicBuffer>& frontBuffer(mPostedBuffer);
        //其实这里就涉及到Surface的双缓冲机制 mPostedBuffer/mLockedBuffer两块buffer
        //这里要判断是否可以复制（比较一下backBuffer与frontBuffer）
        const bool canCopyBack = (frontBuffer != nullptr &&
                                  backBuffer->width  == frontBuffer->width &&
                                  backBuffer->height == frontBuffer->height &&
                                  backBuffer->format == frontBuffer->format);

        if (canCopyBack) { // 可以赋值时
            //计算一下不需要重绘的区域
            const Region copyback(mDirtyRegion.subtract(newDirtyRegion));
            if (!copyback.isEmpty()) {
                // 复制不需要重绘的区域
                copyBlt(backBuffer, frontBuffer, copyback, &fenceFd);
            }
        } else {
            //设置脏区域的边界
            newDirtyRegion.set(bounds);
            //不能复制则清理一下原先的数据
            mDirtyRegion.clear();
            Mutex::Autolock lock(mMutex);
            for (size_t i=0 ; i<NUM_BUFFER_SLOTS ; i++) {
                mSlots[i].dirtyRegion.clear();
            }
        }
        //...
        // 锁定后缓冲区
        status_t res = backBuffer->lockAsync(
            GRALLOC_USAGE_SW_READ_OFTEN | GRALLOC_USAGE_SW_WRITE_OFTEN,
            newDirtyRegion.bounds(), &vaddr, fenceFd);


        if (res != 0) {
            err = INVALID_OPERATION;
        } else {
            //backBuffer保存为mLockedBuffer
            mLockedBuffer = backBuffer;
            //backBuffer里的参数赋值给传进来的buffer
            outBuffer->width  = backBuffer->width;
            outBuffer->height = backBuffer->height;
            outBuffer->stride = backBuffer->stride;
            outBuffer->format = backBuffer->format;
            outBuffer->bits   = vaddr;
        }
    }
    return err;
}
int Surface::dequeueBuffer(android_native_buffer_t** buffer, int* fenceFd) {
    //...mGraphicBufferProducer是在Surface构造函数中赋值的
    status_t result = mGraphicBufferProducer->dequeueBuffer(&buf, &fence, dqInput.width,
                                                            dqInput.height, dqInput.format,
                                                            dqInput.usage, &mBufferAge,
                                                            dqInput.getTimestamps ?
                                                            &frameTimestamps : nullptr);
    //...
}
```

注释50处先dequeueBuffer从输入缓冲队列中获取一块内存ANativeWindowBuffer，然后把其作为backBuffer（后缓冲区），这里牵涉到Surface的双缓冲机制，即前后缓冲区，正在显示的作为前缓冲区，在后台准备新的数据的为后缓冲区。这里会先拿前缓冲区与后缓冲区的参数（宽高，format）判断是否可以直接复制，然后锁定后缓冲区。

再看注释48获取native层的Canvas： graphics::Canvas canvas(env, canvasObj);

```c++
//frameworks/base/libs/hwui/apex/include/android/graphics/canvas.h
namespace graphics {
    class Canvas {
        public:
        Canvas(JNIEnv* env, jobject canvasObj) :
        // 调用ACanvas_getNativeHandleFromJava
        mCanvas(ACanvas_getNativeHandleFromJava(env, canvasObj)),
        mOwnedPtr(false) {}
    }
}

//frameworks/base/libs/hwui/apex/android_canvas.cpp
ACanvas* ACanvas_getNativeHandleFromJava(JNIEnv* env, jobject canvasObj) {
    //继续调用getNativeCanvas
    return TypeCast::toACanvas(GraphicsJNI::getNativeCanvas(env, canvasObj));
}

//frameworks/base/libs/hwui/jni/Graphics.cpp
android::Canvas* GraphicsJNI::getNativeCanvas(JNIEnv* env, jobject canvas) {
    //...
    //51 获取Java层的Canvas mNativeCanvasWrapper的句柄
    jlong canvasHandle = env->GetLongField(canvas, gCanvas_nativeInstanceID);
    if (!canvasHandle) {
        return NULL;
    }
    // 把数值转换为对象
    return reinterpret_cast<android::Canvas*>(canvasHandle);
}
int register_android_graphics_Graphics(JNIEnv* env){
    //gCanvas_nativeInstanceID即java层Canvas的成员变量mNativeCanvasWrapper
    gCanvas_nativeInstanceID = GetFieldIDOrDie(env, gCanvas_class, "mNativeCanvasWrapper", "J");
}
```

注释51处通过java层传过来的canvas对象获取mNativeCanvasWrapper的句柄（即SkiaCanvas）。

再看注释49处canvas.setBuffer

```cpp
//frameworks/base/libs/hwui/apex/include/android/graphics/canvas.h
bool setBuffer(const ANativeWindow_Buffer* buffer,
               int32_t /*android_dataspace_t*/ dataspace) {
    return ACanvas_setBuffer(mCanvas, buffer, dataspace);
}

//frameworks/base/libs/hwui/apex/android_canvas.cpp
bool ACanvas_setBuffer(ACanvas* canvas, const ANativeWindow_Buffer* buffer,
                       int32_t /*android_dataspace_t*/ dataspace) {
    SkBitmap bitmap;
    //52 把buffer转换为SKBitmap
    bool isValidBuffer = (buffer == nullptr) ? false : convert(buffer, dataspace, &bitmap);
    //然后把SKBitmap设置进SkiaCanvas中（SKBitmap可以被canvas绘制）
    TypeCast::toCanvas(canvas)->setBitmap(bitmap);
    return isValidBuffer;
}

//frameworks/base/libs/hwui/SkiaCanvas.cpp 
void SkiaCanvas::setBitmap(const SkBitmap& bitmap) {
    //根据传入的bitmap创建一个SkCanvas，并更新
    mCanvasOwned.reset(new SkCanvas(bitmap));
    mCanvas = mCanvasOwned.get();

    // clean up the old save stack
    mSaveStack.reset(nullptr);
}
```

注释52把获取的buffer转换为一个SkBitmap,此时bitmap中有有效信息，然后把此bitmap设置进SkiaCanvas并替换原来的Bitmap（SKBitmap可以被canvas绘制），接下来canvas就在这个bitmap上进行绘制。

> 可以把Canvas理解成画布，把画布想象成一块内存空间，也就是一个Bitmap，Canvas的API提供了一整套在这个Bitmap上绘图的方法。

注释43处lockCanvas获取有效的Canvas结束后，下面mView.draw(canvas)过程不再细看，都会调用view的onDraw传入此canvas完成绘制，其实都是通过JNI层的SkiaCanvas绘制到SkBitmap上，这些都是通过skia图像绘制引擎具体实现，这里不再讨论绘制具体内容的流程。我们继续看绘制完之后注释44处释放并post此canvas

```java
public void unlockCanvasAndPost(Canvas canvas) {
    synchronized (mLock) {
        //暂不考虑硬件加速
        if (mHwuiContext != null) {
            mHwuiContext.unlockAndPost(canvas);
        } else {
            unlockSwCanvasAndPost(canvas);
        }
    }
}

private void unlockSwCanvasAndPost(Canvas canvas) {
    //...
    try {
        nativeUnlockCanvasAndPost(mLockedObject, canvas);
    } finally {
        nativeRelease(mLockedObject);
        mLockedObject = 0;
    }
}
private static native void nativeUnlockCanvasAndPost(long nativeObject, Canvas canvas);
```

没什么好解释的，调用JNI的nativeUnlockCanvasAndPost

```cpp
//frameworks/base/core/jni/android_view_Surface.cpp
static void nativeUnlockCanvasAndPost(JNIEnv* env, jclass clazz,
        jlong nativeObject, jobject canvasObj) {
    sp<Surface> surface(reinterpret_cast<Surface *>(nativeObject));
    if (!isSurfaceValid(surface)) {
        return;
    }

    // detach the canvas from the surface
    //获取native层的SkiaCanvas
    graphics::Canvas canvas(env, canvasObj);
    //skiaCanvas设置buffer为空
    canvas.setBuffer(nullptr, ADATASPACE_UNKNOWN);

    // unlock surface
    status_t err = surface->unlockAndPost();
    if (err < 0) {
        jniThrowException(env, IllegalArgumentException, NULL);
    }
}

// frameworks/native/libs/gui/Surface.cpp
status_t Surface::unlockAndPost()
{
    //surface->lock()时已经保存了一个mLockedBuffer，此时应该不为null
    if (mLockedBuffer == nullptr) {
        return INVALID_OPERATION;
    }

    int fd = -1;
    //解除锁定
    status_t err = mLockedBuffer->unlockAsync(&fd);
    //53 把这块绘制完毕的buffer提交到缓冲队列中，等待显示
    err = queueBuffer(mLockedBuffer.get(), fd);
    //...
    //接着这块buffer就变成了前台已发布的buffer了，这个就是双缓冲机制了
    mPostedBuffer = mLockedBuffer;
    //置空
    mLockedBuffer = nullptr;
    return err;
}
```

注释53处Surface在unlockAndPost时调用queueBuffer把绘制完毕的buffer提交到缓冲队列，等待消费者消费后显示。  

做个小结，ViewRootImpl通过Surface的dequeueBuffer从缓冲队列获取一块可用于绘制的buffer，然后把buffer转换成SKBitmap后绑定到canvas中，View使用该canvas进行绘制，实际上就是绘制到SKBitmap中，即保存在buffer中，绘制完毕后，通过surface清除canvas与buffer的绑定关系，并通过queueBuffer把绘制后的buffer发送到缓冲队列。此后再由消费者，大部分情况下是SurfaceFlinger进行消费，也有例外，比如也可以通过视频编码器进行消费。

##### 提交事务到SF

上面注释53处调用Surface的queueBuffer把绘制完毕的buffer提交到缓冲队列中，我们继续看下是怎么通知到SF的

```c++
//Surface.cpp
int Surface::queueBuffer(android_native_buffer_t* buffer, int fenceFd) {
    //找到buffer在mSlots中的下标
    int i = getSlotFromBufferLocked(buffer);
    //...
    //调用绑定的GraphicBufferProducer.queueBuffer，即BufferQueueProducer
    status_t err = mGraphicBufferProducer->queueBuffer(i, input, &output);
}
//BufferQueueProducer.cpp
status_t BufferQueueProducer::queueBuffer(int slot,const QueueBufferInput &input, QueueBufferOutput *output) {
    //...
    sp<IConsumerListener> frameAvailableListener;
    //...
    //mCore->mConsumerListener即BLASTBufferQueue
    frameAvailableListener = mCore->mConsumerListener;
    //...
    if (frameAvailableListener != nullptr) {
        frameAvailableListener->onFrameAvailable(item);
    }
}
//BLASTBufferQueue.cpp
void BLASTBufferQueue::onFrameAvailable(const BufferItem& item) {
    //...
    bool waitForTransactionCallback = !mSyncedFrameNumbers.empty();
    //...
    if (!waitForTransactionCallback) {
        acquireNextBufferLocked(std::nullopt);
    }
}

void BLASTBufferQueue::acquireNextBufferLocked(const std::optional<SurfaceComposerClient::Transaction*> transaction) {
    SurfaceComposerClient::Transaction localTransaction;
    bool applyTransaction = true;
    SurfaceComposerClient::Transaction* t = &localTransaction;
    //...
    //54 调用消费者的acquireBuffer从bufferqueue里取的值存到传进去的空的BufferItem里
    BufferItem bufferItem;
    status_t status =mBufferItemConsumer->acquireBuffer(&bufferItem, 0 , false);
    auto buffer = bufferItem.mGraphicBuffer;
    
    //55 把buffer设置到事务中，最终提交到SF
    t->setBuffer(mSurfaceControl, buffer, fence, bufferItem.mFrameNumber, releaseBufferCallback);
    t->setDataspace(mSurfaceControl, static_cast<ui::Dataspace>(bufferItem.mDataSpace));
    t->setHdrMetadata(mSurfaceControl, bufferItem.mHdrMetadata);
    t->setSurfaceDamageRegion(mSurfaceControl, bufferItem.mSurfaceDamage);
    //56 设置了事务完成后的回调是自己，即BLASTBufferQueue
    t->addTransactionCompletedCallback(transactionCallbackThunk, static_cast<void*>(this));
    
    mergePendingTransactions(t, bufferItem.mFrameNumber);
    if (applyTransaction) {
        if (sIsGame) {
            t->setApplyToken(mApplyToken).apply(false, false);
        } else {
            // All transactions on our apply token are one-way. See comment on mAppliedLastTransaction
            //57 所有的事务在applyToken都是单路的，即事务这里不会有回调
            t->setApplyToken(mApplyToken).apply(false, true);
        }
        mAppliedLastTransaction = true;
        mLastAppliedFrameNumber = bufferItem.mFrameNumber;
    } 
}
```

可以看到，生产者提交到缓冲队列后，实际上会回调BBQ的onFrameAvailable，在注释54处通过消费者的acquireBuffer从bufferqueue里取值并赋值到传进去空的BufferItem里，然后获取其mGraphicBuffer即buffer数据，并在注释55处把buffer数据设置到事务里，注释56处设置了事务完成后的回调为自己即BBQ，最后在注释56处应用此事务，即会调用到SF处理。  

> 需要注意的是，Android12后google将BufferQueue组件从SF端移动到了客户端，带来的变化是整个生产者消费者模型都在客户端完成，即图形缓冲区的出队，入队，获取，释放等操作都在客户端，最终通过事务Transaction向SF提交Buffer等信息，Android12之前的消费者监听器是在SF端的ContentsChangedListener

![流程图](img/surfaceFlinger-BBQ.webp)

加上多个事务的合并提交到SF：
![流程图](img/surfaceFlinger-事务.webp)


我们继续看事务怎么传递到SF进程。

注释57调用到了SurfaceComposerClient::Transaction::apply

```c++
//SurfaceComposerClient.cpp
status_t SurfaceComposerClient::Transaction::apply(bool synchronous, bool oneWay) {
    //获取SF代理对象
    sp<ISurfaceComposer> sf(ComposerService::getComposerService());
    
    //新建个composerStates并把本地之前缓存的都添加进去
    Vector<ComposerState> composerStates;
    for (auto const& kv : mComposerStates){
        composerStates.add(kv.second);
    }
	//...
    //本地的binder对象传到SF，以便SF处理完后通知客户端事务已经完成
    sp<IBinder> applyToken = mApplyToken
        ? mApplyToken
        : IInterface::asBinder(TransactionCompletedListener::getIInstance());

    //58 调用SF.setTransactionState
    sf->setTransactionState(mFrameTimelineInfo, composerStates, displayStates, flags, applyToken,
                            mInputWindowCommands, mDesiredPresentTime, mIsAutoTimestamp,
                            {} /*uncacheBuffer - only set in doUncacheBufferTransaction*/,
                            hasListenerCallbacks, listenerCallbacks, mId);
}
```

拿SF的代理对象，并把本地缓存的mComposerStates，事务完成后的监听的binder等数据通过注释58处调用setTransactionState来通知SF。我们先看下ComposerState数据即注释55处t.setBuffer的过程

```c++
SurfaceComposerClient::Transaction& SurfaceComposerClient::Transaction::setBuffer(
    const sp<SurfaceControl>& sc, const sp<GraphicBuffer>& buffer,
    const std::optional<sp<Fence>>& fence, const std::optional<uint64_t>& optFrameNumber,
    ReleaseBufferCallback callback) {
	//通过SurfaceControl获取layer_state_t
    layer_state_t* s = getLayerState(sc);
    
    std::shared_ptr<BufferData> bufferData = std::make_shared<BufferData>();
    bufferData->buffer = buffer;

    s->what |= layer_state_t::eBufferChanged;
    //59 buffer数据最终保存在layer_state_t.bufferData里
    s->bufferData = std::move(bufferData);
    //...
    
}
layer_state_t* SurfaceComposerClient::Transaction::getLayerState(const sp<SurfaceControl>& sc) {
    auto handle = sc->getLayerStateHandle();
    return &(mComposerStates[handle].state);
}
//LayerState.h
struct ComposerState {
    layer_state_t state;
    status_t write(Parcel& output) const;
    status_t read(const Parcel& input);
};
```

注释59其实buffer数据是保存在了ComposerState里面的layer_state_t里面的bufferData里。buffer数据已经有了，我们继续看注释58处调用SF.setTransactionState

```cpp
//SurfaceFlinger.cpp
status_t SurfaceFlinger::setTransactionState(
    const FrameTimelineInfo& frameTimelineInfo, const Vector<ComposerState>& states,
    const Vector<DisplayState>& displays, uint32_t flags, const sp<IBinder>& applyToken,
    const InputWindowCommands& inputWindowCommands, int64_t desiredPresentTime,
    bool isAutoTimestamp, const client_cache_t& uncacheBuffer, bool hasListenerCallbacks,
    const std::vector<ListenerCallbacks>& listenerCallbacks, uint64_t transactionId) {
	//...
    IPCThreadState* ipc = IPCThreadState::self();
    const int originPid = ipc->getCallingPid();
    const int originUid = ipc->getCallingUid();
    //根据参数构建个TransactionState
    TransactionState state{frameTimelineInfo,  states,
                           displays,           flags,
                           applyToken,         inputWindowCommands,
                           desiredPresentTime, isAutoTimestamp,
                           uncacheBuffer,      postTime,
                           permissions,        hasListenerCallbacks,
                           listenerCallbacks,  originPid,
                           originUid,          transactionId};
	//把TransactionState入队列等待执行
    queueTransaction(state);
    if (state.transactionCommittedSignal) {
        waitForSynchronousTransaction(*state.transactionCommittedSignal);
    }

    updateSmomoLayerInfo(state, desiredPresentTime, isAutoTimestamp);
    return NO_ERROR;
}
void SurfaceFlinger::queueTransaction(TransactionState& state) {
    //...
    //TransactionState加到队列尾部
    mTransactionQueue.emplace_back(state);
    //...
    //60 设置事务标记为eTransactionFlushNeeded即0x10
    setTransactionFlags(eTransactionFlushNeeded, schedule, state.applyToken, frameHint);
}
```

设置事务状态时构建个TransactionState并加入队列尾部，在注释60处并设置事务标记为eTransactionFlushNeeded，我们继续看setTransactionFlags流程，此流程较复杂


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
    //请求SurfaceFlinger的vsync
    mScheduler->scheduleFrame();
}

//Scheduler.h
class Scheduler : impl::MessageQueue {
    using Impl = impl::MessageQueue;
    public:
    using Impl::scheduleFrame;
}

//MessageQueue.cpp
void MessageQueue::scheduleFrame() {
    //请求SurfaceFlinger的vsync
    mVsync.scheduledFrameTime =
        mVsync.registration->schedule({.workDuration = mVsync.workDuration.get().count(),
                                       .readyDuration = 0,
                                       .earliestVsync = mVsync.lastCallbackTime.count()});
}

//VSyncCallbackRegistration.cpp
ScheduleResult VSyncCallbackRegistration::schedule(VSyncDispatch::ScheduleTiming scheduleTiming) {
    // mDispatch 是 VSyncDispatchTimerQueue
    return mDispatch.get().schedule(mToken, scheduleTiming);
}

//
ScheduleResult VSyncDispatchTimerQueue::schedule(CallbackToken token,ScheduleTiming scheduleTiming) {
    //...
    //VSyncCallbackRegistration 构造的时候，调用registerCallback生成了一个 token ，这个token存储到了 map 对象 mCallbacks 现在拿出来
    auto it = mCallbacks.find(token);
     // map 迭代器 second 中存储 VSyncDispatchTimerQueueEntry
    auto& callback = it->second;
    // VSyncDispatchTimerQueueEntry 中存储真正的回调函数 MessageQueue::vsyncCallback
    result = callback->schedule(scheduleTiming, mTracker, now);
     //...
}

//在SurfaceFlinger初始化Scheduler时初始化了Vsync
void SurfaceFlinger::initScheduler(const sp<DisplayDevice>& display) {
    //...
    mScheduler->initVsync(mScheduler->getVsyncDispatch(), *mFrameTimeline->getTokenManager(),
                          configs.late.sfWorkDuration);
    //...
}

//MessageQueue::initVsync
void MessageQueue::initVsync(scheduler::VSyncDispatch& dispatch,
                             frametimeline::TokenManager& tokenManager,
                             std::chrono::nanoseconds workDuration) {
    setDuration(workDuration);
    mVsync.tokenManager = &tokenManager;
    //初始化VSyncCallbackRegistration时设定callback为MessageQueue::vsyncCallback
    mVsync.registration = std::make_unique<
        scheduler::VSyncCallbackRegistration>(dispatch,
                                              std::bind(&MessageQueue::vsyncCallback, this,
                                                        std::placeholders::_1,
                                                        std::placeholders::_2,
                                                        std::placeholders::_3),"sf");
}
```

可以看到，VSyncDispatchTimerQueueEntry 中存储真正的回调函数是 MessageQueue::vsyncCallback，继续看MessageQueue::vsyncCallback

```cpp
//MessageQueue.cpp
void MessageQueue::vsyncCallback(nsecs_t vsyncTime, nsecs_t targetWakeupTime, nsecs_t readyTime) {
    // Trace VSYNC-sf
    mVsync.value = (mVsync.value + 1) % 2;
    {
        std::lock_guard lock(mVsync.mutex);
        mVsync.lastCallbackTime = std::chrono::nanoseconds(vsyncTime);
        mVsync.scheduledFrameTime.reset();
    }

    const auto vsyncId = mVsync.tokenManager->generateTokenForPredictions(
            {targetWakeupTime, readyTime, vsyncTime});

    mHandler->dispatchFrame(vsyncId, vsyncTime);
}
void MessageQueue::Handler::dispatchFrame(int64_t vsyncId, nsecs_t expectedVsyncTime) {
    if (!mFramePending.exchange(true)) {
        mVsyncId = vsyncId;
        mExpectedVsyncTime = expectedVsyncTime;
        //往mQueue.mLooper里发了消息
        mQueue.mLooper->sendMessage(this, Message());
    }
}

void MessageQueue::Handler::handleMessage(const Message&) {
    mFramePending.store(false);

    const nsecs_t frameTime = systemTime();
    //mQueue  类型android::impl::MessageQueue
    //android::impl::MessageQueue.mCompositor 类型 ICompositor
    //SurfaceFlinger 继承 ICompositor
    //mQueue.mCompositor 其实就是 SurfaceFlinger 
    auto& compositor = mQueue.mCompositor;
    
    //61 调用到SF的commit，返回false的话，直接返回，否则执行后面的合成流程composite
    if (!compositor.commit(frameTime, mVsyncId, mExpectedVsyncTime)) {
        return;
    }
	//SF合成流程
    compositor.composite(frameTime, mVsyncId);
    compositor.sample();
}
```

注释61最终会调用到SF的commit，返回false的话，直接返回，否则执行SF的合成流程composite


##### SF的commit流程

下面细看下SF的提交合成流程，先看commit提交流程。

```cpp
bool SurfaceFlinger::commit(nsecs_t frameTime, int64_t vsyncId, nsecs_t expectedVsyncTime) FTL_FAKE_GUARD(kMainThreadContext) {
    //...
    bool needsTraversal = false;
    //62 返回SurfaceFlinger.mTransactionFlags 是否携带 eTransactionFlushNeeded 标记。同时清除这个标记
    //上面注释60处传过来的标记就是eTransactionFlushNeeded
    if (clearTransactionFlags(eTransactionFlushNeeded)) {
        //63 处理以前创建的layer，核心就是把新创建的layer加入到Z轴排序集合体系 mCurrentState.layersSortedByZ,
        //Android12以前layersSortedByZ不是在这里添加的,12之后都通过事务创建
        needsTraversal |= commitCreatedLayers();
        //64 flush事务队列，里面会apply事务，处理客户端传过来的数据
        needsTraversal |= flushTransactionQueues(vsyncId);
    }
    
    //65 是否需要执行事务提交。提交的核心就是把 mCurrentState 赋值给 mDrawingState
	// mCurrentState 保存APP传来的数据，mDrawingState 用于合成
    const bool shouldCommit =
        (getTransactionFlags() & ~eTransactionFlushNeeded) || needsTraversal;
    if (shouldCommit) {
        commitTransactions();
    }

    //如果还有待处理的事务，请求下一个SF vsync,把标记重新设置成eTransactionFlushNeeded
    if (transactionFlushNeeded()) {
        setTransactionFlags(eTransactionFlushNeeded);
    }

    //66 latchBuffers 相当于Android12及以前的handlePageFlip，Android13之后改为latchBuffers
    //如果有新的buffer的layer大于0，并且拿到了buffer会返回true
    mustComposite |= shouldCommit;
    mustComposite |= latchBuffers();
    //67 计算边界，脏区域
    updateLayerGeometry();
    //...
    return mustComposite && CC_LIKELY(mBootStage != BootStage::BOOTLOADER);
}

```

commit这块稍微有点复杂

+ 注释62处会判断事务的tag是否是eTransactionFlushNeeded，是的话就走注释63和64，此标记是在注释60处传过来的。另外注意到最开始创建layer时（注释33处）设置的标记为eTransactionNeeded，此时并不会触发注释63把layer添加进mCurrentState.layersSortedByZ，更不会走后面合成流程。
+ 注释63 处理以前创建的layer，核心就是把新创建的layer加入到Z轴排序集合体系 mCurrentState.layersSortedByZ
+ 注释64会flush事务队列，里面会apply事务，处理客户端传过来的数据
+ 注释65根据63和64的返回值来判断是否需要提交事务，提交的核心就是把 mCurrentState 赋值给 mDrawingState，即把客户端传过来的mCurrentState数据，赋值给mDrawingState 用于后面合成流程
+ 注释66的latchBuffers 相当于Android12及以前的handlePageFlip，主要目的是检查每个layer的更新
+ 注释67计算边界，脏区

以上步骤我们详细看下，先看注释63处commitCreatedLayers

```c++
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
    //遍历新创建的layer，通过handleLayerCreatedLocked去添加或者删除此layer
    for (const auto& createdLayer : createdLayers) {
        handleLayerCreatedLocked(createdLayer);
    }
    //清空本地缓存
    createdLayers.clear();
    //这里先标记mLayersAdded为true，在之后commitTransactionsLocked 函数中会设置回 false
    mLayersAdded = true;
    //有新创建的layer，返回false，需执行composite合成操作
    return true;
}
void SurfaceFlinger::handleLayerCreatedLocked(const LayerCreatedState& state) {
    //...
    //68 如果没有父layer，在mCurrentState.layersSortedByZ里添加layer
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
```

注释68处如果没有父layer，在mCurrentState.layersSortedByZ里添加layer，layersSortedByZ即以Z轴排序的集合。继续看注释64处flushTransactionQueues

```cpp
//LayerState.h
struct ComposerState {
    //layer_state_t里包括了layer的所有信息。
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
    //客户端传过来的buffer
    std::shared_ptr<BufferData> bufferData = nullptr;
    //...
}
//SurfaceFlinger.cpp
bool SurfaceFlinger::flushTransactionQueues(int64_t vsyncId) {
    //...
    while (!mTransactionQueue.empty()) {
        auto& transaction = mTransactionQueue.front();
        //看看当前的Transaction是否已经ready，没有ready则不传递Transaction
        const auto ready = [&]() REQUIRES(mStateLock) {
            if (pendingTransactions) {
                return TransactionReadiness::NotReady;
            }

            return transactionIsReadyToBeApplied(transaction, transaction.frameTimelineInfo,
                                                 transaction.isAutoTimestamp,
                                                 transaction.desiredPresentTime,
                                                 transaction.originUid, transaction.states,
                                                 bufferLayersReadyToPresent,
                                                 transactions.size(),
                                                 /*tryApplyUnsignaled*/ false);
        }();
        if (ready != TransactionReadiness::Ready) {
            //没ready的话添加到mPendingTransactionQueues
            mPendingTransactionQueues[transaction.applyToken].push(std::move(transaction));
        }else{
            //已经ready则进入如下的操作
            transaction.traverseStatesWithBuffers([&](const layer_state_t& state) {
                const bool frameNumberChanged = state.bufferData->flags.test(
                    BufferData::BufferDataChange::frameNumberChanged);
                //会把state放入到bufferLayersReadyToPresent这个map中
                if (frameNumberChanged) { 
                    bufferLayersReadyToPresent[state.surface] = state.bufferData->frameNumber;
                } else {
                    // Barrier function only used for BBQ which always includes a frame number.
                    // This value only used for barrier logic.
                    bufferLayersReadyToPresent[state.surface] =
                        std::numeric_limits<uint64_t>::max();
                }
            });
            //最重要的放入到transactions
            transactions.emplace_back(std::move(transaction));
        }

    }
    //上面主要是遍历mTransactionQueue获取已经Ready的Transaction，添加到transactions
    //最后执行最关键的应用事务
    return applyTransactions(transactions, vsyncId);
}

//applyTransactions流程: 把事务中的对应flag的数据存入Layer
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
    //遍历所有客户端传过来的ComposerState
    for (int i = 0; i < states.size(); i++) {
        // 设置layer的具体信息
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
    if (what & layer_state_t::eBufferChanged) {
        //69  BLASTBufferQueue 传递buffer到SF的时候，调用Transaction::setBuffer 添加 eBufferChanged 标记，这里把buffer设置到layer里
        if (layer->setBuffer(buffer, *s.bufferData, postTime, desiredPresentTime, isAutoTimestamp,
                             dequeueBufferTimestamp, frameTimelineInfo)) {
            flags |= eTraversalNeeded;
        }
    }
    //...省略其他属性设置
    return flags;
}

//BufferStateLayer.cpp
bool BufferStateLayer::setBuffer(std::shared_ptr<renderengine::ExternalTexture>& buffer...) {
    //...
    mDrawingState.frameNumber = frameNumber;
    mDrawingState.releaseBufferListener = bufferData.releaseBufferListener;
    //这里的 mDrawingState 是 Layer 的，和SurfaceFlinger的 mDrawingState 不是一个类
    mDrawingState.buffer = std::move(buffer);
    //...
    mDrawingState.modified = true;
    setTransactionFlags(eTransactionNeeded);
    //...
}
```

在setClientStateLocked会把客户端传过来的layer_state_t里的数据设置到layer里。

+ 注释69处把buffer传到layer里，此layer即注释29处创建的BufferStateLayer

在BufferStateLayer的setBuffer中把buffer存到layer的mDrawingState的buffer里，和SurfaceFlinger的 mDrawingState 不是一个类。再看注释65的commitTransactions

```c++
void SurfaceFlinger::commitTransactions() {
    State drawingState(mDrawingState);
    //事务提交，先加锁
    Mutex::Autolock lock(mStateLock);
    modulateVsync(&VsyncModulator::onTransactionCommit);
    
    commitTransactionsLocked(clearTransactionFlags(eTransactionMask));
    mDebugInTransaction = 0;
}
void SurfaceFlinger::commitTransactionsLocked(uint32_t transactionFlags) {
    //屏幕的热插拔SurfaceFlinger::onComposerHalHotplug会调用 setTransactionFlags(eDisplayTransactionNeeded)，然后到这里处理
    const bool displayTransactionNeeded = transactionFlags & eDisplayTransactionNeeded;
    if (displayTransactionNeeded) { 
        processDisplayChangesLocked();
        processDisplayHotplugEventsLocked();
    }
    mForceTransactionDisplayChange = displayTransactionNeeded;
    //...
    //注释63处commitCreatedLayers流程中会把mLayersAdded设为true，这里还原
    if (mLayersAdded) {
        mLayersAdded = false;
        // Layers have been added.
        mVisibleRegionsDirty = true;
    }
    //如果是要处理移除layer
    if (mLayersRemoved) {
        mLayersRemoved = false;
        mVisibleRegionsDirty = true;
        mDrawingState.traverseInZOrder([&](Layer* layer) {
            if (mLayersPendingRemoval.indexOf(layer) >= 0) {
                // this layer is not visible anymore
                Region visibleReg;
                visibleReg.set(layer->getScreenBounds());
                invalidateLayerStack(layer, visibleReg);
            }
        });
    }
    //真正提交事务
    doCommitTransactions();
}
void SurfaceFlinger::doCommitTransactions() {
    //处理被移除的layer集合 mLayersPendingRemoval。释放buffer，从layersSortedByZ移除，加入到mOffscreenLayers
    if (!mLayersPendingRemoval.isEmpty()) {
        for (const auto& l : mLayersPendingRemoval) {
            if (l->isRemovedFromCurrentState()) {
                l->latchAndReleaseBuffer();
            }
            if (l->isAtRoot()) {
                l->setIsAtRoot(false);
                mCurrentState.layersSortedByZ.remove(l);
            }
            if (!l->getParent()) {
                mOffscreenLayers.emplace(l.get());
            }
        }
        mLayersPendingRemoval.clear();
    }
    //70 把已经处理过的mCurrentState直接赋值给mDrawingState
    mDrawingState = mCurrentState;
    mCurrentState.colorMatrixChanged = false;

    if (mVisibleRegionsDirty) {
        for (const auto& rootLayer : mDrawingState.layersSortedByZ) {
            rootLayer->commitChildList();
        }
    }
    commitOffscreenLayers();
}

void Layer::commitChildList() {
    //...
    mDrawingChildren = mCurrentChildren;
    mDrawingParent = mCurrentParent;
    //...
}
```

注释70处把已经处理过的mCurrentState直接赋值给mDrawingState，后面就会处理mDrawingState。继续看注释66处66 latchBuffers

```cpp
bool SurfaceFlinger::latchBuffers() {
    //...
  // Store the set of layers that need updates. This set must not change as
    // buffers are being latched, as this could result in a deadlock.
    // Example: Two producers share the same command stream and:
    // 1.) Layer 0 is latched
    // 2.) Layer 0 gets a new frame
    // 2.) Layer 1 gets a new frame
    // 3.) Layer 1 is latched.
    // Display is now waiting on Layer 1's frame, which is behind layer 0's
    // second frame. But layer 0's second frame could be waiting on display.
    // 上面英文注释说明这块是把有buffer更新的layer存储到set集合。在buffers latch的过程中，不能更新set集合，否则可能导致死锁
    mDrawingState.traverse([&](Layer* layer) {
        //layer各种属性变化后都会设置 eTransactionNeeded 这个flag，比如尺寸，背景，位置，inputInfo、刷新率等等，几乎所有的变化都会设置eTransactionNeeded。
        if (layer->clearTransactionFlags(eTransactionNeeded) || mForceTransactionDisplayChange) {
            const uint32_t flags = layer->doTransaction(0);
            if (flags & Layer::eVisibleRegion) {
                mVisibleRegionsDirty = true;
            }
        }
        // layer有新buffer时，hasReadyFrame() 返回true
        if (layer->hasReadyFrame()) {
            frameQueued = true;
            //对于 BufferStateLayer 而言，有buffer的来了，就返回true
            if (layer->shouldPresentNow(expectedPresentTime)) {
                //把有新buffer的layer，加入set集合 mLayersWithQueuedFrames
                mLayersWithQueuedFrames.emplace(layer);
                if (wakeUpPresentationDisplays) {
                    layerStackId = layer->getLayerStack().id;
                    layerStackIds.insert(layerStackId);
                }
            } else {
                ATRACE_NAME("!layer->shouldPresentNow()");
                layer->useEmptyDamage();
            }
        } else {
            layer->useEmptyDamage();
        }
    });
    mForceTransactionDisplayChange = false;
	//mLayersWithQueuedFrames集合不为空的话，遍历每个需要更新的layer，分别执行其latchBuffer
    if (!mLayersWithQueuedFrames.empty()) {
        for (const auto& layer : mLayersWithQueuedFrames) {
            //71 latchBuffer 判断是否需要重新计算显示区域，由参数visibleRegions带回结果
            if (layer->latchBuffer(visibleRegions, latchTime, expectedPresentTime)) {
                //把layer加入mLayersPendingRefresh，其保存了GPU绘制已经完成的layer
                mLayersPendingRefresh.push_back(layer);
                newDataLatched = true;
            }
            layer->useSurfaceDamage();
        }
    }
}
```

SurfaceFlinger::latchBuffers会把有buffer更新的layer先添加到mLayersWithQueuedFrames，再遍历此集合，分别执行每个layer的latchBuffer方法。

+ 注释71处每个layer执行latchBuffer方法，如果需要重新计算显示区域返回true，并加到mLayersPendingRefresh，此集合保存了GPU已经绘制完成的layer

看下每个layer执行的latchBuffer

```cpp
//BufferLayer.cpp
bool BufferLayer::latchBuffer(bool& recomputeVisibleRegions, nsecs_t latchTime,nsecs_t expectedPresentTime) {
    //如果Fence还没有发送信号，请求 SF-vsync，并且函数返回，等一下次的vsync再处理这个buffer
    if (!fenceHasSignaled()) {
        mFlinger->onLayerUpdate();
        return false;
    }
	//调用到BufferStateLayer中的updateTexImage，主要是为debug用的，构建一些tracer
    status_t err = updateTexImage(recomputeVisibleRegions, latchTime, expectedPresentTime);
    //state的相关buffer数据赋值给mBufferInfo
    err = updateActiveBuffer();
    //把mDrawingState中buffer相关信息的各种变量转移到 mBufferInfo 中
    gatherBufferInfo();

    //...
    return true;
}
```

BufferLayer的latchBuffer相当于java里的模版方法，里面调用的updateTexImage，updateActiveBuffer，gatherBufferInfo实际调用了父类BufferStateLayer的相关方法。

```cpp
status_t BufferStateLayer::updateTexImage(bool& /*recomputeVisibleRegions*/, nsecs_t latchTime,nsecs_t /*expectedPresentTime*/) {
	//...
    //每个layer有唯一的一个sequence，递增
    const int32_t layerId = getSequence();
    const uint64_t bufferId = mDrawingState.buffer->getId();
    const uint64_t frameNumber = mDrawingState.frameNumber;
    const auto acquireFence = std::make_shared<FenceTime>(mDrawingState.acquireFence);
    //perfetto 相应的数据源配置启用的情况下，记录 Fence 和 latchTime
    mFlinger->mTimeStats->setAcquireFence(layerId, frameNumber, acquireFence);
    mFlinger->mTimeStats->setLatchTime(layerId, frameNumber, latchTime);
    mFlinger->mFrameTracer->traceFence(layerId, bufferId, frameNumber, acquireFence,FrameTracer::FrameEvent::ACQUIRE_FENCE);
    mFlinger->mFrameTracer->traceTimestamp(layerId, bufferId, frameNumber, latchTime,FrameTracer::FrameEvent::LATCH);

    //如果mDrawingState.bufferSurfaceFrameTX非Presented状态，重置下
    auto& bufferSurfaceFrame = mDrawingState.bufferSurfaceFrameTX;
    if (bufferSurfaceFrame != nullptr &&
        bufferSurfaceFrame->getPresentState() != PresentState::Presented) {
        addSurfaceFramePresentedForBuffer(bufferSurfaceFrame,
                                          mDrawingState.acquireFenceTime->getSignalTime(),
                                          latchTime);
        mDrawingState.bufferSurfaceFrameTX.reset();
    }

    //设置下mDrawingState.callbackHandles
    std::deque<sp<CallbackHandle>> remainingHandles;
    mFlinger->getTransactionCallbackInvoker()
        .addOnCommitCallbackHandles(mDrawingState.callbackHandles, remainingHandles);
    mDrawingState.callbackHandles = remainingHandles;

    mDrawingStateModified = false;
    return NO_ERROR;
}

status_t BufferStateLayer::updateActiveBuffer() {
    const State& s(getDrawingState());

    if (s.buffer == nullptr) {
        return BAD_VALUE;
    }

    // buffer不为空，把待处理的buffer数量计数器mPendingBufferTransactions减一
    if (!mBufferInfo.mBuffer || !s.buffer->hasSameBuffer(*mBufferInfo.mBuffer)) {
        decrementPendingBufferCount();
    }

    mPreviousReleaseCallbackId = {getCurrentBufferId(), mBufferInfo.mFrameNumber};
    // 把 mDrawingState 中的buffer、acquireFence、frameNumber转移到mBufferInfo
    mBufferInfo.mBuffer = s.buffer;
    mBufferInfo.mFence = s.acquireFence;
    mBufferInfo.mFrameNumber = s.frameNumber;

    return NO_ERROR;
}

void BufferStateLayer::gatherBufferInfo() {
    BufferLayer::gatherBufferInfo();

    const State& s(getDrawingState());
    //把 mDrawingState 中的其他信息转移到mBufferInfo
    mBufferInfo.mDesiredPresentTime = s.desiredPresentTime;
    mBufferInfo.mFenceTime = std::make_shared<FenceTime>(s.acquireFence);
    mBufferInfo.mFence = s.acquireFence;
    mBufferInfo.mTransform = s.bufferTransform;
    auto lastDataspace = mBufferInfo.mDataspace;
    mBufferInfo.mDataspace = translateDataspace(s.dataspace);
    if (lastDataspace != mBufferInfo.mDataspace) {
        mFlinger->mSomeDataspaceChanged = true;
    }
    mBufferInfo.mCrop = computeBufferCrop(s);
    mBufferInfo.mScaleMode = NATIVE_WINDOW_SCALING_MODE_SCALE_TO_WINDOW;
    mBufferInfo.mSurfaceDamage = s.surfaceDamageRegion;
    mBufferInfo.mHdrMetadata = s.hdrMetadata;
    mBufferInfo.mApi = s.api;
    mBufferInfo.mTransformToDisplayInverse = s.transformToDisplayInverse;
    mBufferInfo.mBufferSlot = mHwcSlotGenerator->getHwcCacheSlot(s.clientCacheId);
}
```

可以看到latchBuffer调用的这几个方法主要还是想把前面Transaction时候赋值到了Layer的mDrawingState和buffer相关的数据赋值到Layer的mBufferInfo中。再继续看注释67处计算边界脏区域

```c++
void SurfaceFlinger::updateLayerGeometry() {
    //mVisibleRegionsDirty在有新的Layer增加时设置为 true
    if (mVisibleRegionsDirty) {
        //调用每个Layer的 computeBounds 方法
        computeLayerBounds();
    }

    //mLayersPendingRefresh这个集合表示有新的buffer更新，并且能拿到buffer的layer。在 SurfaceFlinger::latchBuffers()流程中添加(注释71处)
    for (auto& layer : mLayersPendingRefresh) {
        Region visibleReg;
        visibleReg.set(layer->getScreenBounds());
        // 对每个包含当前layer的显示器的脏区域初始化为 Layer.mScreenBounds
        invalidateLayerStack(layer, visibleReg);
    }

    setDisplayAnimating();
    //清空mLayersPendingRefresh
    mLayersPendingRefresh.clear();
}

void SurfaceFlinger::computeLayerBounds() {
    const FloatRect maxBounds = getMaxDisplayBounds();
    //遍历mDrawingState.layersSortedByZ，调用每个Layer的 computeBounds 方法
    for (const auto& layer : mDrawingState.layersSortedByZ) {
        layer->computeBounds(maxBounds, ui::Transform(), 0.f /* shadowRadius */);
    }
}
```

在有新layer增加时会调用每个layer的computeBounds，计算每个layer的边界；再根据SurfaceFlinger::latchBuffers时拿到的mLayersPendingRefresh这个集合计算脏区域。    

小结：commit流程会先把新创建的layer加入到Z轴排序集合体系 mCurrentState.layersSortedByZ里，然后执行每个layer执行latchBuffer方法来确定是否需要重新计算显示区域，再去计算边界和脏区域，如果需要合成就返回true，否则返回false。

##### SF的composite流程

再继续看composite合成流程。

```cpp
void SurfaceFlinger::composite(nsecs_t frameTime, int64_t vsyncId)
    FTL_FAKE_GUARD(kMainThreadContext) {
    //72 构建准备合成的参数
    compositionengine::CompositionRefreshArgs refreshArgs;
    //在SF.h文件定义 ftl::SmallMap<wp<IBinder>, const sp<DisplayDevice>, 5> mDisplays GUARDED_BY(mStateLock);
    //这种写法没看懂，从上下文理解应该是通过binder拿到所有的显示设备
    //合成是以mDisplays里面的顺序去合成的，内置的显示器是在开机时就加入的，所以优先于外部显示器
    const auto& displays = FTL_FAKE_GUARD(mStateLock, mDisplays);
    //增加容器的大小为显示屏的大小
    refreshArgs.outputs.reserve(displays.size());
    std::vector<DisplayId> displayIds;
    //遍历所有的显示器，调用每个getCompositionDisplay方法，获取需要合成的显示器
    for (const auto& [_, display] : displays) {
        //outputs代表所有需要合成的显示器
        refreshArgs.outputs.push_back(display->getCompositionDisplay());
        displayIds.push_back(display->getId());
    }
    mPowerAdvisor->setDisplays(displayIds);
    //遍历mDrawingState.layersSortedByZ，其存储了所有的的layer
    mDrawingState.traverseInZOrder([&refreshArgs](Layer* layer) {
        //只有 BufferLayer和EffectLayer 实现了这个方法，返回layer自身，并强转为父类Layer
        if (auto layerFE = layer->getCompositionEngineLayerFE())
            //添加所有需要合成的layer
            refreshArgs.layers.push_back(layerFE);
    });
    //mLayersWithQueuedFrames是有新的帧数据的Layer
    refreshArgs.layersWithQueuedFrames.reserve(mLayersWithQueuedFrames.size());
    //把有帧数据的Layer转移到 refreshArgs.layersWithQueuedFrames
    for (auto layer : mLayersWithQueuedFrames) {
        if (auto layerFE = layer->getCompositionEngineLayerFE())
            refreshArgs.layersWithQueuedFrames.push_back(layerFE);
    }
    //合成参数refreshArgs其他字段进行赋值
    refreshArgs.forceOutputColorMode = mForceColorMode;
    //...
    //73 合成参数传到合成引擎，开始真正合成工作
    mCompositionEngine->present(refreshArgs);
    //...
    //没啥东西，只是打了些log
    postFrame();
    //74 合成后的收尾工作，比如释放buffer
    postComposition();

}
```

SurfaceFlinger::composite这里合成流程较清晰。先在注释72处构建需要合成的参数，主要把需要合成的display，layer，有帧数据的layer等添加到此参数里，然后在注释73处通过合成引擎去真正完成合成工作，最后在注释74处postComposition做些收尾工作。我们主要看注释73处mCompositionEngine->present真正合成的流程，此过程较复杂，是SF合成的核心。

```cpp
void CompositionEngine::present(CompositionRefreshArgs& args) {
    //合成前预处理
    preComposition(args);
    {
        LayerFESet latchedLayers;
        //遍历所有需要合成输出的显示设备，核心是把显示区域相关数据转存到OutputLayer的mState对象
        for (const auto& output : args.outputs) {
            //74 调用每个显示设备的prepare方法
            output->prepare(args, latchedLayers);
        }
    }
    //75 把状态属性转移到 BufferLayer.mCompositionState
    //这个对象是 compositionengine::LayerFECompositionState 类型
    //可以通过 LayerFE.getCompositionState() 获取合成状态对象。
    //不算EffectLayer的话，其实就是就是获取 BufferLayer.mCompositionState
    updateLayerStateFromFE(args);
    //76 调用每个显示设备的present方法
    for (const auto& output : args.outputs) {
        output->present(args);
    }
}

void CompositionEngine::preComposition(CompositionRefreshArgs& args) {
    bool needsAnotherUpdate = false;
    for (auto& layer : args.layers) {
        //遍历每个layer执行其onPreComposition
        if (layer->onPreComposition(mRefreshStartTime)) {
            needsAnotherUpdate = true;
        }
    }
    mNeedsAnotherUpdate = needsAnotherUpdate;
}
```

合成前先执行每个layer的onPreComposition，不再细看。

+ 注释74先调用每个显示器的prepare
+ 注释75把状态属性转移到 BufferLayer.mCompositionState
+ 注释76调用每个显示器的present

重点看以上流程，先看注释74调用每个显示器的prepare

> frameworks/native/services/surfaceflinger/CompositionEngine/src/Output.cpp

```c++
void Output::prepare(const compositionengine::CompositionRefreshArgs& refreshArgs,LayerFESet& geomSnapshots) {
    rebuildLayerStacks(refreshArgs, geomSnapshots);
}
void Output::rebuildLayerStacks(const compositionengine::CompositionRefreshArgs& refreshArgs,LayerFESet& layerFESet) {
    //获取output状态，返回OutputCompositionState类型的Output.mState
    auto& outputState = editState();
    //如果output状态是关闭或者没必要更新直接返回
    if (!outputState.isEnabled || CC_LIKELY(!refreshArgs.updatingOutputGeometryThisFrame)) {
        return;
    }
    compositionengine::Output::CoverageState coverage{layerFESet};
    //收集所有可见的layer
    collectVisibleLayers(refreshArgs, coverage);
    
    const ui::Transform& tr = outputState.transform;
    Region undefinedRegion{outputState.displaySpace.getBoundsAsRect()};
    //整个显示空间减去所有Layer的不透明覆盖区域为未定义的区域
    undefinedRegion.subtractSelf(tr.transform(coverage.aboveOpaqueLayers));
    outputState.undefinedRegion = undefinedRegion;
    //把计算好的脏区域传入outputState.dirtyRegion 
    //在Output::postFramebuffer()中会被清空
    outputState.dirtyRegion.orSelf(coverage.dirtyRegion);
}

void Output::collectVisibleLayers(const compositionengine::CompositionRefreshArgs& refreshArgs,
                                  compositionengine::Output::CoverageState& coverage) {
    //从顶层到底层遍历所有参与合成的Layer，累积计算覆盖区域、完全不透明区域、脏区域
    //存储当前层的显示区域、排除透明区域的显示区域、被覆盖区域、显示器空间显示区域、阴影区域等到当前显示设备上的OutputLayer的mState对象
    for (auto layer : reversed(refreshArgs.layers)) {
        ensureOutputLayerIfVisible(layer, coverage);
    }

    //把不需要显示的layer放到mReleasedLayers，准备释放
    setReleasedLayers(refreshArgs);

    //把Output.mPendingOutputLayersOrderedByZ转到Output.mCurrentOutputLayersOrderedByZ
    finalizePendingOutputLayers();
}

void Output::ensureOutputLayerIfVisible(sp<compositionengine::LayerFE>& layerFE,
                                        compositionengine::Output::CoverageState& coverage) {
    Region opaqueRegion;
    Region visibleRegion;
    Region coveredRegion;
    Region transparentRegion;
    Region shadowRegion;
    //...省略计算这些区域的过程
    // The layer is visible. Either reuse the existing outputLayer if we have
    // one, or create a new one if we do not.
    //如果显示设备没有对应的 OutputLayer，创建 OutputLayer 同时创建 HWCLayer，并作为 OutputLayer.mState 的成员
    auto result = ensureOutputLayer(prevOutputLayerIndex, layerFE);
    //存储以上区域到当前显示设备上的OutputLayer的mState对象
    auto& outputLayerState = result->editState();
    outputLayerState.visibleRegion = visibleRegion;
    outputLayerState.visibleNonTransparentRegion = visibleNonTransparentRegion;
    outputLayerState.coveredRegion = coveredRegion;
    outputLayerState.outputSpaceVisibleRegion = outputState.transform.transform(
        visibleNonShadowRegion.intersect(outputState.layerStackSpace.getContent()));
    outputLayerState.shadowRegion = shadowRegion;
}
//Output.h
OutputLayer* ensureOutputLayer(std::optional<size_t> prevIndex,
                               const sp<LayerFE>& layerFE) {
    auto outputLayer = (prevIndex && *prevIndex <= mCurrentOutputLayersOrderedByZ.size())
        ? std::move(mCurrentOutputLayersOrderedByZ[*prevIndex])
        : BaseOutput::createOutputLayer(layerFE);
    auto result = outputLayer.get();
    mPendingOutputLayersOrderedByZ.emplace_back(std::move(outputLayer));
    return result;
}

//Display.cpp  
//Display继承于Output
std::unique_ptr<compositionengine::OutputLayer> Display::createOutputLayer(
    const sp<compositionengine::LayerFE>& layerFE) const {
    //先创建OutputLayer
    auto outputLayer = impl::createOutputLayer(*this, layerFE);
    if (const auto halDisplayId = HalDisplayId::tryCast(mId);
        outputLayer && !mIsDisconnected && halDisplayId) {
        //同时创建 HWCLayer，并作为 OutputLayer.mState 的成员
        auto& hwc = getCompositionEngine().getHwComposer();
        auto hwcLayer = hwc.createLayer(*halDisplayId);
        outputLayer->setHwcLayer(std::move(hwcLayer));
        //...
    }
}

//setReleasedLayers 函数会遍历 Output.mCurrentOutputLayersOrderedByZ
//此时Output.mCurrentOutputLayersOrderedByZ中在当前vsync显示的layer都转移到了mPendingOutputLayersOrderedByZ
// 这里会把mCurrentOutputLayersOrderedByZ余下的Layer中，在当前vsync，入队新的buffer的layer放入到 Output.mReleasedLayers 中
//就是说，mReleasedLayers是一些即将移除的的layer，但是当前vsync还在生产帧数据的layer
void Display::setReleasedLayers(const compositionengine::CompositionRefreshArgs& refreshArgs) {
    compositionengine::Output::ReleasedLayers releasedLayers;
    for (auto* outputLayer : getOutputLayersOrderedByZ()) {
        compositionengine::LayerFE* layerFE = &outputLayer->getLayerFE();
        const bool hasQueuedFrames =
            std::any_of(refreshArgs.layersWithQueuedFrames.cbegin(),
                        refreshArgs.layersWithQueuedFrames.cend(),
                        [layerFE](sp<compositionengine::LayerFE> layerWithQueuedFrames) {
                            return layerFE == layerWithQueuedFrames.get();
                        });

        if (hasQueuedFrames) {
            releasedLayers.emplace_back(layerFE);
        }
    }

    setReleasedLayers(std::move(releasedLayers));
}
//Output.h
//把Output.mPendingOutputLayersOrderedByZ转到Output.mCurrentOutputLayersOrderedByZ
//每个vsync内，Output中存储的OutputLayer，都是最新即将要显示的Layer
void finalizePendingOutputLayers() override {
    // The pending layers are added in reverse order. Reverse them to
    // get the back-to-front ordered list of layers.
    //pendding layers添加的时候是反的，所以先把其反转下再赋值给mCurrentOutputLayersOrderedByZ
    std::reverse(mPendingOutputLayersOrderedByZ.begin(),
                 mPendingOutputLayersOrderedByZ.end());

    mCurrentOutputLayersOrderedByZ = std::move(mPendingOutputLayersOrderedByZ);
}
```

prepare的过程，其实就是从顶层到底层遍历所有参与合成的Layer，计算其显示区域等到当前显示设备上的OutputLayer的mState上，把不需要显示的layer放到mReleasedLayers。继续看注释75的updateLayerStateFromFE(args)

```c++
//CompositionEngine.cpp
void CompositionEngine::updateLayerStateFromFE(CompositionRefreshArgs& args) {
    for (const auto& output : args.outputs) {
        output->updateLayerStateFromFE(args);
    }
}
//Output.cpp
void Output::updateLayerStateFromFE(const CompositionRefreshArgs& args) const {
    for (auto* layer : getOutputLayersOrderedByZ()) {
        //有新的Layer增加时 mVisibleRegionsDirty 为true,updatingGeometryThisFrame也为true
        //所以传进去的状态是GeometryAndContent
        layer->getLayerFE().prepareCompositionState(
            args.updatingGeometryThisFrame ? LayerFE::StateSubset::GeometryAndContent
            : LayerFE::StateSubset::Content);
    }
}
//Layer.cpp
void Layer::prepareCompositionState(compositionengine::LayerFE::StateSubset subset) {
    using StateSubset = compositionengine::LayerFE::StateSubset;
	//根据传的状态区分，上面传过来的是GeometryAndContent
    switch (subset) {
        case StateSubset::BasicGeometry:
            prepareBasicGeometryCompositionState();
            break;

        case StateSubset::GeometryAndContent:
            //更新BufferLayer状态对象LayerFECompositionState(mCompositionState)的以下属性
            //outputFilter、isVisible、isOpaque、shadowRadius、contentDirty、geomLayerBounds、geomLayerTransform
            //geomInverseLayerTransform、transparentRegionHint、blendMode、alpha、backgroundBlurRadius、blurRegions、stretchEffect
            prepareBasicGeometryCompositionState();
            //更新以下属性：geomBufferSize、geomContentCrop、geomCrop、geomBufferTransform
            //geomBufferUsesDisplayInverseTransform|geomUsesSourceCrop、isSecure、metadata
            //就是集合图形相关的属性
            prepareGeometryCompositionState();
            //更新以下属性： compositionType、hdrMetadata、compositionType、buffer、bufferSlot、acquireFence、frameNumber
            //sidebandStreamHasFrame、forceClientComposition、isColorspaceAgnostic、dataspace、
            //colorTransform、colorTransformIsIdentity、surfaceDamage、hasProtectedContent、
            //dimmingEnabled、isOpaque、stretchEffect、blurRegions、backgroundBlurRadius、fps
            //就是帧数据相关的属性
            preparePerFrameCompositionState();
            break;

        case StateSubset::Content:
            preparePerFrameCompositionState();
            break;

        case StateSubset::Cursor:
            prepareCursorCompositionState();
            break;
    }
}
//只看prepareBasicGeometryCompositionState，另外两个方法类同
void Layer::prepareBasicGeometryCompositionState() {
    auto* compositionState = editCompositionState();
    compositionState->outputFilter = getOutputFilter();
    compositionState->isVisible = isVisible();
    compositionState->isOpaque = opaque && !usesRoundedCorners && alpha == 1.f;
    compositionState->shadowRadius = mEffectiveShadowRadius;
    compositionState->contentDirty = contentDirty;
    contentDirty = false;

    compositionState->geomLayerBounds = mBounds;
    compositionState->geomLayerTransform = getTransform();
    compositionState->geomInverseLayerTransform = compositionState->geomLayerTransform.inverse();
    compositionState->transparentRegionHint = getActiveTransparentRegion(drawingState);

    compositionState->blendMode = static_cast<Hwc2::IComposerClient::BlendMode>(blendMode);
    compositionState->alpha = alpha;
    compositionState->backgroundBlurRadius = drawingState.backgroundBlurRadius;
    compositionState->blurRegions = drawingState.blurRegions;
    compositionState->stretchEffect = getStretchEffect();
}
```

updateLayerStateFromFE其实就是把状态属性转移到 BufferLayer.mCompositionState。再看注释76调用每个显示器的present

```c++
void Output::present(const compositionengine::CompositionRefreshArgs& refreshArgs) {
    //更新colorMode、dataspace、renderIntent、targetDataspace 属性 
    updateColorProfile(refreshArgs);
    //更新forceClientComposition、displayFrame、sourceCrop、bufferTransform、dataspace属性
    //到这里OutputLayer.mState的属性基本全更新完了
    updateCompositionState(refreshArgs);
    //计划合成。需要ro.surface_flinger.enable_layer_caching这个属性为true，mLayerCachingEnabled=true，然后才会启用 Planner
    planComposition();
    //77 把状态属性写入HWC待执行的缓存，等待执行
    writeCompositionState(refreshArgs);
    //设置显示设备的颜色矩阵，做颜色变换，色盲、护眼模式等
    setColorTransform(refreshArgs);
    //给虚拟显示屏用的。正常主屏使用 FramebufferSurface ，啥事也不做
    beginFrame();

    GpuCompositionResult result;
    //是否可以预测合成策略--Android 13 新增。正常情况下返回false
    //如果上次不全是硬件合成，且存在GPU合成Layer时，返回true    
    const bool predictCompositionStrategy = canPredictCompositionStrategy(refreshArgs);
    if (predictCompositionStrategy) {
        //异步执行 chooseCompositionStrategy --Android 13 新增
        //就是 chooseCompositionStrategy 和 GPU合成 并行执行。用以节约时间
        result = prepareFrameAsync(refreshArgs);
    } else {
        //78 核心逻辑还是确认使用客户端合成还是硬件合成
        prepareFrame();
    }
    //doDebugFlashRegions当打开开发者选项中的“显示Surface刷新”时，额外会产生变化的图层绘制闪烁动画
    devOptRepaintFlash(refreshArgs);
    //79 GPU合成，新增了一些对prepareFrameAsync结果的处理逻辑
    finishFrame(refreshArgs, std::move(result));
    //80 postFramebuffer 函数就是告诉HWC开始做最后的合成了,并显示。获取释放fence，
    postFramebuffer();
    //渲染最新的 cached sets，需要开启了Planner。
    renderCachedSets(refreshArgs);
}
//设置显示设备的颜色矩阵，做颜色变换，色盲、护眼模式等
void Output::setColorTransform(const compositionengine::CompositionRefreshArgs& args) {
    auto& colorTransformMatrix = editState().colorTransformMatrix;
    colorTransformMatrix = *args.colorTransformMatrix;
    dirtyEntireOutput();
}
void Output::dirtyEntireOutput() {
    auto& outputState = editState();
    outputState.dirtyRegion.set(outputState.displaySpace.getBoundsAsRect());
}
//当打开开发者选项中的“显示Surface刷新”时，额外会产生变化的图层绘制闪烁动画
void Output::devOptRepaintFlash(const compositionengine::CompositionRefreshArgs& refreshArgs) {
    if (getState().isEnabled) {
        if (const auto dirtyRegion = getDirtyRegion(); !dirtyRegion.isEmpty()) {
            base::unique_fd bufferFence;
            std::shared_ptr<renderengine::ExternalTexture> buffer;
            updateProtectedContentState();
            dequeueRenderBuffer(&bufferFence, &buffer);
            static_cast<void>(composeSurfaces(dirtyRegion, refreshArgs, buffer, bufferFence));
            mRenderSurface->queueBuffer(base::unique_fd());
        }
    }
    postFramebuffer();
    prepareFrame();
}
//渲染最新的 cached sets，需要开启了Planner。
void Output::renderCachedSets(const CompositionRefreshArgs& refreshArgs) {
    if (mPlanner) {
        mPlanner->renderCachedSets(getState(), refreshArgs.scheduledFrameTime,
                                   getState().usesDeviceComposition || getSkipColorTransform());
    }
}
```

Output::present是合成流程里的核心。

+ 注释77处writeCompositionState把状态属性写入HWC待执行的缓存，等待执行
+ 注释78处prepareFrame确认使用客户端合成还是硬件合成
+ 注释79处finishFrame通过GPU合成，新增了一些对prepareFrameAsync结果的处理逻辑
+ 注释80处postFramebuffer告诉HWC做最后的合成并显示

先看注释77

```c++
void Output::writeCompositionState(const compositionengine::CompositionRefreshArgs& refreshArgs) {
    //...
    for (auto* layer : getOutputLayersOrderedByZ()) {
        //如果上一个Layer的buffer和当前的Layer的buffer一样，那么忽略当前Layer
        if (previousOverride && overrideInfo.buffer->getBuffer() == previousOverride) {
            skipLayer = true;
        }
        //...
        constexpr bool isPeekingThrough = false;
        //把状态属性写入HWC待执行的缓存，等待执行
        layer->writeStateToHWC(includeGeometry, skipLayer, z++, overrideZ, isPeekingThrough);
    }
}
//OutputLayer.cpp
void OutputLayer::writeStateToHWC(bool includeGeometry, bool skipLayer, uint32_t z,
                                  bool zIsOverridden, bool isPeekingThrough) {
    const auto& state = getState();
    //如果没有HWC接口，直接返回
    if (!state.hwc) {
        return;
    }
    //如果没有hwcLayer直接返回
    auto& hwcLayer = (*state.hwc).hwcLayer;
    if (!hwcLayer) {
        return;
    }
    //获取Layer的LayerFECompositionState,只有BufferLayer和EffectLayer有
    const auto* outputIndependentState = getLayerFE().getCompositionState();
    if (!outputIndependentState) {
        return;
    }
    //合成类型
    auto requestedCompositionType = outputIndependentState->compositionType;
    const bool isOverridden =
            state.overrideInfo.buffer != nullptr || isPeekingThrough || zIsOverridden;
    const bool prevOverridden = state.hwc->stateOverridden;
    //有忽略的layer，新增的layer，重写的layer等这4种之一
    if (isOverridden || prevOverridden || skipLayer || includeGeometry) {
        //写入HWC依赖显示设备的几何图形信息
        //HWC2::Layer.setDisplayFrame、setSourceCrop、setZOrder、setTransform
        writeOutputDependentGeometryStateToHWC(hwcLayer.get(), requestedCompositionType, z);
        // 写入HWC不依赖显示设备的几何图形信息
        // HWC2::Layer.setBlendMode、setPlaneAlpha、setLayerGenericMetadata
        writeOutputIndependentGeometryStateToHWC(hwcLayer.get(), *outputIndependentState,
                                                 skipLayer);
    }
    // 写入HWC依赖显示设备的每个帧状态 
    // HWC2::Layer.etVisibleRegion、setBlockingRegion、setDataspace、setBrightness
    writeOutputDependentPerFrameStateToHWC(hwcLayer.get());
    // 写入HWC不依赖显示设备的每帧状态
    // HWC2::Layer.setColorTransform、setSurfaceDamage、setPerFrameMetadata、setBuffer
    writeOutputIndependentPerFrameStateToHWC(hwcLayer.get(), *outputIndependentState, skipLayer);
    // 写入合成类型  HWC2::Layer.setCompositionType
    writeCompositionTypeToHWC(hwcLayer.get(), requestedCompositionType, isPeekingThrough,
                              skipLayer);
    writeSolidColorStateToHWC(hwcLayer.get(), *outputIndependentState);

    editState().hwc->stateOverridden = isOverridden;
    editState().hwc->layerSkipped = skipLayer;
}
//往HWC2::Layer设置了以下：setDisplayFrame、setSourceCrop、setZOrder、setTransform
//另外3个方法类似，略
void OutputLayer::writeOutputDependentGeometryStateToHWC(HWC2::Layer* hwcLayer,Composition requestedCompositionType,uint32_t z) {
    if (auto error = hwcLayer->setDisplayFrame(displayFrame); error != hal::Error::NONE) {}
    if (auto error = hwcLayer->setSourceCrop(sourceCrop); error != hal::Error::NONE) {}
    if (auto error = hwcLayer->setZOrder(z); error != hal::Error::NONE) {}
    
    const auto bufferTransform = (requestedCompositionType != Composition::SOLID_COLOR &&
                                  getState().overrideInfo.buffer == nullptr)
        ? outputDependentState.bufferTransform
        : static_cast<hal::Transform>(0);
    if (auto error = hwcLayer->setTransform(static_cast<hal::Transform>(bufferTransform));
        error != hal::Error::NONE) {}
}
```

writeCompositionState把状态属性写入HWC待执行的缓存。  

再看注释78处prepareFrame确认使用客户端合成还是硬件合成。

> 所谓 Client 合成，就是 HWC 的客户端SurfaceFlinger去合成，SurfaceFlinger合成的话使用GPU合成，因此Client合成，即GPU合成

```cpp
void Output::prepareFrame() {
    auto& outputState = editState();
    std::optional<android::HWComposer::DeviceRequestedChanges> changes;
    //81 选择合成策略
    bool success = chooseCompositionStrategy(&changes);
    //82 重置合成策略
    resetCompositionStrategy();
    outputState.strategyPrediction = CompositionStrategyPredictionState::DISABLED;
    //这个previousDeviceRequestedChanges变量在Output::canPredictCompositionStrategy函数中用来判断是否预测合成策略
    outputState.previousDeviceRequestedChanges = changes;
    outputState.previousDeviceRequestedSuccess = success;
    if (success) {
        //83 应用合成策略
        applyCompositionStrategy(changes);
    }
    //用于虚拟屏
    finishPrepareFrame();
}
void Output::finishPrepareFrame() {
    const auto& state = getState();
    if (mPlanner) {
        mPlanner->reportFinalPlan(getOutputLayersOrderedByZ());
    }
    // 准备帧进行渲染----这里只有虚拟屏幕实现了prepareFrame，其他什么也不做
    mRenderSurface->prepareFrame(state.usesClientComposition, state.usesDeviceComposition);
}
void Output::resetCompositionStrategy() {
    //先重置这些变量，在后续的流程中会使用。相当于设置默认值，因为对基本的Output实现只能进行客户端合成
    auto& outputState = editState();
    outputState.usesClientComposition = true;
    outputState.usesDeviceComposition = false;
    outputState.reusedClientComposition = false;
}
```

prepareFrame同HWC服务交互去确认是否有客户端合成，如果没有客户端合成，并可以忽略验证，那么会直接显示，那么流程到这里基本结束了。我们详细看下注释81和83

```c++
//Display.cpp
bool Display::chooseCompositionStrategy(
    std::optional<android::HWComposer::DeviceRequestedChanges>* outChanges) {
    //...
    //获取HWC服务
    auto& hwc = getCompositionEngine().getHwComposer();
    //最近的一次合成中如果有任意一个layer是 GPU 合成，则返回true
    const bool requiresClientComposition = anyLayersRequireClientComposition();
    //调用HWC服务的getDeviceCompositionChanges
    if (status_t result =
        hwc.getDeviceCompositionChanges(*halDisplayId, requiresClientComposition,
                                        getState().earliestPresentTime,
                                        getState().previousPresentFence,
                                        getState().expectedPresentTime, outChanges);
        result != NO_ERROR) {
        return false;
    }
    return true;
}
//HWComposer.cpp
status_t HWComposer::getDeviceCompositionChanges(HalDisplayId displayId...) {
    //如果有需要GPU合成的layer， canSkipValidate=false;
    //没有需要GPU合成的layer，如果Composer支持获得预期的展示时间，canSkipValidate=true;
    //没有需要GPU合成的layer，Composer也不支持获得预期的当前时间，只有当我们知道我们不会提前呈现时，我们才能跳过验证。
    const bool canSkipValidate = [&] {
        if (frameUsesClientComposition) {
            return false;
        }
        if (mComposer->isSupported(Hwc2::Composer::OptionalFeature::ExpectedPresentTime)) {
            return true;
        }
        return std::chrono::steady_clock::now() >= earliestPresentTime ||
            previousPresentFence->getSignalTime() == Fence::SIGNAL_TIME_PENDING;
    }();
    //如果可以跳过验证就跳过，否则执行validate验证
    if (canSkipValidate) {
        sp<Fence> outPresentFence;
        uint32_t state = UINT32_MAX;
        //如果可以跳过验证，则直接在屏幕上显示内容,否则执行 validate
        //HWC会检查各个图层的状态，并确定如何进行合成
        error = hwcDisplay->presentOrValidate(expectedPresentTime, &numTypes, &numRequests,
                                              &outPresentFence, &state);
        // state = 0 --> Only Validate.
        // state = 1 --> Validate and commit succeeded. Skip validate case. No comp changes.
        // state = 2 --> Validate and commit succeeded. Query Comp changes.
        if (state == 1) { //Present Succeeded.
            //返回状态为1直接显示并返回，结束此流程
            std::unordered_map<HWC2::Layer*, sp<Fence>> releaseFences;
            error = hwcDisplay->getReleaseFences(&releaseFences);
            displayData.releaseFences = std::move(releaseFences);
            displayData.lastPresentFence = outPresentFence;
            displayData.validateWasSkipped = true;
            displayData.presentError = error;
            return NO_ERROR;
        }
    } else {
        //HWC 检查各个图层的状态，并确定如何进行合成
        //numTypes返回合成类型需要变更的layer数量
        //numRequests 返回需要做getDisplayRequests请求的layer数量
        error = hwcDisplay->validate(expectedPresentTime, &numTypes, &numRequests);
    }

    //ChangedTypes =std::unordered_map<HWC2::Layer*,
    //              aidl::android::hardware::graphics::composer3::Composition>;
    //composer3::Composition 为枚举类型：
    //{INVALID = 0, CLIENT = 1,  DEVICE = 2,  SOLID_COLOR = 3,  CURSOR = 4,  SIDEBAND = 5,  DISPLAY_DECORATION = 6,}; 
    //changedTypes即是map<Layer,Composition> 
    android::HWComposer::DeviceRequestedChanges::ChangedTypes changedTypes;
    //map扩容为numTypes
    changedTypes.reserve(numTypes);
    //返回包含所有与上次调用validateDisplay之前设置的合成类型不同的合成类型的Layer
    error = hwcDisplay->getChangedCompositionTypes(&changedTypes);

    //枚举hal::DisplayRequest{FLIP_CLIENT_TARGET = 1u , WRITE_CLIENT_TARGET_TO_OUTPUT = 2u}
    //0表示：指示客户端提供新的客户端目标缓冲区，即使没有为客户端合成标记任何layers。
    //1表示：指示客户端将客户端合成的结果直接写入虚拟显示输出缓冲区。
    auto displayRequests = static_cast<hal::DisplayRequest>(0);
    //LayerRequests = std::unordered_map<HWC2::Layer*, hal::LayerRequest>;
    //枚举 hal::LayerRequest{CLEAR_CLIENT_TARGET = 1 << 0,  }
    //1表示：客户端必须在该layer所在的位置使用透明像素清除其目标。如果必须混合该层，客户端可能会忽略此请求。
    android::HWComposer::DeviceRequestedChanges::LayerRequests layerRequests;
    //map扩容为numRequests
    layerRequests.reserve(numRequests);
    //返回hal::DisplayRequest和每个Layer的hal::LayerRequest类型，用于指导SurfaceFlinger的GPU合成
    error = hwcDisplay->getRequests(&displayRequests, &layerRequests);
    //返回clienttarget属性，这个新增的，目前可能仅仅实现了亮度、调光、数据格式、数据空间相关属性
    DeviceRequestedChanges::ClientTargetProperty clientTargetProperty;
    error = hwcDisplay->getClientTargetProperty(&clientTargetProperty);
    //组合成DeviceRequestedChanges结构体，返回
    outChanges->emplace(DeviceRequestedChanges{std::move(changedTypes), std::move(displayRequests),
                                               std::move(layerRequests),
                                               std::move(clientTargetProperty)});
    if (acceptChanges) {
        error = hwcDisplay->acceptChanges();
    }
    return NO_ERROR;
}
```

chooseCompositionStrategy会先判断是否跳过校验，如果需要跳过，就执行hwcDisplay->presentOrValidate，如果返回状态为1就直接显示，结束此流程，否则组合个DeviceRequestedChanges返回。再看注释83处applyCompositionStrategy

```c++
void Display::applyCompositionStrategy(const std::optional<DeviceRequestedChanges>& changes) {
    if (changes) {
        //应用注释81处组合的DeviceRequestedChanges结构体里的这几个参数
        applyChangedTypesToLayers(changes->changedTypes);
        applyDisplayRequests(changes->displayRequests);
        applyLayerRequestsToLayers(changes->layerRequests);
        applyClientTargetRequests(changes->clientTargetProperty);
    }

    // Determine what type of composition we are doing from the final state
    auto& state = editState();
    state.usesClientComposition = anyLayersRequireClientComposition();
    state.usesDeviceComposition = !allLayersRequireClientComposition();
}
void Display::applyChangedTypesToLayers(const ChangedTypes& changedTypes) {
    for (auto* layer : getOutputLayersOrderedByZ()) {
        //获取OutputLayer中存储的HWC::Layer
        auto hwcLayer = layer->getHwcLayer();
        if (auto it = changedTypes.find(hwcLayer); it != changedTypes.end()) {
            //应用HWC设备要求的合成类型更改
            layer->applyDeviceCompositionTypeChange(
                static_cast<aidl::android::hardware::graphics::composer3::Composition>(
                    it->second));
        }
    }
}
//应用设备合成类型变更
void OutputLayer::applyDeviceCompositionTypeChange(Composition compositionType) {
    auto& state = editState();
    auto& hwcState = *state.hwc;
    if (!hwcState.layerSkipped) {
        detectDisallowedCompositionTypeChange(hwcState.hwcCompositionType, compositionType);
    }
    //这个OutputLayerCompositionState::Hwc.hwcCompositionType用于预测合成策略 Output::canPredictCompositionStrategy
    //compositionType代表最近使用的合成类型
    hwcState.hwcCompositionType = compositionType;
}
void Display::applyDisplayRequests(const DisplayRequests& displayRequests) {
    auto& state = editState();
    //如果displayRequests包含flag FLIP_CLIENT_TARGET，则flipClientTarget=true
    //如果为true，则在执行客户端合成时应提供新的客户端目标(client target)缓冲区
    state.flipClientTarget = (static_cast<uint32_t>(displayRequests) &
                              static_cast<uint32_t>(hal::DisplayRequest::FLIP_CLIENT_TARGET)) != 0;
}
void Display::applyLayerRequestsToLayers(const LayerRequests& layerRequests) {
    for (auto* layer : getOutputLayersOrderedByZ()) {
        layer->prepareForDeviceLayerRequests();
        auto hwcLayer = layer->getHwcLayer();
        if (auto it = layerRequests.find(hwcLayer); it != layerRequests.end()) {
            //应用HWC的layer请求,只是把state.clearClientTarget设为true
            layer->applyDeviceLayerRequest(
                static_cast<Hwc2::IComposerClient::LayerRequest>(it->second));
        }
    }
}
void OutputLayer::applyDeviceLayerRequest(hal::LayerRequest request) {
    auto& state = editState();
    switch (request) {
        case hal::LayerRequest::CLEAR_CLIENT_TARGET:
            //客户端合成时，该layer所在的位置使用透明像素清除其目标
            state.clearClientTarget = true;
            break;
        default:
            break;
    }
}
void Display::applyClientTargetRequests(const ClientTargetProperty& clientTargetProperty) {
    editState().dataspace =
        static_cast<ui::Dataspace>(clientTargetProperty.clientTargetProperty.dataspace);
    editState().clientTargetBrightness = clientTargetProperty.brightness;
    editState().clientTargetDimmingStage = clientTargetProperty.dimmingStage;
    getRenderSurface()->setBufferDataspace(editState().dataspace);
    getRenderSurface()->setBufferPixelFormat(
        static_cast<ui::PixelFormat>(clientTargetProperty.clientTargetProperty.pixelFormat));
}
```

applyCompositionStrategy只是把chooseCompositionStrategy组合的DeviceRequestedChanges里的参数分别应用到State中。再继续看注释79处finishFrame处理Client合成（GPU合成）

```c++
void Output::finishFrame(const CompositionRefreshArgs& refreshArgs, GpuCompositionResult&& result) {
    const auto& outputState = getState();
    std::optional<base::unique_fd> optReadyFence;
    std::shared_ptr<renderengine::ExternalTexture> buffer;
    base::unique_fd bufferFence;
    //上面如果走prepareFrame()，则outputState.strategyPrediction为DISABLED，表示不使用预测合成策略
    //走prepareFrameAsync()为SUCCESS或者FAIL表示合预测成功或者失败
    if (outputState.strategyPrediction == CompositionStrategyPredictionState::SUCCESS) {
        //如果预测成功，则已经执行完 GPU 合成了，不必再dequeueRenderBuffer了
        optReadyFence = std::move(result.fence);
    } else {
        //虽然预测失败了，但是已经dequeued的buffer，会通过GpuCompositionResult传送过来，拿来复用
        if (result.bufferAvailable()) {
            buffer = std::move(result.buffer);
            bufferFence = std::move(result.fence);
        } else {
            //dequeueRenderBuffer失败，连dequeued的buffer都没有，那就重走一遍prepareFrameAsync GPU合成的那块逻辑。当然，也可能就没有执行 prepareFrameAsync
            updateProtectedContentState();
            //BufferQueue的dequeueBuffer操作
            if (!dequeueRenderBuffer(&bufferFence, &buffer)) {
                return;
            }
        }
        //84 执行GPU合成
        optReadyFence = composeSurfaces(Region::INVALID_REGION, refreshArgs, buffer, bufferFence);
    }

    //BufferQueue的queueBuffer操作，把GPU合成之后的buffer入队
    mRenderSurface->queueBuffer(std::move(*optReadyFence));
}

bool Output::dequeueRenderBuffer(base::unique_fd* bufferFence,
                                 std::shared_ptr<renderengine::ExternalTexture>* tex) {
    const auto& outputState = getState();
    //有使用客户端合成或者设置了flipClientTarget都需要进行GPU合成
    if (outputState.usesClientComposition || outputState.flipClientTarget) {
        //dequeueBuffer，然后把dequeue的Buffer包装为ExternalTexture
        *tex = mRenderSurface->dequeueBuffer(bufferFence);
        if (*tex == nullptr) {
            return false;
        }
    }
    return true;
}
std::shared_ptr<renderengine::ExternalTexture> RenderSurface::dequeueBuffer(base::unique_fd* bufferFence) {
    //调用Surface.dequeueBuffer,前面小节说过，Surface即ANativeWindow
    status_t result = mNativeWindow->dequeueBuffer(mNativeWindow.get(), &buffer, &fd);
    // GraphicBuffer继承ANativeWindowBuffer
    // GraphicBuffer::from 把父类ANativeWindowBuffer强制转换为子类GraphicBuffer
    sp<GraphicBuffer> newBuffer = GraphicBuffer::from(buffer);
    std::shared_ptr<renderengine::ExternalTexture> texture;
    //...
    //ExternalTexture使用RenderEngine代表客户端管理GPU图像资源。
    //渲染引擎不是GLES的话，在ExternalTexture构造函数中把GraphicBuffer映射到RenderEngine所需的GPU资源中。
    mTexture = std::make_shared<
        renderengine::impl::ExternalTexture>(GraphicBuffer::from(buffer),
                                             mCompositionEngine.getRenderEngine(),
                                             renderengine::impl::ExternalTexture::Usage::
                                             WRITEABLE);

    mTextureCache.push_back(mTexture);
    if (mTextureCache.size() > mMaxTextureCacheSize) {
        mTextureCache.erase(mTextureCache.begin());
    }
    //返回fence fd
    *bufferFence = base::unique_fd(fd);
    // 返回纹理类
    return mTexture;
}
void RenderSurface::queueBuffer(base::unique_fd readyFence) {
    auto& state = mDisplay.getState();
    if (state.usesClientComposition || state.flipClientTarget || mFlipClientTarget) {
        //调用Surface.queueBuffer
        status_t result =
            mNativeWindow->queueBuffer(mNativeWindow.get(),
                                       mTexture->getBuffer()->getNativeBuffer(),
                                       mFlipClientTarget ? -1 : dup(readyFence));
        //...
        mTexture = nullptr;
    }
    //消费buffer
    //对于非虚拟屏是FramebufferSurface，其包装消费者、继承DisplaySurface；
    //对于虚拟屏是VirtualDisplaySurface
    status_t result = mDisplaySurface->advanceFrame();
}
//FramebufferSurface.cpp
status_t FramebufferSurface::advanceFrame() {
    uint32_t slot = 0;
    sp<GraphicBuffer> buf;
    sp<Fence> acquireFence(Fence::NO_FENCE);
    Dataspace dataspace = Dataspace::UNKNOWN;
    //消费这块buffer
    status_t result = nextBuffer(slot, buf, acquireFence, dataspace);
    return result;
}
status_t FramebufferSurface::nextBuffer(uint32_t& outSlot,
        sp<GraphicBuffer>& outBuffer, sp<Fence>& outFence,
        Dataspace& outDataspace) {
	//...
    //把客户端合成输出的缓冲区句柄传递给HWC。
    //目前还未真正传递到HWC，只是写到命令缓冲区。需要等待执行 present 才执行传递
    status_t result = mHwc.setClientTarget(mDisplayId, outSlot, outFence, outBuffer, outDataspace);
    //...
    return NO_ERROR;
}
```

注释84处composeSurfaces是GPU合成的核心，后面详细讲。然后在queueBuffer里把gpu合成后的buffer和fence传给HWC，其实这里并没真正传递给HWC，只是写到命令缓冲区，需要在postFramebuffer中执行到present时才执行传递

先继续看注释80处postFramebuffer告诉HWC做最后的合成并显示

```cpp
//Output.h
struct FrameFences {
    //这个fence，在当前帧在屏幕上显现，或者发送到面板内存时，会发送信号
    sp<Fence> presentFence{Fence::NO_FENCE};
    //GPU 合成的buffer的消费者 acquire fence；代表GPU合成是否完成；(注：GPU绘制的acquire fence，在latchBuffer阶段已经判断了。)
    sp<Fence> clientTargetAcquireFence{Fence::NO_FENCE};
    //所有Layer的fence；在先前呈现的buffer被读取完成后发送信息；HWC生成
    std::unordered_map<HWC2::Layer*, sp<Fence>> layerFences;
};
//Output.cpp
void Output::postFramebuffer() {
    auto& outputState = editState();
    outputState.dirtyRegion.clear();
    mRenderSurface->flip();
    //显示并获取FrameFences
    auto frame = presentAndGetFrameFences();
    //释放GPU合成使用的buffer
    mRenderSurface->onPresentDisplayCompleted();
    for (auto* layer : getOutputLayersOrderedByZ()) {
        sp<Fence> releaseFence = Fence::NO_FENCE;
        // 获取HWC每个layer的releaseFence
        if (auto hwcLayer = layer->getHwcLayer()) {
            if (auto f = frame.layerFences.find(hwcLayer); f != frame.layerFences.end()) {
                releaseFence = f->second;
            }
        }
        //如果有客户端合成，merge GPUbuffer的fence
        if (outputState.usesClientComposition) {
            releaseFence =Fence::merge("LayerRelease", releaseFence, frame.clientTargetAcquireFence);
        }
        //同步Fence到Layer中
        //如果是BufferQueueLayer设置mSlots[slot].mFence; 
        //如果是BufferStateLayer把fence关联到回调类中
        layer->getLayerFE().onLayerDisplayed(ftl::yield<FenceResult>(std::move(releaseFence)).share());
    }
    //mReleasedLayers是一些即将移除的的layer，但是当前vsync还在生产帧数据的layer
    //把presentFence传给这些layer使用，毕竟他们不参与合成了
    for (auto& weakLayer : mReleasedLayers) {
        if (const auto layer = weakLayer.promote()) {
            layer->onLayerDisplayed(ftl::yield<FenceResult>(frame.presentFence).share());
        }
    }
    //清空mReleasedLayers
    mReleasedLayers.clear();
}
//Display.cpp
compositionengine::Output::FrameFences Display::presentAndGetFrameFences() {
    //这里赋值FrameFences.clientTargetAcquireFence
    auto fences = impl::Output::presentAndGetFrameFences();
    endDraw();
    auto& hwc = getCompositionEngine().getHwComposer();
    //在屏幕上呈现当前显示内容（如果是虚拟显示，则显示到输出缓冲区中）
    //获取device上所有layer的 ReleaseFence
    hwc.presentAndGetReleaseFences(*halDisplayIdOpt, getState().earliestPresentTime,
                                   getState().previousPresentFence);
    //getPresentFence返回 HWComposer.mDisplayData.at(displayId).lastPresentFence;
    //这个fence，在当前帧在屏幕上显现，或者发送到面板内存时，会发送信号
    //这里赋值FrameFences.presentFence
    fences.presentFence = hwc.getPresentFence(*halDisplayIdOpt);
    for (const auto* layer : getOutputLayersOrderedByZ()) {
        auto hwcLayer = layer->getHwcLayer();
        if (!hwcLayer) {
            continue;
        }
        //把HWC显示设备上所有Layer的fence赋值给FrameFences.layerFences
        fences.layerFences.emplace(hwcLayer, hwc.getLayerReleaseFence(*halDisplayIdOpt, hwcLayer));
    }
    hwc.clearReleaseFences(*halDisplayIdOpt);
    return fences;
}
//HWComposer.cpp
status_t HWComposer::presentAndGetReleaseFences(
    HalDisplayId displayId, std::chrono::steady_clock::time_point earliestPresentTime,
    const std::shared_ptr<FenceTime>& previousPresentFence) {
    //...
    //执行present，返回present fence
    //这里真正把GPU合成的buffer和fence传给HWC
    auto error = hwcDisplay->present(&displayData.lastPresentFence);
    std::unordered_map<HWC2::Layer*, sp<Fence>> releaseFences;
    //从hwc里面获得release fence
    error = hwcDisplay->getReleaseFences(&releaseFences);
    displayData.releaseFences = std::move(releaseFences);
    return NO_ERROR;
}
```

postFramebuffer调用hwc.presentAndGetReleaseFences来让屏幕显示当前内容，然后构建了FrameFences。到这里合成工作就完成了，即注释73处mCompositionEngine->present已完成。再继续看注释74合成后的收尾工作postComposition

```cpp
//SurfaceFlinger.cpp
void SurfaceFlinger::postComposition() {
    //...
    for (const auto& layer: mLayersWithQueuedFrames) {
        //更新FrameTracker、TimeStats这些
        layer->onPostComposition(display, glCompositionDoneFenceTime,
                                 mPreviousPresentFences[0].fenceTime, compositorTiming);
        layer->releasePendingBuffer(/*dequeueReadyTime*/ now);
    }
    //...
    //通过binder通信，回调到APP端BBQ执行releaseBuffer了
    mTransactionCallbackInvoker.sendCallbacks(false /* onCommitOnly */);
    mTransactionCallbackInvoker.clearCompletedTransactions();
}

//BufferStateLayer.cpp
void BufferStateLayer::releasePendingBuffer(nsecs_t dequeueReadyTime) {
    //...
    for (auto& handle : mDrawingState.callbackHandles) {
        if (handle->releasePreviousBuffer &&
            mDrawingState.releaseBufferEndpoint == handle->listener) {
            //这个mPreviousReleaseCallbackId 在 BufferStateLayer::updateActiveBuffer() 赋值
            //mPreviousReleaseCallbackId = {getCurrentBufferId(), mBufferInfo.mFrameNumber};
            handle->previousReleaseCallbackId = mPreviousReleaseCallbackId;
            break;
        }
    }
    //...
    //把callback 加入 TransactionCallbackInvoker::mCompletedTransactions
    mFlinger->getTransactionCallbackInvoker().addCallbackHandles(mDrawingState.callbackHandles, jankData);
    //...
}
//TransactionCallbackInvoker.cpp
void TransactionCallbackInvoker::sendCallbacks(bool onCommitOnly) {
    //...
    //回调binder的onTransactionCompleted方法
    callbacks.emplace_back([stats = std::move(listenerStats)]() {
        interface_cast<ITransactionCompletedListener>(stats.listener)
            ->onTransactionCompleted(stats);
    });
    //...
}
```

postComposition通过binder调用到客户端的onTransactionCompleted方法。此回调在客户端调用SF进程的acquireNextBufferLocked方法里定义

```c++
void BLASTBufferQueue::acquireNextBufferLocked(
    const std::optional<SurfaceComposerClient::Transaction*> transaction) {
    //...
    auto releaseBufferCallback =
        std::bind(releaseBufferCallbackThunk, wp<BLASTBufferQueue>(this) /* callbackContext */,
                  std::placeholders::_1, std::placeholders::_2, std::placeholders::_3);
    sp<Fence> fence = bufferItem.mFence ? new Fence(bufferItem.mFence->dup()) : Fence::NO_FENCE;
    t->setBuffer(mSurfaceControl, buffer, fence, bufferItem.mFrameNumber, releaseBufferCallback);
    //...
}
```

其实会回调releaseBufferCallbackThunk方法，此时已经回调到客户端进程。

```cpp
//BLASTBufferQueue.cpp
static void releaseBufferCallbackThunk(wp<BLASTBufferQueue> context, const ReleaseCallbackId& id,
                                       const sp<Fence>& releaseFence,
                                       std::optional<uint32_t> currentMaxAcquiredBufferCount) {
    sp<BLASTBufferQueue> blastBufferQueue = context.promote();
    if (blastBufferQueue) {
        blastBufferQueue->releaseBufferCallback(id, releaseFence, currentMaxAcquiredBufferCount);
    } 
}
void BLASTBufferQueue::releaseBufferCallback(
    const ReleaseCallbackId& id, const sp<Fence>& releaseFence,
    std::optional<uint32_t> currentMaxAcquiredBufferCount) {
    releaseBufferCallbackLocked(id, releaseFence, currentMaxAcquiredBufferCount);
}
void BLASTBufferQueue::releaseBufferCallbackLocked(const ReleaseCallbackId& id,
                                                   const sp<Fence>& releaseFence, std::optional<uint32_t> currentMaxAcquiredBufferCount) {

    //...
    //ReleaseCallbackId 是个包含 bufferId 和 帧号 的Parcelable, ReleasedBuffer结构体又把 releaseFence 包含进来
    auto rb = ReleasedBuffer{id, releaseFence};
    //队列里边没有的话，就加入队列
    if (std::find(mPendingRelease.begin(), mPendingRelease.end(), rb) == mPendingRelease.end()) {
        mPendingRelease.emplace_back(rb);
    }
    //numPendingBuffersToHold是需要保留的buffer数量
    while (mPendingRelease.size() > numPendingBuffersToHold) {
        const auto releasedBuffer = mPendingRelease.front();
        mPendingRelease.pop_front();
        //释放buffer
        releaseBuffer(releasedBuffer.callbackId, releasedBuffer.releaseFence);
        if (mSyncedFrameNumbers.empty()) {
            acquireNextBufferLocked(std::nullopt);
        }
    }
    //...
}

void BLASTBufferQueue::releaseBuffer(const ReleaseCallbackId& callbackId,
                                     const sp<Fence>& releaseFence) {
    //mSubmitted是个map<ReleaseCallbackId, BufferItem>，从这个map中查找对应的 BufferItem
    auto it = mSubmitted.find(callbackId);
    mNumAcquired--;
    mNumUndequeued++;
    mBufferItemConsumer->releaseBuffer(it->second, releaseFence);
    mSubmitted.erase(it);
    mSyncedFrameNumbers.erase(callbackId.framenumber);
}
//BufferItemConsumer.cpp
status_t BufferItemConsumer::releaseBuffer(const BufferItem &item,
                                           const sp<Fence>& releaseFence) {
    status_t err;
    //mSlots[slot].mFence = fence; 赋值releaseFence，并处理Fence合并情况
    err = addReleaseFenceLocked(item.mSlot, item.mGraphicBuffer, releaseFence);
    //调用消费者的releaseBuffer方法
    err = releaseBufferLocked(item.mSlot, item.mGraphicBuffer, EGL_NO_DISPLAY,
                              EGL_NO_SYNC_KHR);
    return err;
}
//调用父类ConsumerBase.cpp的releaseBufferLocked
status_t ConsumerBase::releaseBufferLocked(
        int slot, const sp<GraphicBuffer> graphicBuffer,
        EGLDisplay display, EGLSyncKHR eglFence) {
    //...
    //调用消费者的releaseBuffer方法
    status_t err = mConsumer->releaseBuffer(slot, mSlots[slot].mFrameNumber,
                                            display, eglFence, mSlots[slot].mFence);
    //如果返回buffer过时了，需要清空这个slot的 GraphicBuffer
    if (err == IGraphicBufferConsumer::STALE_BUFFER_SLOT) {
        freeBufferLocked(slot);
    }

    mPrevFinalReleaseFence = mSlots[slot].mFence;
    // 这里的mSlots是BufferItem的，和 BufferQueue 的不是一个变量
    // BufferItem 的Fence设置为 NO_FENCE
    mSlots[slot].mFence = Fence::NO_FENCE;
}
//BufferQueueConsumer.cpp
status_t BufferQueueConsumer::releaseBuffer(int slot, uint64_t frameNumber,
                                            const sp<Fence>& releaseFence, EGLDisplay eglDisplay,
                                            EGLSyncKHR eglFence) {
    sp<IProducerListener> listener;
    //display = EGL_NO_DISPLAY;
    mSlots[slot].mEglDisplay = eglDisplay;
    //EGLSyncKHR eglFence = EGL_NO_SYNC_KHR
    mSlots[slot].mEglFence = eglFence;
    //SurfaceFlinger 传递过来的 releaseFence
    mSlots[slot].mFence = releaseFence;
    //状态转为 released
    mSlots[slot].mBufferState.release();
    //从激活状态set中删除
    mCore->mActiveBuffers.erase(slot);
    //放回 Free list 中
    mCore->mFreeBuffers.push_back(slot);
    if (mCore->mBufferReleasedCbEnabled) {
        // mConnectedProducerListener 是 BufferQueueProducer::connect 时传入的
        // CPU绘制传入的 StubProducerListener,没啥用
        listener = mCore->mConnectedProducerListener;
    }
    //如果有阻塞在dequeuebuffer的线程，此时会被唤醒，有新的buffer可以 Dequeue 了
    mCore->mDequeueCondition.notify_all();
    if (listener != nullptr) {
        //对于CPU绘制，这个是空函数没啥
        listener->onBufferReleased();
    }
}
```

其实就是调用了消费者的releaseBuffer，如果有阻塞在dequeuebuffer的线程，此时会被唤醒。  

小结：SurfaceFlinger的合成会先构建合成需要的参数refreshArgs，把SF里保存的layer等信息传进去，再调用合成引擎的present方法。此方法会遍历所有的显示设备output，调用其prepare和present方法，其中prepare方法会从顶层到底层遍历所有的Layer，把不需要参与合成的layer放到mReleasedLayers中。present方法会调用prepareFrame通过HWC服务去确认是用客户端合成还是硬件合成，如果由硬件合成，合成流程直接结束，否则会在finishFrame里处理客户端合成，即GPU合成，这里会调用composeSurfaces去真正处理GPU合成，然后会postFramebuffer告诉HWC做最后的合成并显示，这里会调用hwc的presentAndGetReleaseFences先展示已经要显示的帧（FrameFences.presentFence），然后把当前的帧赋值到此字段，等待下一个vsync信号后再展示。合成完后会在postComposition通过binder调用到客户端的onTransactionCompleted方法，这里会调用BBQ的releaseBuffer方法，其会调用消费者的releaseBuffer，释放完后把slot放到空闲队列，如果有线程阻塞在dequeuebuffer的，此时会被唤醒。

##### composeSurfaces

继续上面注释84处composeSurfaces，看下GPU具体是怎么合成的

```cpp
std::optional<base::unique_fd> Output::composeSurfaces(
    const Region& debugRegion, const compositionengine::CompositionRefreshArgs& refreshArgs,
    std::shared_ptr<renderengine::ExternalTexture> tex, base::unique_fd& fd) {

    //设置clientCompositionDisplay，这个是display相关参数
    renderengine::DisplaySettings clientCompositionDisplay;
    clientCompositionDisplay.physicalDisplay = outputState.framebufferSpace.getContent();
    clientCompositionDisplay.clip = outputState.layerStackSpace.getContent();
    clientCompositionDisplay.orientation =
        ui::Transform::toRotationFlags(outputState.displaySpace.getOrientation());
    //...省略设置clientCompositionDisplay其他属性

    std::vector<LayerFE*> clientCompositionLayersFE;
    //设置clientCompositionLayers，这个是layer的相关参数
    std::vector<LayerFE::LayerSettings> clientCompositionLayers =
        generateClientCompositionRequests(supportsProtectedContent,
                                          clientCompositionDisplay.outputDataspace,
                                          clientCompositionLayersFE);
    
    OutputCompositionState& outputCompositionState = editState();
    //如果cache里有相同的Buffer，则不需要重复draw一次
    if (mClientCompositionRequestCache && mLayerRequestingBackgroundBlur != nullptr) {
        if (mClientCompositionRequestCache->exists(tex->getBuffer()->getId(),
                                                   clientCompositionDisplay,
                                                   clientCompositionLayers)) {
            outputCompositionState.reusedClientComposition = true;
            setExpensiveRenderingExpected(false);
            return base::unique_fd(std::move(fd));
        }
        mClientCompositionRequestCache->add(tex->getBuffer()->getId(), clientCompositionDisplay,
                                            clientCompositionLayers);
    }
    
    //针对有模糊layer和有复杂颜色空间转换的场景，给GPU进行提频
    const bool expensiveBlurs =
        refreshArgs.blursAreExpensive && mLayerRequestingBackgroundBlur != nullptr;
    const bool expensiveRenderingExpected = expensiveBlurs ||
        std::any_of(clientCompositionLayers.begin(), clientCompositionLayers.end(),
                    [outputDataspace =
                     clientCompositionDisplay.outputDataspace](const auto& layer) {
                        return layer.sourceDataspace != outputDataspace;
                    });
    if (expensiveRenderingExpected) {
        setExpensiveRenderingExpected(true);
    }
    
    //将clientCompositionLayers里面的内容插入到clientCompositionLayerPointers，实质内容相同
    std::vector<renderengine::LayerSettings> clientRenderEngineLayers;
    clientRenderEngineLayers.reserve(clientCompositionLayers.size());
    std::transform(clientCompositionLayers.begin(), clientCompositionLayers.end(),
                   std::back_inserter(clientRenderEngineLayers),
                   [](LayerFE::LayerSettings& settings) -> renderengine::LayerSettings {
                       return settings;
                   });
    //85 GPU合成，主要逻辑在drawLayers里面
    auto fenceResult =
        toFenceResult(renderEngine.drawLayers(clientCompositionDisplay, clientRenderEngineLayers,tex, 
                                              useFramebufferCache, std::move(fd)) .get());
    //...
}


std::future<RenderEngineResult> RenderEngine::drawLayers(
        const DisplaySettings& display, const std::vector<LayerSettings>& layers,
        const std::shared_ptr<ExternalTexture>& buffer, const bool useFramebufferCache,
        base::unique_fd&& bufferFence) {
    const auto resultPromise = std::make_shared<std::promise<RenderEngineResult>>();
    std::future<RenderEngineResult> resultFuture = resultPromise->get_future();
    //调用子类的drawLayersInternal
    drawLayersInternal(std::move(resultPromise), display, layers, buffer, useFramebufferCache,
                       std::move(bufferFence));
    return resultFuture;
}

```

注释85处通过渲染引擎的drawLayers完成GPU合成

> frameworks/native/libs/renderengine/gl/GLESRenderEngine.cpp

```cpp
void GLESRenderEngine::drawLayersInternal(
        const std::shared_ptr<std::promise<RenderEngineResult>>&& resultPromise,
        const DisplaySettings& display, const std::vector<LayerSettings>& layers,
        const std::shared_ptr<ExternalTexture>& buffer, const bool useFramebufferCache,
        base::unique_fd&& bufferFence) {
    //要等前一帧的release fence
    if (bufferFence.get() >= 0) {
        base::unique_fd bufferFenceDup(dup(bufferFence.get()));
        if (bufferFenceDup < 0 || !waitFence(std::move(bufferFenceDup))) {
            sync_wait(bufferFence.get(), -1);
        }
    }
    if (buffer == nullptr) {
        return BAD_VALUE;
    }
    std::unique_ptr<BindNativeBufferAsFramebuffer> fbo;
    std::deque<const LayerSettings*> blurLayers;
    if (CC_LIKELY(mBlurFilter != nullptr)) {
        for (auto layer : layers) {
            if (layer->backgroundBlurRadius > 0) {
                blurLayers.push_back(layer);
            }
        }
    }
    const auto blurLayersSize = blurLayers.size();

    if (blurLayersSize == 0) {
        //将dequeue出来的buffer绑定到Framebuffer上面，作为fbo
        //这里会走BindNativeBufferAsFramebuffer的构造函数
        fbo = std::make_unique<BindNativeBufferAsFramebuffer>(*this,buffer.get()->getNativeBuffer(), useFramebufferCache);
        //设置视图和投影矩阵
        setViewportAndProjection(display.physicalDisplay, display.clip);
		//...
    }
	//...
    //88 真正GPU合成流程后面继续看 
}
std::unique_ptr<Framebuffer> GLESRenderEngine::createFramebuffer() {
    //GLFramebuffer创建时绑定了GLESRenderEngine
    return std::make_unique<GLFramebuffer>(*this);
}
//RenderEngine.h
class BindNativeBufferAsFramebuffer {
    public:
    BindNativeBufferAsFramebuffer(RenderEngine& engine, ANativeWindowBuffer* buffer,
                                  const bool useFramebufferCache)
        : mEngine(engine), mFramebuffer(mEngine.getFramebufferForDrawing()), mStatus(NO_ERROR) {
            //86 mFramebuffer是在GLESRenderEngine的构造函数中createFramebuffer时创建的GLFramebuffer
            //这里先执行setNativeWindowBuffer，如果返回true，执行 mEngine.bindFrameBuffer
            mStatus = mFramebuffer->setNativeWindowBuffer(buffer, mEngine.isProtected(),useFramebufferCache)
                ? mEngine.bindFrameBuffer(mFramebuffer)
                : NO_MEMORY;
        }
}
//GLFramebuffer.cpp
bool GLFramebuffer::setNativeWindowBuffer(ANativeWindowBuffer* nativeBuffer, bool isProtected,
                                          const bool useFramebufferCache) {
    if (mEGLImage != EGL_NO_IMAGE_KHR) {
        if (!usingFramebufferCache) {
            eglDestroyImageKHR(mEGLDisplay, mEGLImage);
            DEBUG_EGL_IMAGE_TRACKER_DESTROY();
        }
        mEGLImage = EGL_NO_IMAGE_KHR;
        mBufferWidth = 0;
        mBufferHeight = 0;
    }

    if (nativeBuffer) {
        //87 调用绑定的GLESRenderEngine.createFramebufferImageIfNeeded
        mEGLImage = mEngine.createFramebufferImageIfNeeded(nativeBuffer, isProtected,useFramebufferCache);
        if (mEGLImage == EGL_NO_IMAGE_KHR) {
            return false;
        }
        usingFramebufferCache = useFramebufferCache;
        mBufferWidth = nativeBuffer->width;
        mBufferHeight = nativeBuffer->height;
    }
    return true;
}
```

注释86处先执行GLFramebuffer.setNativeWindowBuffer，如果返回true，执行 mEngine.bindFrameBuffer

先看注释87处调了GLESRenderEngine.createFramebufferImageIfNeeded

```c++
EGLImageKHR GLESRenderEngine::createFramebufferImageIfNeeded(ANativeWindowBuffer* nativeBuffer,
                                                             bool isProtected,
                                                             bool useFramebufferCache) {
    //将ANativeWindowBuffer转换成GraphicsBuffer
    sp<GraphicBuffer> graphicBuffer = GraphicBuffer::from(nativeBuffer);
    //使用cache，如果有一样的image，就直接返回
    if (useFramebufferCache) {
        std::lock_guard<std::mutex> lock(mFramebufferImageCacheMutex);
        for (const auto& image : mFramebufferImageCache) {
            if (image.first == graphicBuffer->getId()) {
                return image.second;
            }
        }
    }
    EGLint attributes[] = {
            isProtected ? EGL_PROTECTED_CONTENT_EXT : EGL_NONE,
            isProtected ? EGL_TRUE : EGL_NONE,
            EGL_NONE,
    };
    //将dequeue出来的buffer作为参数创建 EGLImage
    EGLImageKHR image = eglCreateImageKHR(mEGLDisplay, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID,
                                          nativeBuffer, attributes);
    if (useFramebufferCache) {
        if (image != EGL_NO_IMAGE_KHR) {
            std::lock_guard<std::mutex> lock(mFramebufferImageCacheMutex);
            if (mFramebufferImageCache.size() >= mFramebufferImageCacheSize) {
                EGLImageKHR expired = mFramebufferImageCache.front().second;
                mFramebufferImageCache.pop_front();
                eglDestroyImageKHR(mEGLDisplay, expired);
                DEBUG_EGL_IMAGE_TRACKER_DESTROY();
            }
            //把image放到mFramebufferImageCache里面
            mFramebufferImageCache.push_back({graphicBuffer->getId(), image});
        }
    }
    return image;
}
```

再看GLESRenderEngine.bindFrameBuffer

```c++
status_t GLESRenderEngine::bindFrameBuffer(Framebuffer* framebuffer) {
    GLFramebuffer* glFramebuffer = static_cast<GLFramebuffer*>(framebuffer);
    //上一步创建的EGLImage
    EGLImageKHR eglImage = glFramebuffer->getEGLImage();
    //创建RenderEngine时就已经创建好的texture id和fb id
    uint32_t textureName = glFramebuffer->getTextureName();
    uint32_t framebufferName = glFramebuffer->getFramebufferName();
    //绑定texture，后面的操作将作用在这上面
    glBindTexture(GL_TEXTURE_2D, textureName);
    //根据EGLImage 创建一个2D texture
    glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, (GLeglImageOES)eglImage);
    //Bind the Framebuffer to render into
    glBindFramebuffer(GL_FRAMEBUFFER, framebufferName);
    //将纹理附着在帧缓存上面，渲染到farmeBuffer
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureName, 0);
    uint32_t glStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    return glStatus == GL_FRAMEBUFFER_COMPLETE_OES ? NO_ERROR : BAD_VALUE;
}
```

首先将dequeue出来的buffer通过eglCreateImageKHR做成image，然后通过glEGLImageTargetTexture2DOES根据image创建一个2D的纹理，再通过glFramebufferTexture2D把纹理附着在帧缓存上面。 再继续看注释88处GLESRenderEngine::drawLayersInternal后面的流程

```c++
void GLESRenderEngine::drawLayersInternal(...){
    std::unique_ptr<BindNativeBufferAsFramebuffer> fbo;
    //...
    //88 此时已经准备好fbo 
    //设置顶点和纹理坐标的size
    Mesh mesh = Mesh::Builder()
        .setPrimitive(Mesh::TRIANGLE_FAN)
        .setVertices(4 /* count */, 2 /* size */)
        .setTexCoords(2 /* size */)
        .setCropCoords(2 /* size */)
        .build();

    for (const auto& layer : layers) {
        //先校验
        if (blurLayers.size() > 0 && blurLayers.front() == layer) {
            blurLayers.pop_front();
               auto status = mBlurFilter->prepare();
            if (status != NO_ERROR) {
                 return;
            }
            //...省略其他校验条件
        }
        
        // Ensure luminance is at least 100 nits to avoid div-by-zero
        //避免计算的时候被除数是0，设置最少为100
        const float maxLuminance = std::max(100.f, layer.source.buffer.maxLuminanceNits);
        mState.maxMasteringLuminance = maxLuminance;
        mState.maxContentLuminance = maxLuminance;
        mState.projectionMatrix = projectionMatrix * layer.geometry.positionTransform;
        
        //获取layer的大小
        const FloatRect bounds = layer.geometry.boundaries;
        Mesh::VertexArray<vec2> position(mesh.getPositionArray<vec2>());
        //设置顶点的坐标，逆时针方向
        position[0] = vec2(bounds.left, bounds.top);
        position[1] = vec2(bounds.left, bounds.bottom);
        position[2] = vec2(bounds.right, bounds.bottom);
        position[3] = vec2(bounds.right, bounds.top);
        //设置crop的坐标
        setupLayerCropping(layer, mesh);
        //设置颜色矩阵
        setColorTransform(layer.colorTransform);
        
        bool usePremultipliedAlpha = true;
        bool disableTexture = true;
        bool isOpaque = false;
        //Buffer相关设置
        if (layer.source.buffer.buffer != nullptr) {
            disableTexture = false;
            isOpaque = layer.source.buffer.isOpaque;

            //layer的buffer，即输入的buffer
            sp<GraphicBuffer> gBuf = layer.source.buffer.buffer->getBuffer();
            validateInputBufferUsage(gBuf);
            //textureName是创建BufferQueuelayer时生成的，用来标识这个layer，
            //fence是acquire fence
            bindExternalTextureBuffer(layer.source.buffer.textureName, gBuf,
                                      layer.source.buffer.fence);

            usePremultipliedAlpha = layer.source.buffer.usePremultipliedAlpha;
            Texture texture(Texture::TEXTURE_EXTERNAL, layer.source.buffer.textureName);
            mat4 texMatrix = layer.source.buffer.textureTransform;

            texture.setMatrix(texMatrix.asArray());
            texture.setFiltering(layer.source.buffer.useTextureFiltering);

            texture.setDimensions(gBuf->getWidth(), gBuf->getHeight());
            setSourceY410BT2020(layer.source.buffer.isY410BT2020);

            renderengine::Mesh::VertexArray<vec2> texCoords(mesh.getTexCoordArray<vec2>());
            //设置纹理坐标，也是逆时针
            texCoords[0] = vec2(0.0, 0.0);
            texCoords[1] = vec2(0.0, 1.0);
            texCoords[2] = vec2(1.0, 1.0);
            texCoords[3] = vec2(1.0, 0.0);
            //设置纹理的参数
            setupLayerTexturing(texture);
        }
        //有阴影
        if (layer.shadow.length > 0.0f) {
            handleShadow(layer.geometry.boundaries, layer.geometry.roundedCornersRadius,layer.shadow);
        } else if (layer.geometry.roundedCornersRadius > 0.0 && color.a >= 1.0f && isOpaque) {
            //有圆角
            handleRoundedCorners(display, layer, mesh);
        } else {
            //89 正常绘制
            drawMesh(mesh);
        }
    }
    
    //90 再调用flush，获取到Fence
    base::unique_fd drawFence = flush();
    mLastDrawFence = new Fence(dup(drawFence.get()));
    mPriorResourcesCleaned = false;

    resultPromise->set_value({NO_ERROR, std::move(drawFence)});
    return;
}
void GLESRenderEngine::bindExternalTextureBuffer(uint32_t texName, const sp<GraphicBuffer>& buffer,const sp<Fence>& bufferFence) {
	//先从缓存中找是否有相同的buffer
    bool found = false;
    {
        std::lock_guard<std::mutex> lock(mRenderingMutex);
        auto cachedImage = mImageCache.find(buffer->getId());
        found = (cachedImage != mImageCache.end());
    }
    if (!found) {
        //如果ImageCache里面没有则需要重新创建一个EGLImage，创建输入的EGLImage是在ImageManager线程里面，利用notify唤醒机制
        status_t cacheResult = mImageManager->cache(buffer);
        if (cacheResult != NO_ERROR) {
            return;
        }
    }
    //把EGLImage转换成纹理，类型为GL_TEXTURE_EXTERNAL_OES
    bindExternalTextureImage(texName, *cachedImage->second);
    //...
}

void GLESRenderEngine::bindExternalTextureImage(uint32_t texName, const Image& image) {
    const GLImage& glImage = static_cast<const GLImage&>(image);
    const GLenum target = GL_TEXTURE_EXTERNAL_OES;
    //绑定纹理，纹理ID为texName
    glBindTexture(target, texName);
    if (glImage.getEGLImage() != EGL_NO_IMAGE_KHR) {
        // 把EGLImage转换成纹理，纹理ID为texName
        glEGLImageTargetTexture2DOES(target, static_cast<GLeglImageOES>(glImage.getEGLImage()));
    }
}
```

先将输入和输出的Buffer都生成了纹理，以及设置了纹理的坐标和顶点的坐标，先看注释89 drawMesh绘制，再看注释90处flush

```c++
void GLESRenderEngine::drawMesh(const Mesh& mesh) {
    if (mesh.getTexCoordsSize()) {
        //开启顶点着色器属性，目的是能在顶点着色器中访问顶点的属性数据
        glEnableVertexAttribArray(Program::texCoords);
        //给顶点着色器传纹理的坐标
        glVertexAttribPointer(Program::texCoords, mesh.getTexCoordsSize(), GL_FLOAT, GL_FALSE,
                              mesh.getByteStride(), mesh.getTexCoords());
    }
    //给顶点着色器传顶点的坐标
    glVertexAttribPointer(Program::position, mesh.getVertexSize(), GL_FLOAT, GL_FALSE,mesh.getByteStride(), mesh.getPositions());
    //...省略圆角和阴影的处理
    
    //创建顶点和片段着色器，将顶点属性设和一些常量参数设到shader里面
    ProgramCache::getInstance().useProgram(mInProtectedContext ? mProtectedEGLContext : mEGLContext,managedState);
    //91 调GPU去draw
    //opengl提供的API接口，用来绘制图元。
    //第一个参数代表绘制的opengl图元类型，比如三角形传GL_TRIANGLES，第二个参数代表顶点数组的起始索引，第三个参数代表打算绘制多少个顶点
    glDrawArrays(mesh.getPrimitive(), 0, mesh.getVertexCount());
}
//frameworks/native/libs/renderengine/gl/ProgramCache.cpp
void ProgramCache::useProgram(EGLContext context, const Description& description) {
    //generate the key for the shader based on the description
    //设置key值，根据不同的key值创建不同的shader
    Key needs(computeKey(description));

    //look-up the program in the cache
    auto& cache = mCaches[context];
    //如果cache里面没有相同的program则重新创建一个
    auto it = cache.find(needs);
    if (it == cache.end()) {
        // we didn't find our program, so generate one...
        nsecs_t time = systemTime();
        //生成可执行程序
        it = cache.emplace(needs, generateProgram(needs)).first;
        time = systemTime() - time;
    }

    // here we have a suitable program for this description
    std::unique_ptr<Program>& program = it->second;
    if (program->isValid()) {
        //执行可执行程序
        program->use();
        program->setUniforms(description);
    }
}
std::unique_ptr<Program> ProgramCache::generateProgram(const Key& needs) {
    //创建顶点着色器，其实就是拼接了个可执行程序的字符串
    String8 vs = generateVertexShader(needs);
    //创建片段着色器，同上，拼接了个可执行程序的字符串
    String8 fs = generateFragmentShader(needs);
    //链接和编译着色器，把上面生成的两个字符串传到Program的构造函数中
    return std::make_unique<Program>(needs, vs.string(), fs.string());
}
//这里拼接了个可执行程序
String8 ProgramCache::generateFragmentShader(const Key& needs) {
    Formatter fs;
    if (needs.getTextureTarget() == Key::TEXTURE_EXT) {
        fs << "#extension GL_OES_EGL_image_external : require";
    }
    fs << "precision mediump float;";
    if (needs.getTextureTarget() == Key::TEXTURE_EXT) {
        fs << "uniform samplerExternalOES sampler;";
    } else if (needs.getTextureTarget() == Key::TEXTURE_2D) {
        fs << "uniform sampler2D sampler;";
    }
    if (needs.hasTextureCoords()) {
        fs << "varying vec2 outTexCoords;";
    }
    //...省略拼接其他内容
    // 拼接void main(void){}，相当于生成个可执行程序
    fs << "void main(void) {" << indent;
    if (needs.drawShadows()) {
        fs << "gl_FragColor = getShadowColor();";
    } else {
        if (needs.isTexturing()) {
            fs << "gl_FragColor = texture2D(sampler, outTexCoords);";
            if (needs.isY410BT2020()) {
                fs << "gl_FragColor.rgb = convertY410BT2020(gl_FragColor.rgb);";
            }
        } else {
            fs << "gl_FragColor.rgb = color.rgb;";
            fs << "gl_FragColor.a = 1.0;";
        }
        if (needs.isOpaque()) {
            fs << "gl_FragColor.a = 1.0;";
        }
    }
    //...
    if (needs.hasRoundedCorners()) {
        if (needs.isPremultiplied()) {
            fs << "gl_FragColor *= vec4(applyCornerRadius(outCropCoords));";
        } else {
            fs << "gl_FragColor.a *= applyCornerRadius(outCropCoords);";
        }
    }

    fs << dedent << "}";
    return fs.getString();
}

Program::Program(const ProgramCache::Key& /*needs*/, const char* vertex, const char* fragment): mInitialized(false) {
    //编译顶点和片段着色器
    GLuint vertexId = buildShader(vertex, GL_VERTEX_SHADER);
    GLuint fragmentId = buildShader(fragment, GL_FRAGMENT_SHADER);
    //创建programID
    GLuint programId = glCreateProgram();
    //将顶点和片段着色器链接到programe
    glAttachShader(programId, vertexId);
    glAttachShader(programId, fragmentId);
    //将着色器里面的属性和自定义的属性变量绑定
    glBindAttribLocation(programId, position, "position");
    glBindAttribLocation(programId, texCoords, "texCoords");
    glBindAttribLocation(programId, cropCoords, "cropCoords");
    glBindAttribLocation(programId, shadowColor, "shadowColor");
    glBindAttribLocation(programId, shadowParams, "shadowParams");
    glLinkProgram(programId);

    GLint status;
    glGetProgramiv(programId, GL_LINK_STATUS, &status);
    if (status != GL_TRUE) {
        //...
    }else{
        mProgram = programId;
        mVertexShader = vertexId;
        mFragmentShader = fragmentId;
        mInitialized = true;
        //获得着色器里面uniform变量的位置
        mProjectionMatrixLoc = glGetUniformLocation(programId, "projection");
        mTextureMatrixLoc = glGetUniformLocation(programId, "texture");
        mSamplerLoc = glGetUniformLocation(programId, "sampler");
        mColorLoc = glGetUniformLocation(programId, "color");
        //...
        
        // set-up the default values for our uniforms
        glUseProgram(programId);
        glUniformMatrix4fv(mProjectionMatrixLoc, 1, GL_FALSE, mat4().asArray());
        glEnableVertexAttribArray(0);
    }
}

void Program::use() {
    //使Program生效，安装一个程序对象作为当前渲染状态的一部分
	//此为opengl的一个api函数：安装一个程序对象作为当前渲染状态的一部分
    glUseProgram(mProgram);
}
void Program::setUniforms(const Description& desc) {
    // 根据uniform的位置，给uniform变量设置，设到shader里面
    if (mSamplerLoc >= 0) {
        glUniform1i(mSamplerLoc, 0);
        glUniformMatrix4fv(mTextureMatrixLoc, 1, GL_FALSE, desc.texture.getMatrix().asArray());
    }
    if (mColorLoc >= 0) {
        const float color[4] = {desc.color.r, desc.color.g, desc.color.b, desc.color.a};
        glUniform4fv(mColorLoc, 1, color);
    }
    //...省略其他变量的设置
    // these uniforms are always present
    glUniformMatrix4fv(mProjectionMatrixLoc, 1, GL_FALSE, desc.projectionMatrix.asArray());
}
```

对于GPU来说，输入都是一幅幅纹理，然后在着色器里面控制最后pixel的位置坐标和颜色值，最后在注释91处调用opengl提供的glDrawArrays接口使用GPU来绘制图元。再继续看注释90处flush

```c++
base::unique_fd GLESRenderEngine::flush() {
    if (!GLExtensions::getInstance().hasNativeFenceSync()) {
        return base::unique_fd();
    }
    //创建一个EGLSync对象，用来标识GPU是否绘制完
    EGLSyncKHR sync = eglCreateSyncKHR(mEGLDisplay, EGL_SYNC_NATIVE_FENCE_ANDROID, nullptr);
    if (sync == EGL_NO_SYNC_KHR) {
        return base::unique_fd();
    }
    
    //opengl的api接口。用于强制刷新缓冲，保证绘图命令将被执行
    //92 将gl command命令全部刷给GPU
    glFlush();

    //获得android 使用的fence fd
    base::unique_fd fenceFd(eglDupNativeFenceFDANDROID(mEGLDisplay, sync));
    eglDestroySyncKHR(mEGLDisplay, sync);

    if (CC_UNLIKELY(mTraceGpuCompletion && mFlushTracer) && fenceFd.get() >= 0) {
        mFlushTracer->queueSync(eglCreateSyncKHR(mEGLDisplay, EGL_SYNC_FENCE_KHR, nullptr));
    }

    return fenceFd;
}
```

在注释92处执行opengl的接口glFlush将命令全部刷给GPU，这些命令会被gpu执行，GPU自己去draw。  

小结：composeSurfaces实现GPU合成可以理解成通过opengl把buffer数据转换成fence数据。先将dequeue出来的buffer通过eglCreateImageKHR做成image，然后通过glEGLImageTargetTexture2DOES根据image创建一个2D的纹理，再通过glFramebufferTexture2D把纹理附着在帧缓存上面。之后在drawMesh拼接并使用一个可执行的程序，然后执行opengl的glDrawArrays方法来让GPU绘制图元，最后在flush方法中调用opengl的glFlush方法来强制刷新缓存，保证绘图命令将被执行，之后就可以获取android层用到的fence来完成后面的把fence传给屏幕驱动完成显示。
