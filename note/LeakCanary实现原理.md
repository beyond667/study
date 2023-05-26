#### LeakCanary2.7实现原理分析 ####

`LeakCanary` 是Square公司开源的内存检测工具，与常用的AS自带的Profile工具，MAT工具相比使用非常简单（只需要一行代码即可），泄漏后生成的链式结构也很容易定位泄漏点。 

- 为什么初始化都不需要（2.x之前需要），一行代码就搞定？

- 其实现原理是什么？

- 其流程是什么？ 

我们具体分析下。  

##### 1 LeakCanary初始化

[LeakCanary]: https://github.com/square/leakcanary

一行代码引入：

``` groovy
    implementation 'com.squareup.leakcanary:leakcanary-android:2.7'
```

在leakcanary-android这个lib依赖的leakcanary-android-core的AndroidManifest文件中注册了ContentProvider

``` groovy
    <application>
        <provider
            android:name="leakcanary.internal.AppWatcherInstaller$MainProcess"
            android:authorities="${applicationId}.leakcanary-installer"
            android:enabled="@bool/leak_canary_watcher_auto_install"
            android:exported="false" />
    </application>
```

熟悉应用启动流程的同学都知道，ContentProvider.onCreate方法要早于Application.onCreate

```java
	//ActivityThread.java的handleBindApplication方法

	//先安装ContentProvider
    if (!ArrayUtils.isEmpty(data.providers)) {
        installContentProviders(app, data.providers);
    }

	//Instrumentation仪表盘的onCreate
	mInstrumentation.onCreate(data.instrumentationArgs);
    try {
        //Instrumentation来具体调用我们应用生命周期，先执行我们Application.onCreate
        mInstrumentation.callApplicationOnCreate(app);
    } catch (Exception e) {
        if (!mInstrumentation.onException(app, e)) {
            throw new RuntimeException(
                "Unable to create application " + app.getClass().getName()
                + ": " + e.toString(), e);
        }
    }

```

这样AppWatcherInstaller的onCreate就会先执行

```kotlin
//AppWatcherInstaller.onCreate  
  override fun onCreate(): Boolean {
    val application = context!!.applicationContext as Application
    AppWatcher.manualInstall(application)
    return true
  }
```

继续看AppWatcher.manualInstall做了什么

```kotlin
  @JvmOverloads
  fun manualInstall(
    application: Application,
    retainedDelayMillis: Long = TimeUnit.SECONDS.toMillis(5),   //retainedDelayMillis初始化是5s，后面会用到
    watchersToInstall: List<InstallableWatcher> = appDefaultWatchers(application)
  ) {
    this.retainedDelayMillis = retainedDelayMillis
    // 省略无关紧要代码
    LeakCanaryDelegate.loadLeakCanary(application)

    watchersToInstall.forEach {
      it.install()
    }
  }

  //LeakCanary中需要监控的对象
  fun appDefaultWatchers(
    application: Application,
    reachabilityWatcher: ReachabilityWatcher = objectWatcher
  ): List<InstallableWatcher> {
    return listOf(
      ActivityWatcher(application, reachabilityWatcher),
      FragmentAndViewModelWatcher(application, reachabilityWatcher),
      RootViewWatcher(reachabilityWatcher),
      ServiceWatcher(reachabilityWatcher)
    )
  }
```

可以看到其初始化时通过`ActivityWatcher`，`FragmentAndViewModelWatcher`，`ServiceWatcher` 执行其install方法

以ActivityWatcher的install方法为例

```kotlin
  private val lifecycleCallbacks =
    object : Application.ActivityLifecycleCallbacks by noOpDelegate() {
      override fun onActivityDestroyed(activity: Activity) {
        reachabilityWatcher.expectWeaklyReachable(
          activity, "${activity::class.java.name} received Activity#onDestroy() callback"
        )
      }
    }

  override fun install() {
    application.registerActivityLifecycleCallbacks(lifecycleCallbacks)
  }
```

可以看到其主要监听了ActivityLifecycleCallbacks的onActivityDestroyed方法，也就是说在Activity准备销毁时LeakCanary开始监听其是否会泄漏。



