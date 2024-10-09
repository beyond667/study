#### 深入理解Watchdog实现原理分析 ####

#### 前言

##### 1.1 主要内容

本文主要关注内容：

1. Watchdog的作用
2. 深入分析Watchdog的实现原理
3. 具体案例

##### 1.2 Watchdog的作用

SystemServer进程是Android的核心进程，里面运行了很多核心服务，比如AMS,PMS,WMS，如果这些核心服务和重要的线程卡住，就会导致相应的功能无法正常使用，严重影响用户体验，所以谷歌引入Watchdog机制来监控这些核心服务和重要线程是否被卡住，一旦卡住，系统会重启SystemServer进程即重启。  

#### 深入理解Watchdog的实现原理

##### 2.1 初始化

开机启动流程中SystemServer.startBootstrapServices里首先就是启动Watchdog

```java
   private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
        t.traceBegin("startBootstrapServices");

        // Start the watchdog as early as possible so we can crash the system server
        // if we deadlock during early boot
        t.traceBegin("StartWatchdog");
        final Watchdog watchdog = Watchdog.getInstance();
        watchdog.start();
        t.traceEnd();
        //。。。
        }
```

这里以android12为例  

Watchdog.java

```java
public class Watchdog {
	private static Watchdog sWatchdog;
    private final Thread mThread;
    private final ArrayList<HandlerChecker> mHandlerCheckers = new ArrayList<>();
    private final HandlerChecker mMonitorChecker;
    public static Watchdog getInstance() {
        if (sWatchdog == null) {
            sWatchdog = new Watchdog();
        }

        return sWatchdog;
    }
    
    private Watchdog() {
        mThread = new Thread(this::run, "watchdog");
        mMonitorChecker = new HandlerChecker(FgThread.getHandler(),"foreground thread", DEFAULT_TIMEOUT);
        mHandlerCheckers.add(mMonitorChecker);
        mHandlerCheckers.add(new HandlerChecker(new Handler(Looper.getMainLooper()), "main thread", DEFAULT_TIMEOUT));
        mHandlerCheckers.add(new HandlerChecker(UiThread.getHandler(), "ui thread", DEFAULT_TIMEOUT));
        mHandlerCheckers.add(new HandlerChecker(IoThread.getHandler(),"i/o thread", DEFAULT_TIMEOUT));
        mHandlerCheckers.add(new HandlerChecker(DisplayThread.getHandler(), "display thread", DEFAULT_TIMEOUT));
        mHandlerCheckers.add(new HandlerChecker(AnimationThread.getHandler(),"animation thread", DEFAULT_TIMEOUT));
        mHandlerCheckers.add(new HandlerChecker(SurfaceAnimationThread.getHandler(),"surface animation thread", DEFAULT_TIMEOUT));
        addMonitor(new BinderThreadMonitor());
    }
    
    public void start() {
        mThread.start();
    }
    
    //run方法重点分析
    private void run() {}
}
```

初始化过程很简单，获取单例对象并调用start方法，启动异步线程执行run方法。  

稍微跑题下：构造方法里有个比较有意思的写法`mThread = new Thread(this::run, "watchdog");`，Thread的构造方法第一个参数是Runnable接口，但是传的是this::run，看着又像调用了内部的run方法，表面看应该编译错误的，实际上是java8的lamda写法，实际上等同于以下：

```java
//1=====实际运行代码
mThread = new Thread(new Runnable() {
    @Override
    public void run() {
        run(); //Watchdog.run方法
    }
}, "watchdog");
//2=====在Ruunbale接口是个函数式接口，因此可以用lambda表达式简化：
mThread = new Thread(()->run(), "watchdog");
//3======最终简化
mThread = new Thread(this::run, "watchdog");
```

Watchdog构造函数中把要监听的HandlerChecker对象存进mHandlerCheckers列表，HandlerChecker的构造函数传进不同线程的handler，用来监听不同线程的handler是否卡住。另外还构建了mMonitorChecker对象，用来监听核心服务是否卡住，mMonitorChecker运行在FgThread中。  

