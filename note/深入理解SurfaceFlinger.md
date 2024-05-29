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

我们主要看注释1 `surfaceflinger::createSurfaceFlinger`这里的surfaceflinger其实指的是SurfaceFlingerFactory

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