##### 2 LeakCanary原理：WeakReference和ReferenceQueue的妙用

```java
public class WeakReference<T> extends Reference<T> {
    public WeakReference(T referent) {
        super(referent);
    }
    public WeakReference(T referent, ReferenceQueue<? super T> q) {
        super(referent, q);
    }
}
```

WeakRefernece（作为容器）的构造函数中可以传ReferenceQueue，表示被弱引用容器标识的对象如果被GC，此时会把WeakReference容器添加到指定的ReferenceQueue中，也就是说如果对象被回收，就可以从ReferenceQueue中poll出WeakReference容器，即：如果从ReferenceQueue中poll不出就代表可能泄露。看下面例子就能明白

```java
ReferenceQueue referenceQueue = new ReferenceQueue();
Object obj = new Object();

//把obj放入weakReference，并和一个referenceQueue关联
//当obj被gc回收后，盛放它的weakReference会被添加与之关联的referenceQueue
WeakReference weakReference = new WeakReference(obj,referenceQueue);
System.out.println("从queue里取出来的为 " + referenceQueue.poll()); //因为对象没被回收，所以此时poll出来的为空

//把obj置空，让它没有强引用
obj = null;
//gc，让可以回收的对象回收
Runtime.getRuntime().gc();
try{
    Thread.sleep(1000);
}catch (Exception e){}

//gc后拿到的reference和wekreference是同一个对象，说明对象被回收后WeakReference被添加到referenceQueue
Reference findRef = referenceQueue.poll();
System.out.println("findRef = " +findRef + "是否等于上面的weakReference = " + (findRef == weakReference));

```

基于以上原理，把需要观察的对象（Activity，fragment）等加到弱引用容器中，并指定ReferenceQueue，一段时间后从queue里判断是否能拿到弱引用。



##### 3 具体流程

继续分析源码，在onActivityDestroyed回调后执行了ObjectWatcher.expectWeaklyReachable()

```kotlin
  @Synchronized override fun expectWeaklyReachable(watchedObject: Any,description: String) {
    if (!isEnabled()) {
      return
    }
    //先把已经GC的清空下
    removeWeaklyReachableObjects()
    // key是随机生成的uuid
    val key = UUID.randomUUID().toString()
    val watchUptimeMillis = clock.uptimeMillis()
    //初始化弱引用，指定queue，并加入观察对象列表
    val reference =KeyedWeakReference(watchedObject, key, description, watchUptimeMillis, queue)
    watchedObjects[key] = reference
    //异步任务处理
    checkRetainedExecutor.execute {
      moveToRetained(key)
    }
  }

  //从ReferenceQueue中poll出来弱引用，如果能拿到，说明被弱引用引用的对象已经被GC，就可以从观察对象列表中删除
  private fun removeWeaklyReachableObjects() {
    // WeakReferences are enqueued as soon as the object to which they point to becomes weakly
    // reachable. This is before finalization or garbage collection has actually happened.
    var ref: KeyedWeakReference?
    do {
      ref = queue.poll() as KeyedWeakReference?
      if (ref != null) {
        watchedObjects.remove(ref.key)
      }
    } while (ref != null)
  }
```

而异步任务checkRetainedExecutor初始化中用到的retainedDelayMillis在LeakCanary初始化方法manualInstall中已经设置5s

```kotlin
  val objectWatcher = ObjectWatcher(
    clock = { SystemClock.uptimeMillis() },
    checkRetainedExecutor = {
      check(isInstalled) {
        "AppWatcher not installed"
      }
      mainHandler.postDelayed(it, retainedDelayMillis)
    },
    isEnabled = { true }
  )
```

也就是说5s之后调用了moveToRetained把key放到了怀疑列表。

```kotlin
  @Synchronized private fun moveToRetained(key: String) {
    removeWeaklyReachableObjects()
    val retainedRef = watchedObjects[key]
    if (retainedRef != null) {
      retainedRef.retainedUptimeMillis = clock.uptimeMillis()
      onObjectRetainedListeners.forEach { it.onObjectRetained() }
    }
  }
```