如下Watchdog提供了addMonitor和addThread来往mMonitorChecker（监听核心服务）和mHandlerCheckers（监听handler）里传被监听对象。

```java
public class Watchdog {
   public void addMonitor(Monitor monitor) {
        synchronized (mLock) {
            mMonitorChecker.addMonitorLocked(monitor);
        }
    }

    public void addThread(Handler thread) {
        addThread(thread, DEFAULT_TIMEOUT);
    }

    public void addThread(Handler thread, long timeoutMillis) {
        synchronized (mLock) {
            final String name = thread.getLooper().getThread().getName();
            mHandlerCheckers.add(new HandlerChecker(thread, name, timeoutMillis));
        }
    }
    public final class HandlerChecker implements Runnable {
        private final ArrayList<Monitor> mMonitorQueue = new ArrayList<Monitor>();
        //把Monitor添加到内部的mMonitorQueue列表中
        void addMonitorLocked(Monitor monitor) {
            mMonitorQueue.add(monitor);
        }
    }
}
```

以AMS和WMS为例：

```java
public class ActivityManagerService extends IActivityManager.Stub
        implements Watchdog.Monitor, BatteryStatsImpl.BatteryCallback, ActivityManagerGlobalLock {
    public ActivityManagerService(Context systemContext, ActivityTaskManagerService atm) {
        //...
        Watchdog.getInstance().addMonitor(this);
        Watchdog.getInstance().addThread(mHandler);
        //...
    }
    public void monitor() {
        synchronized (this) { }
    }
}
```

```java
public class WindowManagerService extends IWindowManager.Stub
        implements Watchdog.Monitor, WindowManagerPolicy.WindowManagerFuncs {
    public void onInitReady() {
        //...
        Watchdog.getInstance().addMonitor(this);
 		//...
    }    
    @Override
    public void monitor() {
        synchronized (mGlobalLock) { }
    }
    
}
```

可以看到AMS和WMS在其构造函数或者刚初始化后通过addMonitor和addThread加到Watchdog的监管，并实现Watchdog.Monitor接口的monitor方法，这方法只是对关键的监听对象加锁，并无其他操作。

##### 2.2 Watchdog.run方法

