#### 前言

PMS是android系统很重要的一个系统服务，主要负责管理应用的安装，卸载，更新，查询，权限管理。我们在四大组件的启动流程中都看到PMS的身影，比如通过Intent启动另一个应用时都会先通过PMS去获取该应用的PackageInfo信息。本文主要从两个方面分析PMS：PMS安装APP流程，PMS的启动和使用流程。代码基于Android13。

#### PMS启动流程

在[开机流程](https://github.com/beyond667/study/blob/master/note/Activity%E5%90%AF%E5%8A%A8%E6%B5%81%E7%A8%8B.md)中已经分析过，PMS是在`SystemServer`的`startBootstrapServices()`里启动的，本文只关注跟PMS相关的。

> frameworks/base/services/java/com/android/server/SystemServer.java

```java
private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
private static final String ENCRYPTED_STATE = "1";
private boolean mOnlyCore;

private void startBootstrapServices(@NonNull TimingsTraceAndSlog t) {
	//...
    // Only run "core" apps if we're encrypting the device.
    String cryptState = VoldProperties.decrypt().orElse("");
    if (ENCRYPTING_STATE.equals(cryptState)) {
        mOnlyCore = true;
    } else if (ENCRYPTED_STATE.equals(cryptState)) {
        mOnlyCore = true;
    }
    
    IPackageManager iPackageManager;
    Pair<PackageManagerService, IPackageManager> pmsPair = PackageManagerService.main(
        mSystemContext, installer, domainVerificationService,
        mFactoryTestMode != FactoryTest.FACTORY_TEST_OFF, mOnlyCore);
    mPackageManagerService = pmsPair.first;
    iPackageManager = pmsPair.second;
	//...
}
```

注意vold.decrypt属性值如果是1或者trigger_restart_min_framework，mOnlyCore就为true，代表不需扫描data分区。vold.decrypt是获取android磁盘的加密状态，默认是扫描data分区的。

>frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java

```java
public static Pair<PackageManagerService, IPackageManager> main(Context context,Installer installer, @NonNull DomainVerificationService domainVerificationService,boolean factoryTest, boolean onlyCore) {

    //1 构建注射器，里面传了PMS构造函数中需要的对象
    PackageManagerServiceInjector injector = new PackageManagerServiceInjector(
        context, lock, installer, installLock, new PackageAbiHelperImpl(),
        backgroundHandler,
        (i, pm) -> PermissionManagerService.create(context,
                                                   i.getSystemConfig().getAvailableFeatures()),
        (i, pm) -> new Settings(Environment.getDataDirectory(),
                                RuntimePermissionsPersistence.createInstance(),
                                i.getPermissionManagerServiceInternal(),
                                domainVerificationService, backgroundHandler, lock),
        (i, pm) -> SystemConfig.getInstance(),
        //...
    );
    //2 直接new PackageManagerService创建实例
    PackageManagerService m = new PackageManagerService(injector, onlyCore, factoryTest,
                                                        PackagePartitions.FINGERPRINT, Build.IS_ENG, Build.IS_USERDEBUG,
                                                        Build.VERSION.SDK_INT, Build.VERSION.INCREMENTAL);

    //3 通过PMS new出IPackageManagerImpl这个binder对象，以key为“package”添加到系统服务中
    IPackageManagerImpl iPackageManager = m.new IPackageManagerImpl();
    ServiceManager.addService("package", iPackageManager);
    final PackageManagerNative pmn = new PackageManagerNative(m);
    ServiceManager.addService("package_native", pmn);
    LocalManagerRegistry.addManager(PackageManagerLocal.class, m.new PackageManagerLocalImpl());
    return Pair.create(m, iPackageManager);
}
```

+ 注释1处先创建了个注射器，里面塞了很多PMS初始化时需要的资源，比如权限管理的服务PermissionManagerService，协助PMS保存应用信息的Settings，系统全局的配置信息SystemConfig等等。
+ 注释2把注释1的注射器传到PMS的构造函数中，new出实例
+ 注释3由于PMS并没继承Binder，所以其并不是binder对象，通过m.new IPackageManagerImpl初始化binder对象，并绑定到以key为`package`的系统服务中

先具体看下注释1里 的Settings和SystemConfig

##### 准备Settings

> frameworks/base/services/core/java/com/android/server/pm/Settings.java

```java
Settings(File dataDir...)  {
    mSystemDir = new File(dataDir, "system");
    mSystemDir.mkdirs();
	//记录系统所有安装的apk的信息，包括permission，name，flags，version等
    mSettingsFilename = new File(mSystemDir, "packages.xml");
    //packages.xml的备份文件
    mBackupSettingsFilename = new File(mSystemDir, "packages-backup.xml");
    //记录手机里所有app的简要信息，包括name、dataPath等
    mPackageListFilename = new File(mSystemDir, "packages.list");
    FileUtils.setPermissions(mPackageListFilename, 0640, SYSTEM_UID, PACKAGE_INFO_GID);

    final File kernelDir = new File("/config/sdcardfs");
    mKernelMappingFilename = kernelDir.exists() ? kernelDir : null;
    //强制stop的apk信息  
    mStoppedPackagesFilename = new File(mSystemDir, "packages-stopped.xml");
    //packages-stopped的备份文件
    mBackupStoppedPackagesFilename = new File(mSystemDir, "packages-stopped-backup.xml");

    registerObservers();
}
```

这个Settings不是我们常用的provider下的Settings，而是协助PMS保存APK的信息的类，比较重要的是packages.xml和packages.list文件，分别记录所有应用的详细和简略信息。任何应用的更改，包括权限，安装卸载更新等都会写入到packages.xml中，写入之前会先把packages.xml拷贝一份为packages-backup.xml，写入成功后删除备份文件，下次写入时如果有备份文件，则说明之前写入有异常，就会重新使用packages-backup.xml备份文件。配置文件里面具体内容参考[packages.xml和packages.list全解析](https://zhuanlan.zhihu.com/p/31124919)

继续看PMS的构造函数，由于较长，分开来看

```java
//frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java
public PackageManagerService(PackageManagerServiceInjector injector...) {
    mInjector = injector;
    mContext = injector.getContext();
    mSettings = injector.getSettings();
    //...
    mSettings.addSharedUserLPw("android.uid.system", Process.SYSTEM_UID/*1000*/,
                               ApplicationInfo.FLAG_SYSTEM,ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.phone", RADIO_UID/*1001*/,ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.log", LOG_UID/*1007*/,
                               ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.nfc", NFC_UID/*1027*/,
                               ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.bluetooth", BLUETOOTH_UID/*1002*/,
                               ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.shell", SHELL_UID/*2000*/,
                               ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.se", SE_UID,
                               ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.networkstack", NETWORKSTACK_UID,
                               ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    mSettings.addSharedUserLPw("android.uid.uwb", UWB_UID,
                               ApplicationInfo.FLAG_SYSTEM, ApplicationInfo.PRIVATE_FLAG_PRIVILEGED);
    
    //...
}

//frameworks/base/services/core/java/com/android/server/pm/Settings.java
SharedUserSetting addSharedUserLPw(String name, int uid, int pkgFlags, int pkgPrivateFlags) {
    //1 settings里用mSharedUsers了所有的shareuser信息
    SharedUserSetting s = mSharedUsers.get(name);
    if (s != null) {
        if (s.mAppId == uid) {
            return s;
        }
        return null;
    }
    s = new SharedUserSetting(name, pkgFlags, pkgPrivateFlags);
    s.mAppId = uid;
    if (mAppIds.registerExistingAppId(uid, s, name)) {
        mSharedUsers.put(name, s);
        return s;
    }
    return null;
}

//frameworks/base/services/core/java/com/android/server/pm/AppIdSettingMap.java
/**
* We use an ArrayList instead of an SparseArray for non system apps because the number of apps
* might be big, and only ArrayList gives us a constant lookup time.
*/
private final WatchedArrayList<SettingBase> mNonSystemSettings;
private final WatchedSparseArray<SettingBase> mSystemSettings;
public boolean registerExistingAppId(int appId, SettingBase setting, Object name) {
    //2 pid大于10000即为非系统应用
    if (appId >= Process.FIRST_APPLICATION_UID) {
        int size = mNonSystemSettings.size();
        //先减去10000算出非系统应用的index
        final int index = appId - Process.FIRST_APPLICATION_UID;
        //如果index已经大于非缓存的size，直接添加缺少的size进去，因为用的是数组存的，为了方便后面的根据index查找
        while (index >= size) {
            mNonSystemSettings.add(null);
            size++;
        }
        //如果相应的index处已经存了，说明之前已经添加过了直接返回false
        if (mNonSystemSettings.get(index) != null) {
            return false;
        }
        //否则把此SharedUserSetting添加到非系统缓存列表中
        mNonSystemSettings.set(index, setting);
    } else {
        //系统的同理，只不过系统的用的是sparseArray，不需要像非系统的数组一样，在中间塞一堆空对象
        if (mSystemSettings.get(appId) != null) {
            return false;
        }
        mSystemSettings.put(appId, setting);
    }
    return true;
}
```

首先从构造器里获取需要的各种资源，比如settings。然后通过addSharedUserLPw把android.uid.system/phone/log/shell等这9个系统默认的sharedUserId添加到settings里。这里牵涉到共享用户id的概念，具有相同sharedUserId的应用具有相同的权限，并且共用同一个uid。比如我们的系统应用在AndroidManifest中配置了`android:sharedUserId="android.uid.system"`，那个该应用就认为是系统应用，并且其uid即为android.uid.system（1000）。

+ 注释1在settings.addSharedUserLPw方法里缓存了所有的sharedUser信息，如果第一次添加就new个新的SharedUserSetting，并调用AppIdSettingMap.registerExistingAppId来注册
+ 注释2处对pid大于10000的即为非系统应用，把注释1new的SharedUserSetting添加到非系统应用的缓存中，系统应用同理。注意这里系统应用和非系统应用的缓存用了两种数据结构，上面英文注释也写的很清楚，非系统应用用的ArrayList而不是SpareseArray，因为非系统应用数量可能会很多，在[HashMap/ArrayMap/SparseArray](https://github.com/beyond667/study/blob/master/note/HashMap%E5%92%8CArrayMap%E5%92%8CSparseArray.md)中我们知道ArrayMap和SparseArray比较适用于数据量小于1000的情况

##### SystemConfig

构建注射器时也通过getInstance初始化了SystemConfig

>/frameworks/base/core/java/com/android/server/SystemConfig.java

```java
public static SystemConfig getInstance() {
    synchronized (SystemConfig.class) {
        if (sInstance == null) {
            sInstance = new SystemConfig();
        }
        return sInstance;
    }
}
SystemConfig() {
    readAllPermissions();
    readPublicNativeLibrariesList();
}
private void readAllPermissions() {
    final XmlPullParser parser = Xml.newPullParser();
    readPermissions(parser, Environment.buildPath(
        Environment.getRootDirectory(), "etc", "sysconfig"), ALLOW_ALL);
    readPermissions(parser, Environment.buildPath(
        Environment.getRootDirectory(), "etc", "permissions"), ALLOW_ALL);
    
    readPermissions(parser, Environment.buildPath(
        Environment.getVendorDirectory(), "etc", "sysconfig"), vendorPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getVendorDirectory(), "etc", "permissions"), vendorPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getOdmDirectory(), "etc", "sysconfig"), odmPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getOdmDirectory(), "etc", "permissions"), odmPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getOemDirectory(), "etc", "sysconfig"), oemPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getOemDirectory(), "etc", "permissions"), oemPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getProductDirectory(), "etc", "sysconfig"), productPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getProductDirectory(), "etc", "permissions"), productPermissionFlag);
    readPermissions(parser, Environment.buildPath(
        Environment.getSystemExtDirectory(), "etc", "sysconfig"), ALLOW_ALL);
    readPermissions(parser, Environment.buildPath(
        Environment.getSystemExtDirectory(), "etc", "permissions"), ALLOW_ALL);
}
private void readPublicNativeLibrariesList() {
    readPublicLibrariesListFile(new File("/vendor/etc/public.libraries.txt"));
    String[] dirs = {"/system/etc", "/system_ext/etc", "/product/etc"};
    for (String dir : dirs) {
        File[] files = new File(dir).listFiles();
        if (files == null) {
            continue;
        }
        for (File f : files) {
            String name = f.getName();
            if (name.startsWith("public.libraries-") && name.endsWith(".txt")){
                readPublicLibrariesListFile(f);
            }
        }
    }
}
```

SystemConfig是全局的系统配置信息，通过解析相应的xml来配置。可以看到从以下目录获取permissions，（空，vendor，oem，odm，product，system）/etc/（permissions，sysconfig）这12个文件夹下的所有xml，另外还从（system，system_ext，product）/ect这3个目录读以public.libraries-开头，.txt结尾的NativeLibraries文件。

看下readPermissions到底读了什么

```java
public void readPermissions(final XmlPullParser parser, File libraryDir, int permissionFlag) {
    if (!libraryDir.exists() || !libraryDir.isDirectory()) {
        return;
    }
    if (!libraryDir.canRead()) {
        return;
    }

    File platformFile = null;
    for (File f : libraryDir.listFiles()) {
        if (!f.isFile()) {
            continue;
        }

        // We'll read platform.xml last
        // 先跳过读etc/permissions/platform.xml，存起来最后再读
        if (f.getPath().endsWith("etc/permissions/platform.xml")) {
            platformFile = f;
            continue;
        }
		//只读xml文件
        if (!f.getPath().endsWith(".xml")) {
            continue;
        }
        if (!f.canRead()) {
            continue;
        }
        readPermissionsFromXml(parser, f, permissionFlag);
    }

    if (platformFile != null) {
        readPermissionsFromXml(parser, platformFile, permissionFlag);
    }
}
private void readPermissionsFromXml(final XmlPullParser parser, File permFile,int permissionFlag) {
    FileReader permReader = new FileReader(permFile);
    parser.setInput(permReader);
    //文件内容不是以permissions或者config节点开始的，直接抛异常
    if (!parser.getName().equals("permissions") && !parser.getName().equals("config")) {
        throw new XmlPullParserException("Unexpected start tag in ...");
    }
    
    while (true) {
        XmlUtils.nextElement(parser);
        String name = parser.getName();
        switch (name) {
            case "group": {
                String gidStr = parser.getAttributeValue(null, "gid");
                int gid = android.os.Process.getGidForName(gidStr);
                mGlobalGids = appendInt(mGlobalGids, gid);
            }break;
            case "permission": {
                String perm = parser.getAttributeValue(null, "name");
                readPermission(parser, perm);
            }  break;
            case "assign-permission": {
                String perm = parser.getAttributeValue(null, "name");  
                String uidStr = parser.getAttributeValue(null, "uid");
                int uid = Process.getUidForName(uidStr);
                ArraySet<String> perms = mSystemPermissions.get(uid);
                if (perms == null) {
                    perms = new ArraySet<String>();
                    mSystemPermissions.put(uid, perms);
                }
                perms.add(perm);
            }break;
            case "feature": {
                String fname = parser.getAttributeValue(null, "name");
                int fversion = XmlUtils.readIntAttribute(parser, "version", 0);
                addFeature(fname, fversion);
            }break;
            case "privapp-permissions": {
				//单个app的权限，略
            }break;
                //...
        }
    }
}
private void addFeature(String name, int version) {
    FeatureInfo fi = mAvailableFeatures.get(name);
    if (fi == null) {
        fi = new FeatureInfo();
        fi.name = name;
        fi.version = version;
        mAvailableFeatures.put(name, fi);
    } else {
        fi.version = Math.max(fi.version, version);
    }
}
```

没什么好解释的，通过pull的方式解析xml，把相应的节点写入对应的变量中。

PMS主要用到了AvailableFeatures和SharedLibraries，PMS的权限管理类PermissionManagerService用到了SystemPermissions和GlobalGids，其实就是上面扫描的xml里获取的group,permission,assign-permission,feature,library这些节点的值。

```java
//frameworks/base/services/core/java/com/android/server/pm/permission/PermissionManagerServiceImpl.java
PermissionManagerService(Context context,ArrayMap<String, FeatureInfo>availableFeatures) {
	//...
    mPermissionManagerServiceImpl = new PermissionManagerServiceImpl(context,availableFeatures);
}
public PermissionManagerServiceImpl(Context context,ArrayMap<String,FeatureInfo> availableFeatures) {
 //...
    SystemConfig systemConfig = SystemConfig.getInstance();
    mSystemPermissions = systemConfig.getSystemPermissions();
    mGlobalGids = systemConfig.getGlobalGids();
}
//PMS.java 构造函数
SystemConfig systemConfig = injector.getSystemConfig();
mAvailableFeatures = systemConfig.getAvailableFeatures();
ArrayMap<String, SystemConfig.SharedLibraryEntry> libConfig
    = systemConfig.getSharedLibraries();
//Settings.java
public int[] getGlobalGids() {
    return mGlobalGids;
}
public SparseArray<ArraySet<String>> getSystemPermissions() {
    return mSystemPermissions;
}
public ArrayMap<String, FeatureInfo> getAvailableFeatures() {
    return mAvailableFeatures;
}
```

我们来看下xml的数据结构

> etc/permissions/platform.xml

```xml
<!-- This file is used to define the mappings between lower-level system
     user and group IDs and the higher-level permission names managed
     by the platform.
-->
<permissions>
    <permission name="android.permission.READ_LOGS" >
        <group gid="log" />
    </permission>
    //给uid为shell的分配Internet权限
    <assign-permission name="android.permission.INTERNET" uid="shell" />
    //...
</permissions>
```

也可以给某个应用单独配置权限，以etc/permissions/`com.android.documentui`.xml为例

```xml
<permissions>
    <privapp-permissions package="com.android.documentsui">
        <permission name="android.permission.CHANGE_OVERLAY_PACKAGES"/>
        <permission name="android.permission.INTERACT_ACROSS_USERS"/>
        <!-- Permissions required for reading and logging compat changes -->
        <permission name="android.permission.LOG_COMPAT_CHANGE"/>
        <permission name="android.permission.MODIFY_QUIET_MODE"/>
        <permission name="android.permission.READ_COMPAT_CHANGE_CONFIG"/>
    </privapp-permissions>
</permissions>
```

platform.xml最上面英文说明已经解释了此配置文件是为了在gid和uid做了映射关系。可以暂时理解成给某些uid或者某些应用（应用创建的时候也会指定相应的uid）分配指定的权限。

#### 扫描启动流程

继续看PMS构造函数后面的流程，上面只是准备了Settings和SystemConfig，后面才是关键的步骤。

```java
public PackageManagerService(PackageManagerServiceInjector injector,...){
    //...
    //1 读packages.xml文件，首次启动没这个文件，mFirstBoot为true
    mFirstBoot = !mSettings.readLPw(computer, mInjector.getUserManagerInternal().getUsers(true,false,false));
    //对于首次启动，通知jni层做了些初始化和拷贝动作
    if (mFirstBoot) {
        mInstaller.setFirstBoot();
    }
    if (!mOnlyCore && mFirstBoot) {
        DexOptHelper.requestCopyPreoptedFiles();
    }
    //...
    //2 扫描系统应用和非系统应用
    PackageParser2 packageParser = mInjector.getScanningCachingPackageParser();
    mOverlayConfig = mInitAppsHelper.initSystemApps(packageParser, packageSettings, userIds,startTime);
    mInitAppsHelper.initNonSystemApps(packageParser, userIds, startTime);
    //...
    //3 写入packages.xml
    writeSettingsLPrTEMP();
}
```

+ 注释1处调用mSettings.readLPw读packages.xml或者packages-backup.xml文件，如果是首次启动，由于没有这个文件，直接返回false，取反赋值给mFirstBoot，首次启动的话会通知jni层做一些拷贝动作，非首次启动packages.xml或者packages-backup.xml至少有一个存在，此文件记录的是关机前所有应用的信息和权限
+ 注释2会去扫描所有的系统和非系统应用
+ 注释3重新写入packages.xml

这3个部分都看下。

##### Settings.readLPw

> frameworks/base/services/core/java/com/android/server/pm/Settings.java

```java
boolean readLPw(@NonNull Computer computer, @NonNull List<UserInfo> users) {
    FileInputStream str = null;
    //备份文件如果存在，就用备份文件，并把packages.xml删除，因为有备份文件，说明上次写入packages.xml的时候有异常中断了写入
    if (mBackupSettingsFilename.exists()) {
        str = new FileInputStream(mBackupSettingsFilename);
        if (mSettingsFilename.exists()) {
            mSettingsFilename.delete();
        }
    }

    //如果没有备份文件，先看是否有packages.xml，如果还没有，说明是首次启动，直接返回false
    if (str == null) {
        if (!mSettingsFilename.exists()) {
            return false;
        }
        str = new FileInputStream(mSettingsFilename);
    }
    //到这里要么读的是packages.xml，要么是packages-backup.xml
    final TypedXmlPullParser parser = Xml.resolvePullParser(str);

    //后面就是解析xml的过程
    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
           && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
        String tagName = parser.getName();
        if (tagName.equals("package")) {
            readPackageLPw(parser, users, originalFirstInstallTimes);
        } else if (tagName.equals("permissions")) {
            mPermissions.readPermissions(parser);
        } else if (tagName.equals("shared-user")) {
            readSharedUserLPw(parser, users);
        } 
        //...
    }
}
```

上面注释很清楚了，不赘述，我们以`package`字段为例，调用到readPackageLPw

```java
private void readPackageLPw(TypedXmlPullParser parser, List<UserInfo> users,ArrayMap<String, Long> originalFirstInstallTimes){
    //...
    //读了packages.xml里package节点里面的属性
    name = parser.getAttributeValue(null, ATTR_NAME);
    realName = parser.getAttributeValue(null, "realName");
    userId = parser.getAttributeInt(null, "userId", 0);
    sharedUserAppId = parser.getAttributeInt(null, "sharedUserId", 0);
    //...
    packageSetting = addPackageLPw(name.intern(), realName, new File(codePathStr),
                                   legacyNativeLibraryPathStr, primaryCpuAbiString, secondaryCpuAbiString,
                                   cpuAbiOverrideString, userId, versionCode, pkgFlags, pkgPrivateFlags,
                                   null , null ,null , null , null , domainSetId);
    
}

final WatchedArrayMap<String, PackageSetting> mPackages;
PackageSetting addPackageLPw(String name, String realName, File codePath...) {
    PackageSetting p = mPackages.get(name);
    if (p != null) {
        if (p.getAppId() == uid) {
            return p;
        }
        return null;
    }
    p = new PackageSetting(name, realName, codePath...);
    p.setAppId(uid);
    if (mAppIds.registerExistingAppId(uid, p, name)) {
        mPackages.put(name, p);
        return p;
    }
    return null;
}
```

对于每一个app，都构建一个PackageSetting并缓存起来，另外还会像注册共享用户id一样注册所有其他应用的uid。

##### 扫描系统和非系统应用

继续看这两个方法：mInitAppsHelper.initSystemApps和initNonSystemApps

>frameworks/base/services/core/java/com/android/server/pm/InitAppsHelper.java

先看扫描系统应用
```java
private static final File DIR_ANDROID_ROOT = getDirectory(ENV_ANDROID_ROOT, "/system");
public static @NonNull File getRootDirectory() {
    return DIR_ANDROID_ROOT;
}

public OverlayConfig initSystemApps(PackageParser2 packageParser...) {
    scanSystemDirs(packageParser, mExecutorService);
    if (!mIsOnlyCoreApps) { 
        //注意这里mPossiblyDeletedUpdatedSystemApps和mExpectingBetter
        mInstallPackageHelper.prepareSystemPackageCleanUp(packageSettings, mPossiblyDeletedUpdatedSystemApps, mExpectingBetter, userIds);
    }

}
private void scanSystemDirs(PackageParser2 packageParser, ExecutorService executorService) {
    File frameworkDir = new File(Environment.getRootDirectory(), "framework");

    for (int i = mDirsToScanAsSystem.size() - 1; i >= 0; i--) {
        final ScanPartition partition = mDirsToScanAsSystem.get(i);
        if (partition.getOverlayFolder() == null) {
            continue;
        }
        scanDirTracedLI(partition.getOverlayFolder()...);
    }

    scanDirTracedLI(frameworkDir, null,
                    mSystemParseFlags,
                    mSystemScanFlags | SCAN_NO_DEX | SCAN_AS_PRIVILEGED,
                    packageParser, executorService);

    for (int i = 0, size = mDirsToScanAsSystem.size(); i < size; i++) {
        final ScanPartition partition = mDirsToScanAsSystem.get(i);
        if (partition.getPrivAppFolder() != null) {
            scanDirTracedLI(partition.getPrivAppFolder()...);
        }
        scanDirTracedLI(partition.getAppFolder()...);
    }
}
```

可以看到先扫描了mDirsToScanAsSystem列表所有的overlay子目录，再扫了`system/framework`目录，最后又扫了mDirsToScanAsSystem列表所有的`priv-app`和`app`目录，mDirsToScanAsSystem是在InitAppsHelper初始化时传进来的，最初又是由注射器带进来的，忽略具体调用流程，只看这配置的目录。

> frameworks/base/core/java/com/android/server/pm/PackagePartitions.java

```java
public static final String PARTITION_NAME_SYSTEM = "system";
public static final String PARTITION_NAME_ODM = "odm";
public static final String PARTITION_NAME_OEM = "oem";
public static final String PARTITION_NAME_PRODUCT = "product";
public static final String PARTITION_NAME_SYSTEM_EXT = "system_ext";
public static final String PARTITION_NAME_VENDOR = "vendor";
private static final ArrayList<SystemPartition> SYSTEM_PARTITIONS =
    new ArrayList<>(Arrays.asList(
        new SystemPartition(Environment.getRootDirectory(),PARTITION_SYSTEM, Partition.PARTITION_NAME_SYSTEM,true , false),
        new SystemPartition(Environment.getVendorDirectory(),PARTITION_VENDOR, Partition.PARTITION_NAME_VENDOR,true , true),
        new SystemPartition(Environment.getOdmDirectory(),PARTITION_ODM, Partition.PARTITION_NAME_ODM,true, true ),
        new SystemPartition(Environment.getOemDirectory(),PARTITION_OEM, Partition.PARTITION_NAME_OEM,false , true ),
        new SystemPartition(Environment.getProductDirectory(),PARTITION_PRODUCT, Partition.PARTITION_NAME_PRODUCT,true , true ),
        new SystemPartition(Environment.getSystemExtDirectory(),PARTITION_SYSTEM_EXT, Partition.PARTITION_NAME_SYSTEM_EXT, true, true )));
```

即会扫描system,vendor,oem,odm,product,system_ext下的overlay，priv-app，app目录

再看扫描非系统应用

```java
public void initNonSystemApps(PackageParser2 packageParser...) {
    if (!mIsOnlyCoreApps) {
        scanDirTracedLI(mPm.getAppInstallDir(),null, 0,
                        mScanFlags | SCAN_REQUIRE_KNOWN,packageParser, mExecutorService);
    }
    //...
    //注意这里fixSystemPackages会去处理mExpectingBetter
    if (!mIsOnlyCoreApps) {
        fixSystemPackages(userIds);
    }
    mExpectingBetter.clear();
}

//PMS.java
mAppInstallDir = new File(Environment.getDataDirectory(), "app");
File getAppInstallDir() {
    return mAppInstallDir;
}

//Environment.java
private static final String DIR_ANDROID_DATA_PATH = getDirectoryPath(ENV_ANDROID_DATA, "/data");
private static final File DIR_ANDROID_DATA = new File(DIR_ANDROID_DATA_PATH);
public static File getDataDirectory() {
    return DIR_ANDROID_DATA;
}
```

非系统应用是扫描/data/app目录下所有的目录。

需要注意的是上面有两个列表关注:`possiblyDeletedUpdatedSystemApps`和`mExpectingBetter`

了解这2个概念，我们需要了解OTA升级。**OTA升级其实就是标准系统升级方式，全称是Over-the-Air Technology。OTA升级无需备份数据，所有数据都会完好无损的保留下来，这个概念在ROM开发中比较常见。**

对于一次OTA升级，会导致三种情况：

* 系统应用无更新
* 系统应用有新版本
* 系统应用被删除

扫描系统的时候调了mInstallPackageHelper.prepareSystemPackageCleanUp去设置possiblyDeletedUpdatedSystemApps和mExpectingBetter

```java
public void prepareSystemPackageCleanUp(
    WatchedArrayMap<String, PackageSetting> packageSettings,
    List<String> possiblyDeletedUpdatedSystemApps,
    ArrayMap<String, File> expectingBetter, int[] userIds) {
    // Iterates PackageSettings in reversed order because the item could be removed
    // during the iteration.
    for (int index = packageSettings.size() - 1; index >= 0; index--) {
        final PackageSetting ps = packageSettings.valueAt(index);
        final String packageName = ps.getPackageName();
		//只处理系统应用
        if (!ps.isSystem()) {
            continue;
        }

        /*
             * If the package is scanned, it's not erased.
             */
        final AndroidPackage scannedPkg = mPm.mPackages.get(packageName);
        final PackageSetting disabledPs =
            mPm.mSettings.getDisabledSystemPkgLPr(packageName);
        if (scannedPkg != null) {
            /*
                 * If the system app is both scanned and in the
                 * disabled packages list, then it must have been
                 * added via OTA. Remove it from the currently
                 * scanned package so the previously user-installed
                 * application can be scanned.
                 */
            if (disabledPs != null) {
                mRemovePackageHelper.removePackageLI(scannedPkg, true);
                expectingBetter.put(ps.getPackageName(), ps.getPath());
            }
            continue;
        }

        if (disabledPs == null) {
            mRemovePackageHelper.removePackageDataLIF(ps, userIds, null, 0, false);
        } else {
            //1 如果一个系统APP不复存在，且被标记为Disable状态，说明这个系统APP已经彻底不存在了，添加到possiblyDeletedUpdatedSystemApps删除列表
            if (disabledPs.getPath() == null || !disabledPs.getPath().exists()
                || disabledPs.getPkg() == null) {
                possiblyDeletedUpdatedSystemApps.add(packageName);
            } else {
                // 否则添加到expectingBetter
                expectingBetter.put(disabledPs.getPackageName(), disabledPs.getPath());
            }
        }
    }
}
```

扫描系统应用的时候会把要删除的系统应用放到possiblyDeletedUpdatedSystemApps，可能更新的放到expectingBetter缓存里。再看扫描非系统应用时调用fixSystemPackages，此时非系统应用已经扫描完毕

```java
private void fixSystemPackages(@NonNull int[] userIds) {
    //根据mPossiblyDeletedUpdatedSystemApps去删除系统应用
    mInstallPackageHelper.cleanupDisabledPackageSettings(mPossiblyDeletedUpdatedSystemApps, userIds, mScanFlags);
    //处理mExpectingBetter数据
    mInstallPackageHelper.checkExistingBetterPackages(mExpectingBetter,mStubSystemApps, mSystemScanFlags, mSystemParseFlags);
    mInstallPackageHelper.installSystemStubPackages(mStubSystemApps, mScanFlags);
}
public void cleanupDisabledPackageSettings(List<String> possiblyDeletedUpdatedSystemApps,int[] userIds, int scanFlags) {
    //遍历possiblyDeletedUpdatedSystemApps，通过mRemovePackageHelper.removePackageLI去移除要删除的系统应用
    for (int i = possiblyDeletedUpdatedSystemApps.size() - 1; i >= 0; --i) {
        final String packageName = possiblyDeletedUpdatedSystemApps.get(i);
        final AndroidPackage pkg = mPm.mPackages.get(packageName);
     	//...
        //settings里有，但是新的mPackages里没有，说明此系统应用已经删除，直接移除该应用
        final PackageSetting ps = mPm.mSettings.getPackageLPr(packageName);
        if (ps != null && mPm.mPackages.get(packageName) == null) {
            mRemovePackageHelper.removePackageDataLIF(ps, userIds, null, 0, false);
        }
    }
}
public void checkExistingBetterPackages(ArrayMap<String, File> expectingBetterPackages,
                                        List<String> stubSystemApps, int systemScanFlags, int systemParseFlags) {
    //遍历expectingBetterPackages
    for (int i = 0; i < expectingBetterPackages.size(); i++) {
        final String packageName = expectingBetterPackages.keyAt(i);
        if (mPm.mPackages.containsKey(packageName)) {
            continue;
        }
        final File scanFile = expectingBetterPackages.valueAt(i);
        //把系统应用enable
        mPm.mSettings.enableSystemPackageLPw(packageName);
        final AndroidPackage newPkg = scanSystemPackageTracedLI(
            scanFile, reparseFlags, rescanFlags, null);
        // We rescanned a stub, add it to the list of stubbed system packages
        if (newPkg.isStub()) {
            stubSystemApps.add(packageName);
        }
    }
```

其实就是扫系统前会把所有系统应用disable，再与ota升级的应用对比，如果新的已经不存在了，说明已经删除了，添加到待删除的应用列表，待扫描完非系统应用，再把要删除的系统应用删除，其他的系统应用重新enable，要更新的应用更新。

##### scanDirTracedLI

再继续看扫描过程，不管是系统应用还是非系统应用，最终都调用了InitAppsHelper.scanDirTracedLI去扫描相应目录。

```java
private void scanDirTracedLI(File scanDir...PackageParser2 packageParser...) {
    mInstallPackageHelper.installPackagesFromDir(scanDir...packageParser...);
}
```

> frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java

```java
public void installPackagesFromDir(File scanDir...) {
    final File[] files = scanDir.listFiles();
    ParallelPackageParser parallelPackageParser =
        new ParallelPackageParser(packageParser, executorService, frameworkSplits);
    int fileCount = 0;
    for (File file : files) {
        //1 遍历传过来的scanDir的每个子文件夹，通过异步的方式去扫描每个应用的AndroidManifest.xml文件，并把解析结果包装到ParseResult中
        parallelPackageParser.submit(file, parseFlags);
        fileCount++;
    }
    for (; fileCount > 0; fileCount--) {
        ParallelPackageParser.ParseResult parseResult = parallelPackageParser.take();
        //2 对1处解析完的结果，再做相应的处理，比如构建PackageSetting
        addForInitLI(parseResult.parsedPackage, parseFlags, scanFlags,null);
    }
}
```

+ 注释1处是android6.0之后加入的ParallelPackageParser，采用**线程池+阻塞队列**的方式来扫描每个应用的AndroidManifest.xml文件，并把解析结果包装到ParseResult中，后面再通过take拿出结果，如果结果还没拿到，就会阻塞当前线程。
+ 注释2把解析完的结果再做相应的处理，比如构建PackageSetting对象，每个apk都对应一个PackageSetting对象，而Settings会保存这些pkg与PackageSetting的映射关系，而这些映射关系最后都会序列化到/data/system/packages.xml中

我们详细看下注释1和注释2，先看parallelPackageParser.submit过程

> frameworks/base/services/core/java/com/android/server/pm/ParallelPackageParser.java

```java
private final BlockingQueue<ParseResult> mQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
public void submit(File scanFile, int parseFlags) {
    mExecutorService.submit(() -> {
        ParseResult pr = new ParseResult();
        pr.scanFile = scanFile;
        pr.parsedPackage = parsePackage(scanFile, parseFlags);
        mQueue.put(pr);
    });
}
protected ParsedPackage parsePackage(File scanFile, int parseFlags) {
    return mPackageParser.parsePackage(scanFile, parseFlags, true, mFrameworkSplits);
}
```

通过线程池去执行parsePackage，执行结果放到mQueue中，而BlockingQueue为阻塞队列

>frameworks/base/services/core/java/com/android/server/pm/parsing/PackageParser2.java

```java
public ParsedPackage parsePackage(File packageFile, int flags, boolean useCaches,List<File> frameworkSplits) {
	//...
    ParseResult<ParsingPackage> result = parsingUtils.parsePackage(input, packageFile, flags,frameworkSplits);
    ParsedPackage parsed = (ParsedPackage) result.getResult().hideAsParsed();
    return parsed;
}
```

> frameworks/base/services/core/java/com/android/server/pm/pkg/parsing/ParsingPackageUtils.java

```java
public ParseResult<ParsingPackage> parsePackage(ParseInput input, File packageFile, int flags,List<File> frameworkSplits) {
    if (((flags & PARSE_FRAMEWORK_RES_SPLITS) != 0)
        && frameworkSplits.size() > 0
        && packageFile.getAbsolutePath().endsWith("/framework-res.apk")) {
        return parseClusterPackage(input, packageFile, frameworkSplits, flags);
    } else if (packageFile.isDirectory()) {
        return parseClusterPackage(input, packageFile, /* frameworkSplits= */null, flags);
    } else {
        return parseMonolithicPackage(input, packageFile, flags);
    }
}
```

如果是文件夹就走parseClusterPackage()，否则走parseMonolithicPackage()。这里Cluster和Monolithic是android5.1之后有的概念，主要是支持APK拆分，一个大的APK可以拆分成多个独立的APK，这些拆分的APK有相同的签名，解析过程就是把这些小的APK组合成一个Package，原来单独的apk叫Monolithic，拆分后的APK叫Cluster。







































#### adb install流程

常见的安装场景有以下几种：

+ 命令行：adb install /push(系统应用)
+ 用户下载apk，通过系统内置的安装器packageinstall安装，有安装界面
+ 系统开机时安装系统应用
+ 应用商店自动安装

不管通过哪种方式，最终干活的都是PMS，这里对adb的方式比较好奇，先具体分析下`adb install`后的流程。

在windows平台执行adb install，其实是adb.exe install，这里调用到了adb模块的main方法

> packages/modules/adb/client/main.cpp

```c++
int main(int argc, char* argv[], char* envp[]) {
    return adb_commandline(argc - 1, const_cast<const char**>(argv + 1));
}
```

> packages/modules/adb/client/commandline.cpp

```cpp
int adb_commandline(int argc, const char** argv) {
	//...
    if (!strcmp(argv[0], "devices")) {
        //...
    }else if (!strcmp(argv[0], "shell")) {
        return adb_shell(argc, argv);
    }else if (!strcmp(argv[0], "install")) {
        if (argc < 2) error_exit("install requires an argument");
        return install_app(argc, argv);
    } 
    //...
}
```

原来我们平常输入的adb devices/shell/install其实就是走的这里。继续看install_app

>packages/modules/adb/client/adb_install.cpp

```c++
int install_app(int argc, const char** argv) {
    //...
    //这里会计算下需要用那种模式安装，非二进制的默认返回的是INSTALL_PUSH
    auto [primary_mode, fallback_mode] =
        calculate_install_mode(install_mode, use_fastdeploy, incremental_request);
    switch (install_mode) {
        case INSTALL_PUSH:
            return install_app_legacy(passthrough_argv.size(), passthrough_argv.data(),use_fastdeploy);
            //....
    }
}

static int install_app_legacy(int argc, const char** argv, bool use_fastdeploy){
    //....
    if (do_sync_push(apk_file, apk_dest.c_str(), false, CompressionType::Any, false)) {
        result = pm_command(argc, argv);
        delete_device_file(apk_dest);
    }
    return result;
}
static int pm_command(int argc, const char** argv) {
    std::string cmd = "pm";
    while (argc-- > 0) {
        cmd += " " + escape_arg(*argv++);
    }
    return send_shell_command(cmd);
}
```

先通过do_sync_push把源目录push到目标目录，再通过pm_command去执行相关命令，这里看到传的字符串是"pm ..."，这里又调回commandline.send_shell_command

> packages/modules/adb/client/commandline.cpp

```cpp
int send_shell_command(const std::string& command, bool disable_shell_protocol,
                       StandardStreamsCallbackInterface* callback) {
    //...
    while (true) {
        if (attempt_connection) {
            std::string error;
            //拼接了一条在server执行的shell执行
            std::string service_string = ShellServiceString(use_shell_protocol, "", command);
            //把该指令通过socket通信发送到服务端
            fd.reset(adb_connect(service_string, &error));
            if (fd >= 0) {
                break;
            }
        }
        if (!wait_for_device("wait-for-device")) {
            return 1;
        }
    }
}
```

先通过ShellServiceString拼接一条在服务端执行的shell指令，再通过socket通信发送到服务端

>packages/modules/adb/client/adb_client.cpp

```c++
int adb_connect(std::string_view service, std::string* error) {
    return adb_connect(nullptr, service, error);
}
int adb_connect(TransportId* transport, std::string_view service, std::string* error,bool force_switch_device) {
    //...
    unique_fd fd(_adb_connect(service, transport, error, force_switch_device));
    //...
}
static int _adb_connect(std::string_view service, TransportId* transport, std::string* error,bool force_switch = false) {
    //...
    std::optional<TransportId> transport_result = switch_socket_transport(fd.get(), error);
    //...
}
static int _adb_connect(std::string_view service, TransportId* transport, std::string* error,bool force_switch = false) {
    //...
    if (!SendProtocolString(fd.get(), service)) {
        *error = perror_str("write failure during connection");
        return -1;
    }
    //...
}
```

看到这里就不再往socket通信更深里看了。这里SendProtocolString传的是个文件描述符，即前面传的pm字符串，其实这里传的是/frameworks/base/cmds/pm/pm文件

>/frameworks/base/cmds/pm/pm

```cpp
#!/system/bin/sh
cmd package "$@"
```

这里的服务端即shell进程，会执行该cmd package指令，调用其cmd.main入口方法，注意这里已经由adb进程进入到shell进程。

> frameworks/native/cmds/cms/main.cpp

```cpp
int main(int argc, char* const argv[]) {
    //...
    return cmdMain(arguments, android::aout, android::aerr, STDIN_FILENO, STDOUT_FILENO,STDERR_FILENO, RunMode::kStandalone);
}
```

>frameworks/native/cmds/cms/cmd.cpp

```c++
int cmdMain(const std::vector<std::string_view>& argv, TextOutput& outputLog, TextOutput& errorLog, int in, int out, int err, RunMode runMode) {
    //...
    sp<IServiceManager> sm = defaultServiceManager();
    sp<IBinder> service;
    service = sm->checkService(serviceName);
    status_t error = IBinder::shellCommand(service, in, out, err, args, cb, result);
    //...
}
```

这里先获取serviceManager，再调用checkService去获取相应的服务，这里由于传的是package，所以是获取了PMS，调用binder的shellCommand，这个service即PMS的代理对象

>frameworks/native/libs/binder/Binder.cpp

```c++
status_t IBinder::shellCommand(const sp<IBinder>& target, int in, int out, int err,Vector<String16>& args, const sp<IShellCallback>& callback,const sp<IResultReceiver>& resultReceiver)
{
	//...
    return target->transact(SHELL_COMMAND_TRANSACTION, send, &reply);
}
```

binder机制，shell客户端调用代理类的transact方法，服务端调用其对应的onTransact方法，这里会调用Binder.onTransact

> frameworks/base/core/java/android/os/Binder.java

```java
protected boolean onTransact(int code, @NonNull Parcel data, @Nullable Parcel reply,int flags) throws RemoteException {
    //...
    if (code == SHELL_COMMAND_TRANSACTION) {
        shellCommand(in != null ? in.getFileDescriptor() : null,
                     out.getFileDescriptor(),
                     err != null ? err.getFileDescriptor() : out.getFileDescriptor(),
                     args, shellCallback, resultReceiver);
    }
}
public void shellCommand(@Nullable FileDescriptor in, @Nullable FileDescriptor out,
                         @Nullable FileDescriptor err,
                         @NonNull String[] args, @Nullable ShellCallback callback,
                         @NonNull ResultReceiver resultReceiver) throws RemoteException {
    onShellCommand(in, out, err, args, callback, resultReceiver);
}
```

由于PMS重写了onShellCommand方法，这里调用到PMS.onShellCommand

> frameworks/base/services/core/java/com/android/server/pm/PackageManagerService.java

```java
@Override
public void onShellCommand(FileDescriptor in, FileDescriptor out,
                           FileDescriptor err, String[] args, ShellCallback callback,
                           ResultReceiver resultReceiver) {
    (new PackageManagerShellCommand(this, mContext,mDomainVerificationManager.getShell()))
    .exec(this, in, out, err, args, callback, resultReceiver);
}
```

new了个PackageManagerShellCommand，并执行其exec方法，这里会回调PackageManagerShellCommand.onCommand方法

> frameworks/base/services/core/java/com/android/server/pm/PackageManagerShellCommand.java

```java
public int onCommand(String cmd) {
    switch (cmd) {
        case "install":
            return runInstall();
        //...
    }
}
private int runInstall() throws RemoteException {
    return doRunInstall(makeInstallParams(UNSUPPORTED_INSTALL_CMD_OPTS));
}
private int doRunInstall(final InstallParams params) throws RemoteException{
	//...
    final int sessionId = doCreateSession(params.sessionParams,params.installerPackageName,params.userId);
    if (doCommitSession(sessionId, false /*logSuccess*/) != PackageInstaller.STATUS_SUCCESS) {
        return 1;
    }
    //...
}
```

这里通过doCreateSession和doCommitSession跟PackageInstall交互，通过PackageInstall去真正走安装流程。这个我们在PMS安装流程里具体看。

总的来说，adb install命令会先在adb进程构建服务端执行的shell语句后通过socket传给shell进程执行，shell进程执行cmd package 指令后获取指定的服务，比如PMS，并通过binder机制调用其shellCommand，最终再根据传的adb后面的参数，比如install/uninstall执行相应的安装/卸载。