继续看InternalLeakCanary.onObjectRetained()

```kotlin
  override fun onObjectRetained() = scheduleRetainedObjectCheck()

  fun scheduleRetainedObjectCheck() {
    if (this::heapDumpTrigger.isInitialized) {
      heapDumpTrigger.scheduleRetainedObjectCheck()
    }
  }
```

HeapDumpTrigger.scheduleRetainedObjectCheck()

```kotlin
  fun scheduleRetainedObjectCheck(
    delayMillis: Long = 0L
  ) {
    val checkCurrentlyScheduledAt = checkScheduledAt
    if (checkCurrentlyScheduledAt > 0) {
      return
    }
    checkScheduledAt = SystemClock.uptimeMillis() + delayMillis
    backgroundHandler.postDelayed({
      checkScheduledAt = 0
      checkRetainedObjects()
    }, delayMillis)
  }
```

最主要HeapDumpTrigger.checkRetainedObjects()，只分析一些关键代码

```kotlin
 private fun checkRetainedObjects() {
    var retainedReferenceCount = objectWatcher.retainedObjectCount
     //如果怀疑列表数量大于0，执行一次GC
    if (retainedReferenceCount > 0) {
      gcTrigger.runGc()
      retainedReferenceCount = objectWatcher.retainedObjectCount
    }
    //config.retainedVisibleThreshold配置的5，也就是说这方法会把目前怀疑列表的count和5对比
    if (checkRetainedCount(retainedReferenceCount, config.retainedVisibleThreshold)) return

    dumpHeap(
      retainedReferenceCount = retainedReferenceCount,
      retry = true,
      reason = "$retainedReferenceCount retained objects, app is $visibility"
    )
  }
```

这里也就是说会判断怀疑列表的数量，如果大于5就会执行dumpHeap（）来具体分析下是否有泄漏

```kotlin
  private fun dumpHeap(
    retainedReferenceCount: Int,
    retry: Boolean,
    reason: String
  ) {
      //这里执行heapDumper.dumpHeap方法生成hprof文件
      when (val heapDumpResult = heapDumper.dumpHeap()) {///省略
      }
      //生成文件后HeapAnalyzerService这个服务的runAnalysis方法开始分析hprof文件
      HeapAnalyzerService.runAnalysis(
          context = application,
          heapDumpFile = heapDumpResult.file,
          heapDumpDurationMillis = heapDumpResult.durationMillis,
          heapDumpReason = reason
      )
    }
  }
```

内存分析服务HeapAnalyzerService.runAnalysis()方法，开启了HeapAnalyzerService服务，其继承于IntentService

```kotlin
 fun runAnalysis(
      context: Context,
      heapDumpFile: File,
      heapDumpDurationMillis: Long? = null,
      heapDumpReason: String = "Unknown"
    ) {
      val intent = Intent(context, HeapAnalyzerService::class.java)
      intent.putExtra(HEAPDUMP_FILE_EXTRA, heapDumpFile)
      intent.putExtra(HEAPDUMP_REASON_EXTRA, heapDumpReason)
      heapDumpDurationMillis?.let {
        intent.putExtra(HEAPDUMP_DURATION_MILLIS_EXTRA, heapDumpDurationMillis)
      }
      startForegroundService(context, intent)
    }
```

其重写的异步方法onHandleIntentInForeground

```kotlin
  override fun onHandleIntentInForeground(intent: Intent?) {
    // Since we're running in the main process we should be careful not to impact it.
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    val heapDumpFile = intent.getSerializableExtra(HEAPDUMP_FILE_EXTRA) as File
    val config = LeakCanary.config
    //如果dumpfile文件存在，就分析此文件
    val heapAnalysis = if (heapDumpFile.exists()) {
      analyzeHeap(heapDumpFile, config)
    } 
  }
```

这里执行了heapAnalyzer.analyze来具体分析