```java
private void run() {
    boolean waitedHalf = false;
    while (true) {
        List<HandlerChecker> blockedCheckers = Collections.emptyList();
        boolean allowRestart = true;
        synchronized (mLock) {
            long timeout = CHECK_INTERVAL; //30s
            //------------关键1 遍历所有的mHandlerCheckers，执行scheduleCheckLocked
            for (int i=0; i<mHandlerCheckers.size(); i++) {
                HandlerChecker hc = mHandlerCheckers.get(i);
                hc.scheduleCheckLocked();
            }

            //------------关键2 强制休眠30s
            long start = SystemClock.uptimeMillis();
            while (timeout > 0) {
                try {
                    mLock.wait(timeout);
                } catch (InterruptedException e) {}
                timeout = CHECK_INTERVAL - (SystemClock.uptimeMillis() - start);
            }

            //------------关键3 评估等待状态
            final int waitState = evaluateCheckerCompletionLocked();
            if (waitState == COMPLETED) {
                // The monitors have returned; reset
                waitedHalf = false;
                continue;
            } else if (waitState == WAITING) {
                // still waiting but within their configured intervals; back off and recheck
                continue;
            } else if (waitState == WAITED_HALF) {
                if (!waitedHalf) {
                    Slog.i(TAG, "WAITED_HALF");
                    waitedHalf = true;
                    pids = new ArrayList<>(mInterestingJavaPids);
                    doWaitedHalfDump = true;
                } else {
                    continue;
                }
            } else {
                // something is overdue!
                blockedCheckers = getBlockedCheckersLocked();
                subject = describeCheckersLocked(blockedCheckers);
                allowRestart = mAllowRestart;
                pids = new ArrayList<>(mInterestingJavaPids);
            }
        } // END synchronized (mLock)

        //------------关键4 dump日志，写入dropbox文件；如果是等待一半状态，先调ams的dumpStackTraces去dump下，continue退出此次循环
        if (doWaitedHalfDump) {
            ArrayList<Integer> nativePids = getInterestingNativePids();
            // We've waited half the deadlock-detection interval.  Pull a stack
            // trace and wait another half.
            initialStack = ActivityManagerService.dumpStackTraces(pids, null, null,
                                                                  nativePids, null, subject);
            if (initialStack != null){
                SmartTraceUtils.dumpStackTraces(Process.myPid(), pids,
                                                nativePids, initialStack);
            }
            continue;
        }

        final File finalStack = ActivityManagerService.dumpStackTraces(
            pids, processCpuTracker, new SparseArray<>(), nativePids,
            tracesFileException, subject);
        if (finalStack != null){
            SmartTraceUtils.dumpStackTraces(Process.myPid(), pids, nativePids, finalStack);
        }

        // Give some extra time to make sure the stack traces get written.
        // The system's been hanging for a minute, another second or two won't hurt much.
        // 都已经卡一分多钟了，不在乎多等几秒dump吧
        SystemClock.sleep(5000);

        File watchdogTraces;
        String newTracesPath = "traces_SystemServer_WDT"
            + mTraceDateFormat.format(new Date()) + "_pid"
            + String.valueOf(Process.myPid());
        File tracesDir = new File(ActivityManagerService.ANR_TRACE_DIR);
        watchdogTraces = new File(tracesDir, newTracesPath);

        if (watchdogTraces.createNewFile()) {
            appendFile(watchdogTraces, initialStack);
        } 
        
        Thread dropboxThread = new Thread("watchdogWriteToDropbox") {
            public void run() {
                if (mActivity != null) {
                    mActivity.addErrorToDropBox(
                        "watchdog", null, "system_server", null, null, null,
                        null, report.toString(), finalStack, null, null, null,
                        errorId);
                }
            }
        };
        dropboxThread.start();
        try {
            dropboxThread.join(2000);  // wait up to 2 seconds for it to return.
        } catch (InterruptedException ignored) {}

        //有时候也需要记录下kernel日志
        // At times, when user space watchdog traces don't give an indication on
        // which component held a lock, because of which other threads are blocked,
        // (thereby causing Watchdog), trigger kernel panic
        boolean crashOnWatchdog = SystemProperties
            .getBoolean("persist.sys.crashOnWatchdog", false);
        if (crashOnWatchdog) {
            doSysRq('w');
            doSysRq('l');
            SystemClock.sleep(3000);
            doSysRq('c');
        }

        IActivityController controller;
        synchronized (mLock) {
            controller = mController;
        }
        if (controller != null) {
            Slog.i(TAG, "Reporting stuck state to activity controller");
            try {
                Binder.setDumpDisabled("Service dumps disabled due to hung system process.");
                // 1 = keep waiting, -1 = kill system
                int res = controller.systemNotResponding(subject);
                if (res >= 0) {
                    Slog.i(TAG, "Activity controller requested to coninue to wait");
                    waitedHalf = false;
                    continue;
                }
            } catch (RemoteException e) {
            }
        }

        //------------关键5
         if (!allowRestart) {
                Slog.w(TAG, "Restart not allowed: Watchdog is *not* killing the system process");
         } else {
             Slog.w(TAG, "*** WATCHDOG KILLING SYSTEM PROCESS: " + subject);
             Slog.w(TAG, "*** GOODBYE!");
             Process.killProcess(Process.myPid());
             System.exit(10);
         }
    
   }
}
```

run方法可以简化成以下5个步骤：

1. 每隔30s遍历所有的mHandlerCheckers，执行scheduleCheckLocked记录每个HandlerChecker的开始时间
2. 强制休眠30s，假如第10s中断，计算出还需继续等20s，直到等够30s才继续。
3. 评估等待状态，一共有4种状态，complete（没卡，完成），WAITING（卡了30s内，继续等待），WAITED_HALF（超过30s不到1分钟，等了一半，需先dump些日志），OVERDUE（超过1分钟，卡死，可能重启）。需要注意的是这里的评估状态是所有被监听的对象的最差的情况，比如一共监听4个对象，一个已完成，一个WAITING，一个WAITED_HALF，一个OVERDUE，最终会返回OVERDUE。
4. dump日志，写入dropbox文件，如果是WAITED_HALF状态，先dump日志后就退出此次循环继续下个循环。
5. 杀掉当前进程，由于watchdog跟systemserver是一个进程，相当于杀掉systemserver系统重启。

下面具体看关键的第一步scheduleCheckLocked和第三步evaluateCheckerCompletionLocked

```java
public final class HandlerChecker implements Runnable {
    private final ArrayList<Monitor> mMonitors = new ArrayList<Monitor>();
    private final ArrayList<Monitor> mMonitorQueue = new ArrayList<Monitor>();
    
    HandlerChecker(Handler handler, String name, long waitMaxMillis) {
        mHandler = handler;
        mName = name;
        mWaitMax = waitMaxMillis;
        mCompleted = true;
    }
    
    public void scheduleCheckLocked() {
        //mCompleted一开始是true，会把所有的mMonitorQueue全部放到mMonitors列表中，再清空mMonitorQueue
        //一般只有开机时才会调用addMonitor加入mMonitorQueue，这里执行完第一次后mMonitorQueue就一直为空了
        //如果是监听的handler，mMonitorQueue对象本来就是空，所以mMonitors也就为空
        if (mCompleted) {
            // Safe to update monitors in queue, Handler is not in the middle of work
            mMonitors.addAll(mMonitorQueue);
            mMonitorQueue.clear();
        }
        //通过mMonitors为空判断监听的是handler，如果监听的handler正在polling代表此handler没有卡死
        if ((mMonitors.size() == 0 && mHandler.getLooper().getQueue().isPolling())
            || (mPauseCount > 0)) {
            mCompleted = true;
            return;
        }
        //mCompleted一开始为true，只有走了下面置为false后，下次进来没必要再走下面流程（即一个完整的监听周期并没完成），这里判断后直接return
        if (!mCompleted) {
            // we already have a check in flight, so no need
            return;
        }

        //每个监听周期先把mCompleted设为false，记录开始时间，再往该handler最前面插入一条消息，如果此消息执行后会回调HandlerChecker.run
        mCompleted = false;
        mCurrentMonitor = null;
        mStartTime = SystemClock.uptimeMillis();
        mHandler.postAtFrontOfQueue(this);
    }
    
    @Override
    public void run() {
		//如果监听的是moniter，这个mMonitors就不为空，分别执行monitor方法，如果系统服务卡死，比如ams卡死，就会一直停留在monitor方法等待其他线程释放锁
        //如果监听的是handler，monitor为空，都执行到这个方法了代表handler没有卡死
        final int size = mMonitors.size();
        for (int i = 0 ; i < size ; i++) {
            synchronized (mLock) {
                mCurrentMonitor = mMonitors.get(i);
            }
            mCurrentMonitor.monitor();
        }

        synchronized (mLock) {
            mCompleted = true;
            mCurrentMonitor = null;
        }
    }
}
```

代码中注释很详细了，不赘述了，可以看到对于要系统服务和handler，watchdog监听的原理不一样，handler的话只要执行到这个run方法就代表没有卡死，系统服务的话需要回调monitor方法等待其他线程释放锁。