```kotlin
private fun analyzeHeap(
  heapDumpFile: File,
  config: Config
): HeapAnalysis {
  val heapAnalyzer = HeapAnalyzer(this)

  val proguardMappingReader = try {
    ProguardMappingReader(assets.open(PROGUARD_MAPPING_FILE_NAME))
  } catch (e: IOException) { }
  return heapAnalyzer.analyze(
    heapDumpFile = heapDumpFile,
    leakingObjectFinder = config.leakingObjectFinder,
    referenceMatchers = config.referenceMatchers,
    computeRetainedHeapSize = config.computeRetainedHeapSize,
    objectInspectors = config.objectInspectors,
    metadataExtractor = config.metadataExtractor,
    proguardMapping = proguardMappingReader?.readProguardMapping()
  )
}
```

这里注意到HeapAnalyzer已经不是用的haha算法了，2.7版本已经用的是shark包了

```kotlin
fun analyze(
    heapDumpFile: File,
    leakingObjectFinder: LeakingObjectFinder,
    referenceMatchers: List<ReferenceMatcher> = emptyList(),
    computeRetainedHeapSize: Boolean = false,
    objectInspectors: List<ObjectInspector> = emptyList(),
    metadataExtractor: MetadataExtractor = MetadataExtractor.NO_OP,
    proguardMapping: ProguardMapping? = null
  ): HeapAnalysis {
     return try {
      listener.onAnalysisProgress(PARSING_HEAP_DUMP)
      val sourceProvider = ConstantMemoryMetricsDualSourceProvider(FileSourceProvider(heapDumpFile))
      sourceProvider.openHeapGraph(proguardMapping).use { graph ->
        val helpers =
          FindLeakInput(graph, referenceMatchers, computeRetainedHeapSize, objectInspectors)
        val result = helpers.analyzeGraph(
          metadataExtractor, leakingObjectFinder, heapDumpFile, analysisStartNanoTime
        )
        val lruCacheStats = (graph as HprofHeapGraph).lruCacheStats()
      }
    }
  }
```

这个方法稍微有点复杂，大概意思是从内存dump文件中基于根对象可达性算法（helpers.analyzeGraph）分析对象是否存在泄漏，并返回HeapAnalysisSuccess包装后的对象。继续看下这个可达性算法

```kotlin
  private fun FindLeakInput.analyzeGraph(
    metadataExtractor: MetadataExtractor,
    leakingObjectFinder: LeakingObjectFinder,
    heapDumpFile: File,
    analysisStartNanoTime: Long
  ): HeapAnalysisSuccess {
    //省略解析文件的操作
    //findLeakingObjectIds 这个方法通过特定规则过滤之后得到一组泄露对象id的set集合
    val leakingObjectIds = leakingObjectFinder.findLeakingObjectIds(graph)
    //解析完的泄露对象调用findLeaks来分析最短路径链
    val (applicationLeaks, libraryLeaks, unreachableObjects) = findLeaks(leakingObjectIds)
    return HeapAnalysisSuccess(
      heapDumpFile = heapDumpFile,
      createdAtTimeMillis = System.currentTimeMillis(),
      analysisDurationMillis = since(analysisStartNanoTime),
      metadata = metadataWithCount,
      applicationLeaks = applicationLeaks,
      libraryLeaks = libraryLeaks,
      unreachableObjects = unreachableObjects
    )
  }
  // 根据解析到的GCRoot对象和泄露的对象，在graph中搜索最短引用链
  private fun FindLeakInput.findLeaks(leakingObjectIds: Set<Long>): LeaksAndUnreachableObjects {
    val pathFinder = PathFinder(graph, listener, referenceMatchers)
    //这里采用的是广度优先遍历的算法进行搜索出多条路径
    val pathFindingResults =
      pathFinder.findPathsFromGcRoots(leakingObjectIds, computeRetainedHeapSize)
    //查找不可达对象
    val unreachableObjects = findUnreachableObjects(pathFindingResults, leakingObjectIds)

    val shortestPaths =
      deduplicateShortestPaths(pathFindingResults.pathsToLeakingObjects)

    val inspectedObjectsByPath = inspectObjects(shortestPaths)

    val retainedSizes =
      if (pathFindingResults.dominatorTree != null) {
        computeRetainedSizes(inspectedObjectsByPath, pathFindingResults.dominatorTree)
      } else {
        null
      }
    //findPathsFromGcRoots基于广度优先算法找到的可能多条路径，这里又基于深度优先算法找到最短路径
    val (applicationLeaks, libraryLeaks) = buildLeakTraces(
      shortestPaths, inspectedObjectsByPath, retainedSizes
    )
    return LeaksAndUnreachableObjects(applicationLeaks, libraryLeaks, unreachableObjects)
  }
```