```java
private static final int COMPLETED = 0;
private static final int WAITING = 1;
private static final int WAITED_HALF = 2;
private static final int OVERDUE = 3;
private int evaluateCheckerCompletionLocked() {
    int state = COMPLETED;
    for (int i=0; i<mHandlerCheckers.size(); i++) {
        HandlerChecker hc = mHandlerCheckers.get(i);
        state = Math.max(state, hc.getCompletionStateLocked());
    }
    return state;
}
public int getCompletionStateLocked() {
    if (mCompleted) {
        return COMPLETED;
    } else {
        long latency = SystemClock.uptimeMillis() - mStartTime;
        if (latency < mWaitMax/2) {
            return WAITING;
        } else if (latency < mWaitMax) {
            return WAITED_HALF;
        }
    }
    return OVERDUE;
}
```

第三步evaluateCheckerCompletionLocked在等待30s后评估状态，如果已经完成就返回COMPLETED，再计算下现在的时间减去开始时间，如果在30s内返回WAITING，如果大于30小于60返回WAITED_HALF，如果大于等于60返回OVERDUE。这里设计的比较巧妙的点由于要的是最差的状态，所以等待时间越长设计的状态值就越大，再根据Math.max作比较，高效而优雅。  

##### 监听bindler

注意在构造函数中`addMonitor(new BinderThreadMonitor());`

```java
private static final class BinderThreadMonitor implements Watchdog.Monitor {
    @Override
    public void monitor() {
        Binder.blockUntilThreadAvailable();
    }
}
public class Binder implements IBinder {
    public static final native void blockUntilThreadAvailable();
}
```

这里调到jni  

android_util_Binder.cpp：

```c++
static void android_os_Binder_blockUntilThreadAvailable(JNIEnv* env, jobject clazz)
{
    return IPCThreadState::self()->blockUntilThreadAvailable();
}
```

再到IPCThreadState.cpp

```c++
void IPCThreadState::blockUntilThreadAvailable()
{
    //加锁
    pthread_mutex_lock(&mProcess->mThreadCountLock);
    mProcess->mWaitingForThreads++;
    //这个while循环表示正在等待执行的线程数大于最大的binder线程上限（15个）
    while (mProcess->mExecutingThreadsCount >= mProcess->mMaxThreads) {
        pthread_cond_wait(&mProcess->mThreadCountDecrement, &mProcess->mThreadCountLock);
    }
    mProcess->mWaitingForThreads--;
    //释放锁
    pthread_mutex_unlock(&mProcess->mThreadCountLock);
}
```

while循环中代表如果正在等待执行的binder线程数大于15后就会调用`pthread_cond_wait`函数阻塞当前线程。blockUntilThreadAvailable先枷锁，如果执行此方法后卡顿或者binder线程的数量大于等于15，就需要等待系统释放其他的binder线程，直到小于15后才会释放锁，这也是jni层中binder的监听是否卡顿的原理。

#### 总结

Watchdog实现原理很简单：开机后先获取单例，在构造函数中把监听的线程的handler封装起来，要监听的系统服务单独封装到fg线程，后面通过addMonitor和addThread加入到相应的队列，再调用start方法异步线程，异步线程启动本地run方法，run方法中先遍历要监听的列表，分别计算出开始时间，然后等待30s再评估出最差的状态，如果超过30s就先dump一次日志，超过1分钟就写进dropbox日志，再杀掉当前进程即systemserver进程，即系统会重启。

#### 案例

实际开发中遇到启动中卡在正在启动页面，关键log如下：

```java
05-10 11:50:49.469  1000  1439  4361 I DropBoxManagerService: add tag=system_server_watchdog isTagEnabled=true flags=0x2
05-10 11:50:49.474  1000  1439  1627 W Watchdog: *** WATCHDOG KILLING SYSTEM PROCESS: Blocked in monitor com.android.server.wm.WindowManagerService on foreground thread (android.fg), Blocked in handler on display thread (android.display)
05-10 11:50:49.476  1000  1439  1627 W Watchdog: android.fg annotated stack trace:
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     at com.android.server.wm.WindowManagerService.monitor(WindowManagerService.java:6874)
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     - waiting to lock <0x050c7990> (a com.android.server.wm.WindowManagerGlobalLock)
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     at com.android.server.Watchdog$HandlerChecker.run(Watchdog.java:277)
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     at android.os.Handler.handleCallback(Handler.java:938)
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     at android.os.Handler.dispatchMessage(Handler.java:99)
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     at android.os.Looper.loopOnce(Looper.java:201)
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     at android.os.Looper.loop(Looper.java:288)
05-10 11:50:49.477  1000  1439  1627 W Watchdog:     at android.os.HandlerThread.run(HandlerThread.java:67)
05-10 11:50:49.478  1000  1439  1627 W Watchdog:     at com.android.server.ServiceThread.run(ServiceThread.java:44)
05-10 11:50:49.480  1000  1439  1627 W Watchdog: android.display annotated stack trace:
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at android.view.SurfaceControl.nativeApplyTransaction(Native Method)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at android.view.SurfaceControl.access$2900(SurfaceControl.java:89)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at android.view.SurfaceControl$GlobalTransactionWrapper.applyGlobalTransaction(SurfaceControl.java:3681)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at android.view.SurfaceControl.closeTransaction(SurfaceControl.java:1741)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     - locked <0x08b9d289> (a java.lang.Class)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at com.android.server.wm.WindowManagerService.closeSurfaceTransaction(WindowManagerService.java:1107)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at com.android.server.wm.RootWindowContainer.performSurfacePlacementNoTrace(RootWindowContainer.java:880)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at com.android.server.wm.RootWindowContainer.performSurfacePlacement(RootWindowContainer.java:830)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at com.android.server.wm.WindowSurfacePlacer.performSurfacePlacementLoop(WindowSurfacePlacer.java:177)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at com.android.server.wm.WindowSurfacePlacer.performSurfacePlacement(WindowSurfacePlacer.java:126)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at com.android.server.wm.WindowSurfacePlacer.performSurfacePlacement(WindowSurfacePlacer.java:115)
05-10 11:50:49.481  1000  1439  1627 W Watchdog:     at com.android.server.wm.AppTransition.handleAppTransitionTimeout(AppTransition.java:1694)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     - locked <0x050c7990> (a com.android.server.wm.WindowManagerGlobalLock)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at com.android.server.wm.AppTransition.lambda$new$0$AppTransition(AppTransition.java:271)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at com.android.server.wm.AppTransition$$ExternalSyntheticLambda0.run(Unknown Source:2)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at android.os.Handler.handleCallback(Handler.java:938)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at android.os.Handler.dispatchMessage(Handler.java:99)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at android.os.Looper.loopOnce(Looper.java:201)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at android.os.Looper.loop(Looper.java:288)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at android.os.HandlerThread.run(HandlerThread.java:67)
05-10 11:50:49.482  1000  1439  1627 W Watchdog:     at com.android.server.ServiceThread.run(ServiceThread.java:44)
05-10 11:50:49.482  1000  1439  1627 W Watchdog: *** GOODBYE!
```

可以看到是AppTransition.handleAppTransitionTimeout加锁后调用到performSurfacePlacement，直到调到SurfaceControl.nativeApplyTransaction(Native Method)后到jni层一直没有返回导致卡死。目前初步解决方案是在开机过程中把mService.mWindowPlacerLocked.performSurfacePlacement();放异步

```java
// AppTransition.java 
private void handleAppTransitionTimeout() {
    synchronized (mService.mGlobalLock) {
        final DisplayContent dc = mDisplayContent;
        if (dc == null) {
            return;
        }
        notifyAppTransitionTimeoutLocked();
        if (isTransitionSet() || !dc.mOpeningApps.isEmpty() || !dc.mClosingApps.isEmpty()
            || !dc.mChangingContainers.isEmpty()) {
            setTimeout();
            mService.mWindowPlacerLocked.performSurfacePlacement();
        }
    }
}
```