查找不可达对象

```kotlin
  private fun FindLeakInput.findUnreachableObjects(
    pathFindingResults: PathFindingResults,
    leakingObjectIds: Set<Long>
  ): List<LeakTraceObject> {
    val reachableLeakingObjectIds =
      pathFindingResults.pathsToLeakingObjects.map { it.objectId }.toSet()

    val unreachableLeakingObjectIds = leakingObjectIds - reachableLeakingObjectIds

    val unreachableObjectReporters = unreachableLeakingObjectIds.map { objectId ->
      ObjectReporter(heapObject = graph.findObjectById(objectId))
    }

    objectInspectors.forEach { inspector ->
      unreachableObjectReporters.forEach { reporter ->
        inspector.inspect(reporter)
      }
    }

    val unreachableInspectedObjects = unreachableObjectReporters.map { reporter ->
      val reason = resolveStatus(reporter, leakingWins = true).let { (status, reason) ->
        when (status) {
          LEAKING -> reason
          UNKNOWN -> "This is a leaking object"
          NOT_LEAKING -> "This is a leaking object. Conflicts with $reason"
        }
      }
      InspectedObject(
        reporter.heapObject, LEAKING, reason, reporter.labels
      )
    }

    return buildLeakTraceObjects(unreachableInspectedObjects, null)
  }
```

具体Shark分析hprof流程参照https://linjiang.tech/2019/12/25/leakcanary/，https://juejin.cn/post/6877829309961076750到此整个LeakCanary流程分析完。

##### 4 hprof文件解析

hprof的具体协议链接：http://hg.openjdk.java.net/jdk6/jdk6/jdk/raw-file/tip/src/share/demo/jvmti/hprof/manual.html#mozTocId848088

hprof文件的标准协议主要由head和body组成，body是由一系列不同类型的Record组成，Record主要用于描述trace、object、thread等信息，依次分为4个部分：TAG、TIME、LENGTH、BODY，其中TAG就是表示Record类型。Record之间依次排列或嵌套，最终组成hprof文件。

多个Record被进抽象为HprofMemoryIndex，Index可以快速定位到对应对象在hprof文件中的位置；最终Index和Hprof一起再组成HprofGraph，graph做为hprof的最上层描述，将所有堆中数据抽象为了 gcRoots、objects、classes、instances等集合。

后续通过 `Graph` 的 `objects` 集合找出泄露对象，然后通过<font color="red">广度优先遍历</font>找出其到 `GcRoot` 的引用路径链，结束流程。

##### 5 总结

LeakCanary怎么实现检测内存泄露的？

1 利用ContentProvider的onCreate方法早于Application.oncreate，在其方法里自动初始化，所以2.X版本只需要导入包即可。并监听activity的onActivityDestroyed等的销毁。

2 在监听对象的销毁方法中，先把对象加入到观察者对象列表中，利用WeakReference和ReferenceQueue的特性，5s后判断ReferenceQueue中是否能poll出相应的WeakReference来判断对象是否泄露，能poll出说明被销毁，从观察者列表中移除此对象，不能poll出说明还没被销毁，把其加入到怀疑列表。

3 如果怀疑列表的count大于默认的5，会执行一次HeapDumper.dumpHeap方法来dump一次内存快照，生成hprof文件后调用HeapAnalyzerService这个后台服务的runAnalysis方法开始分析此文件。

4 2.X版本分析内存文件用的是shark包下的heapAnalyzer.analyze，而1.X版本用的是haha包下的。原理都是基于hprof文件找到最短的引用链（先基于广度优先找到所有路径，再基于深度优先找到最短路径），最终构建为applicationLeaks和LibraryLeaks两种形式的泄露描述。
