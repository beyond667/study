#### 前言

PMS是android系统很重要的一个系统服务，主要负责管理应用的安装，卸载，更新，查询，权限管理。我们在四大组件的启动流程中都看到PMS的身影，比如通过Intent启动另一个应用时都会先通过PMS去获取该应用的PackageInfo信息。本文主要从两个方面分析PMS：PMS安装APP流程，PMS的启动和使用流程。代码基于Android13。

#### PMS启动流程

在[开机流程](https://github.com/beyond667/study/blob/master/note/%E5%BC%80%E6%9C%BA%E6%B5%81%E7%A8%8B.md)中已经分析过，PMS是在`SystemServer`的`startBootstrapServices()`里启动的，本文只关注跟PMS相关的。

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

我们详细看下注释1和注释2，先看注释1的parallelPackageParser.submit的完整解析过程

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
    //特别注意：这里做了转换
    ParsedPackage parsed = (ParsedPackage) result.getResult().hideAsParsed();
    return parsed;
}
```
需要特别注意的是result包的是ParsingPackage，这里对扫描后的结果ParsingPackage强转成ParsedPackage，能强转说明扫描的时候生成的实现类也实现了ParsedPackage 
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

如果是文件夹就走parseClusterPackage()，否则走parseMonolithicPackage()。这里Cluster和Monolithic是android5.1之后有的概念，主要是支持APK拆分，一个大的APK可以拆分成多个独立的APK，这些拆分的APK有相同的签名，解析过程就是把这些小的APK组合成一个Package，原来单独的apk叫Monolithic，拆分后的APK叫Cluster。对于Cluster的解析，也是遍历其文件夹，如果子文件夹还是文件夹，继续遍历，直到把所有apk都找到并解析。两者最后都是通过parseApkLite去解析AndroidManifest文件的拆分包信息和parseBaseApk去解析四大组件等完整基本信息。我们只看parseMonolithicPackage即可

```java
private ParseResult<ParsingPackage> parseMonolithicPackage(ParseInput input, File apkFile,int flags) {
    //1 去解析分包信息
    final ParseResult<PackageLite> liteResult =
        ApkLiteParseUtils.parseMonolithicPackageLite(input, apkFile, flags);
    final PackageLite lite = liteResult.getResult();
    final SplitAssetLoader assetLoader = new DefaultSplitAssetLoader(lite, flags);
    //2 parseBaseApk解析四大组件等
    final ParseResult<ParsingPackage> result = parseBaseApk(input, apkFile,apkFile.getCanonicalPath(),assetLoader, flags);
    return input.success(result.getResult().setUse32BitAbi(lite.isUse32bitAbi()));
}
public static ParseResult<PackageLite> parseMonolithicPackageLite(ParseInput input,File packageFile, int flags) {
    final ParseResult<ApkLite> result = parseApkLite(input, packageFile, flags);
    final ApkLite baseApk = result.getResult();
    final String packagePath = packageFile.getAbsolutePath();
    return input.success(
        new PackageLite(packagePath, baseApk.getPath(), baseApk...));
}
public static ParseResult<ApkLite> parseApkLite(ParseInput input, FileDescriptor fd, String debugPathName, int flags) {
    return parseApkLiteInner(input, null, fd, debugPathName, flags);
}
private static ParseResult<ApkLite> parseApkLiteInner(ParseInput input,
                                                      File apkFile, FileDescriptor fd, String debugPathName, int flags) {
    XmlResourceParser parser = null;
    //打开AndroidManifest.xml
    parser = apkAssets.openXml(ANDROID_MANIFEST_FILENAME);
    return parseApkLite(input, apkPath, parser, signingDetails, flags);
}
private static ParseResult<ApkLite> parseApkLite(ParseInput input, String codePath,
                                                 XmlResourceParser parser, SigningDetails signingDetails, int flags){
    //...
    //这里解析了些分包信息
    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
           && (type != XmlPullParser.END_TAG || parser.getDepth() >= searchDepth)) {
        if (TAG_PACKAGE_VERIFIER.equals(parser.getName())) {...}
        else if (TAG_APPLICATION.equals(parser.getName())) {...}

    }
    return input.success(
        new ApkLite(codePath, packageSplit.first, packageSplit.second, isFeatureSplit,
                    configForSplit, usesSplitName, isSplitRequired, versionCode,
                    versionCodeMajor, revisionCode, installLocation, verifiers, signingDetails,
                    coreApp, debuggable, profilableByShell, multiArch, use32bitAbi,
                    useEmbeddedDex, extractNativeLibs, isolatedSplits, targetPackage,
                    overlayIsStatic, overlayPriority, requiredSystemPropertyName,
                    requiredSystemPropertyValue, minSdkVersion, targetSdkVersion,
                    rollbackDataPolicy, requiredSplitTypes.first, requiredSplitTypes.second,
                    hasDeviceAdminReceiver, isSdkLibrary));
}
```

+ 注释1最终通过parseApkLite去解析AndroidManifest.xml的拆分包信息，new的ApkLite对象封装后返回
+ 注释2会解析完整的应用信息，我们继续看

```java
private ParseResult<ParsingPackage> parseBaseApk(ParseInput input, File apkFile,
                                                 String codePath, SplitAssetLoader assetLoader, int flags) {
    try (XmlResourceParser parser = assets.openXmlResourceParser(cookie,ANDROID_MANIFEST_FILENAME)) {
        ParseResult<ParsingPackage> result = parseBaseApk(input, apkPath, codePath, res,parser, flags);
        final ParsingPackage pkg = result.getResult();
        //设置uuid和签名等
        pkg.setVolumeUuid(volumeUuid);
        pkg.setSigningDetails(ret.getResult());
        return input.success(pkg);
    }
}
private ParseResult<ParsingPackage> parseBaseApk(ParseInput input, String apkPath,
                                                 String codePath, Resources res, XmlResourceParser parser, int flags){
    //...
    final ParsingPackage pkg = mCallback.startParsingPackage(
        pkgName, apkPath, codePath, manifestArray, isCoreApp);
    final ParseResult<ParsingPackage> result =
        parseBaseApkTags(input, pkg, manifestArray, res, parser, flags);
    return input.success(pkg);
}
//PackageParser2.java的内部类Callback：start
public static abstract class Callback implements ParsingPackageUtils.Callback {

    @Override
    public final ParsingPackage startParsingPackage(@NonNull String packageName,
                                                    @NonNull String baseCodePath, @NonNull String codePath,
                                                    @NonNull TypedArray manifestArray, boolean isCoreApp) {
        return PackageImpl.forParsing(packageName, baseCodePath, codePath, manifestArray,
                                      isCoreApp);
    }
}
//PackageParser2.java的内部类Callback：end
//PackageImpl.java:start
//PackageImpl实现了ParsedPackage，继承的ParsingPackageImpl也实现了ParsingPackage
//所以PackageImpl既可以强转成ParsedPackage,也可以强转为ParsingPackage
public class PackageImpl extends ParsingPackageImpl implements ParsedPackage{
    public static PackageImpl forParsing(String packageName, String baseCodePath,String codePath...) {
        return new PackageImpl(packageName, baseCodePath, codePath, manifestArray, isCoreApp);
    }
}
//PackageImpl.java:end

private ParseResult<ParsingPackage> parseBaseApkTags(ParseInput input, ParsingPackage pkg,
                                                     TypedArray sa, Resources res, XmlResourceParser parser, int flags){
    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
           && (type != XmlPullParser.END_TAG
               || parser.getDepth() > depth)) {
        if (TAG_APPLICATION.equals(tagName)) {
            result = parseBaseApplication(input, pkg, res, parser, flags);
        }
    }
    //...
}
private ParseResult<ParsingPackage> parseBaseApplication(ParseInput input, ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags){
    //解析了application里所有基本信息，比如debugable，enable,allowBackup，icon，logo，theme
    parseBaseAppBasicFlags(pkg, sa);
    while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
           && (type != XmlPullParser.END_TAG
               || parser.getDepth() > depth)) {
        switch (tagName) {
            case "activity": isActivity = true;
            case "receiver":
                ParseResult<ParsedActivity> activityResult =
                    ParsedActivityUtils.parseActivityOrReceiver(mSeparateProcesses, pkg,res, parser, flags, sUseRoundIcon, null /*defaultSplitName*/,input);
                break;
            case "service":
                ParseResult<ParsedService> serviceResult =
                    ParsedServiceUtils.parseService(mSeparateProcesses, pkg, res, parser,
                                                    flags, sUseRoundIcon, null /*defaultSplitName*/,
                                                    input);
                break;
                //provider类似
        }
        return input.success(pkg);
    }
```

可以看到真正完整解析AndroidManifest是在parseBaseApplication里，除了解析icon，logo外，专门解析了四大组件，并把解析的结果返回。

注意的是，返回的pkg是ParsingPackage，是在解析前通过startParsingPackage这里new出来的PackageImpl，而PackageImpl除了继承ParsingPackageImpl（实现了ParsingPackage和Parcelable），又是实现了ParsedPackage接口（名字起的太有误导性了，不过PackageImpl确实比较特殊，既要作为解析包的实现类，又要作为Parcelable来存解析后的数据，后面又要转换成ParsedPackage去映射PackageSetting）

##### addForInitLI

上面注释1的完整解析过程后（安装过程的第一个阶段--准备阶段），继续看注释2的addForInitLI根据解析的结果ParsedPackage来构建PackageSetting

> frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java

```java
private AndroidPackage addForInitLI(ParsedPackage parsedPackage,int parseFlags,int scanFlags,UserHandle user)  {
    //1 scan阶段
    final Pair<ScanResult, Boolean> scanResultPair = scanSystemPackageLI(parsedPackage, parseFlags, scanFlags, user);
    //...
    //根据1扫描的结果构建ReconcileRequest
    final ScanResult scanResult = scanResultPair.first;
    final ReconcileRequest reconcileRequest = new ReconcileRequest(Collections.singletonMap(pkgName, scanResult)...);
    //2 reconcile阶段
    final Map<String, ReconciledPackage> reconcileResult =
        ReconcilePackageUtils.reconcilePackages(reconcileRequest,mSharedLibraries, mPm.mSettings.getKeySetManagerService(),mPm.mSettings);
    //3 commit阶段 这里会把扫描PackageSetting真正添加到Settings里
    commitReconciledScanResultLocked(reconcileResult.get(pkgName), mPm.mUserManager.getUserIds());
}
```
+ 注释1是安装过程的扫描阶段
+ 注释2是安装过程的reconcile阶段，reconcile是调和，协调的意思
+ 注释3是安装过程的commit阶段，这里会把扫描PackageSetting真正添加到Settings里

先看注释1扫描阶段

> frameworks/base/services/core/java/com/android/server/pm/ScanPackageUtils.java

```java
private Pair<ScanResult, Boolean> scanSystemPackageLI(ParsedPackage parsedPackage...){
    //...省略校验过程
    final ScanResult scanResult = scanPackageNewLI(parsedPackage, parseFlags,scanFlags | SCAN_UPDATE_SIGNATURE, 0, user, null);
    return new Pair<>(scanResult, shouldHideSystemApp);
}
private ScanResult scanPackageNewLI(ParsedPackage parsedPackage...){
    //...
    synchronized (mPm.mLock) {
        assertPackageIsValid(parsedPackage, parseFlags, newScanFlags);
        final ScanRequest request = new ScanRequest(parsedPackage...);
        return ScanPackageUtils.scanPackageOnlyLI(request, mPm.mInjector, mPm.mFactoryTest, currentTime);
    }
}
public static ScanResult scanPackageOnlyLI(ScanRequest request,PackageManagerServiceInjector injector...){
    PackageSetting pkgSetting = request.mPkgSetting;
    if (pkgSetting != null && oldSharedUserSetting != sharedUserSetting) {
        pkgSetting = null;
    }
    final boolean createNewPackage = (pkgSetting == null);
   //根据sharedUserid判断是否要创建新的PackageSetting，不需要创建的话就更新
    if (createNewPackage) {
        pkgSetting = Settings.createNewSetting(parsedPackage.getPackageName()...);
    }else{
        pkgSetting = new PackageSetting(pkgSetting);
        pkgSetting.setPkg(parsedPackage);
        Settings.updatePackageSetting(pkgSetting...);
    }
    //... 再对pkgSetting进行一些设置
    pkgSetting.setLastModifiedTime(scanFileTime);
    pkgSetting.setPkg(parsedPackage)
        .setPkgFlags(PackageInfoUtils.appInfoFlags(...));
    return new ScanResult(request, true, pkgSetting, changedAbiCodePath,
                          !createNewPackage ,
                          Process.INVALID_UID  , sdkLibraryInfo,
                          staticSharedLibraryInfo, dynamicSharedLibraryInfos);
}
```

扫描阶段主要是构建PackageSetting。这里会根据sharedUserSetting去判断是否需要创建PackageSetting，需要的话就通过Settings.createNewSetting去创建，否则就直接根据已有的PackageSetting去重新构建新的PackageSetting，再通过Settings.updatePackageSetting去更新，两者其实都是构建或者更新PackageSetting对象，但是此时还未绑定到Settings里，虽然这里调用了Settings的方法，但实际上还未添加到Settings的缓存对象**mPackages**里。

再看注释2的reconcile阶段

> frameworks/base/services/core/java/com/android/server/pm/ReconcilePackageUtils.java

```java
public static Map<String, ReconciledPackage> reconcilePackages(ReconcileRequest request...){
    final Map<String, ScanResult> scannedPackages = request.mScannedPackages;
    final Map<String, ReconciledPackage> result = new ArrayMap<>(scannedPackages.size());
    final ArrayMap<String, AndroidPackage> combinedPackages =
        new ArrayMap<>(request.mAllPackages.size() + scannedPackages.size());
    combinedPackages.putAll(request.mAllPackages);
    
    for (String installPackageName : scannedPackages.keySet()) {
        final ScanResult scanResult = scannedPackages.get(installPackageName);
        // add / replace existing with incoming packages
        combinedPackages.put(scanResult.mPkgSetting.getPackageName(), scanResult.mRequest.mParsedPackage);

        //...
        //如果是更新，构建个删除旧包的action
        final DeletePackageAction deletePackageAction;
        if (isInstall && prepareResult.mReplace && !prepareResult.mSystem) {
            deletePackageAction = DeletePackageHelper.mayDeletePackageLocked(res.mRemovedInfo,);
            if (deletePackageAction == null) {
                throw new ReconcileFailure("May not delete ");
            }
        } else {
            deletePackageAction = null;
        }
        //...省略检查包的其他信息，比如验证签名,数据库版本
        
        result.put(installPackageName,new ReconciledPackage(request...deletePackageAction...));
    }
    
    return result;
}
```

reconcile阶段主要是在commit之前再验证下要安装的包的信息，以保证安装能成功，另外如果是更新应用，会构建个删除包的action塞到result中，commit时会去执行删除旧包操作（这里由于是开机启动所以没有删除包的操作，正常安装更新时调用commitPackagesLocked会删除旧包）

继续看注释3的commitReconciledScanResultLocked真正添加到Settings里

```java
public AndroidPackage commitReconciledScanResultLocked(ReconciledPackage reconciledPkg, int[] allUsers) {
    //...
    //把扫描的结果转化成AndroidPackage
    final AndroidPackage pkg = parsedPackage.hideAsFinal();
    commitPackageSettings(pkg, oldPkg, pkgSetting, oldPkgSetting, scanFlags,
                          (parseFlags & ParsingPackageUtils.PARSE_CHATTY) != 0, reconciledPkg);
    return pkg;
}
private void commitPackageSettings(AndroidPackage pkg...) {
    //...
    synchronized (mPm.mLock) {
        // We don't expect installation to fail beyond this point
        // Add the new setting to mSettings
        mPm.mSettings.insertPackageSettingLPw(pkgSetting, pkg);
        mPm.mPackages.put(pkg.getPackageName(), pkg);
        
        //存进pms的mComponentResolver里
        final Computer snapshot = mPm.snapshotComputer();
        mPm.mComponentResolver.addAllComponents(pkg, chatty, mPm.mSetupWizardPackage, snapshot);
    }
    //...
}
```

除了往settings里添加，pms里也缓存了此扫描结果，还在pms的mComponentResolver缓存了信息，这个在后面模糊匹配查找应用会用到此Resolver

```java
void insertPackageSettingLPw(PackageSetting p, AndroidPackage pkg) {
    //如果签名为空就更新签名，如果shareduserSetting不同也更新
    // Update signatures if needed.
    if (p.getSigningDetails().getSignatures() == null) {
        p.setSigningDetails(pkg.getSigningDetails());
    }
    // If this app defines a shared user id initialize
    // the shared user signatures as well.
    SharedUserSetting sharedUserSetting = getSharedUserSettingLPr(p);
    if (sharedUserSetting != null) {
        if (sharedUserSetting.signatures.mSigningDetails.getSignatures() == null) {
            sharedUserSetting.signatures.mSigningDetails = pkg.getSigningDetails();
        }
    }
    addPackageSettingLPw(p, sharedUserSetting);
}
void addPackageSettingLPw(PackageSetting p, SharedUserSetting sharedUser) {
    //添加到mPackages缓存的arraymap中
    mPackages.put(p.getPackageName(), p);
    //略，更新shareduser
}
```

到这里就添加到settings的mPackages缓存里了。以上就是整个扫描解析AndroidManifest.xml并最终将PackageSetting缓存在Setting。下面就是把有可能的更新数据通过Settings.writeLPr写入packages.xml的过程了。

#### writeLPr

```java
//PMS构造函数调用
 writeSettingsLPrTEMP();

void writeSettingsLPrTEMP() {
    mPermissionManager.writeLegacyPermissionsTEMP(mSettings.mPermissions);
    mSettings.writeLPr(mLiveComputer);
}
```

直接调用Settings.writeLPr

```java
void writeLPr(@NonNull Computer computer) {
    //1 如果/data/system/packages.xml文件存在
    if (mSettingsFilename.exists()) {
        if (!mBackupSettingsFilename.exists()) {
            if (!mSettingsFilename.renameTo(mBackupSettingsFilename)) {
                return;
            }
        } else {
            mSettingsFilename.delete();
        }
    }
    try {
        final FileOutputStream fstr = new FileOutputStream(mSettingsFilename);
        final TypedXmlSerializer serializer = Xml.resolveSerializer(fstr);
        serializer.startDocument(null, true);
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
        serializer.startTag(null, "packages");

		//2 遍历已经缓存的mPackages，通过writePackageLPr写入packages.xml
        for (final PackageSetting pkg : mPackages.values()) {
            writePackageLPr(serializer, pkg);
        }

        for (final PackageSetting pkg : mDisabledSysPackages.values()) {
            writeDisabledSysPackageLPr(serializer, pkg);
        }
        //...
        serializer.endTag(null, "packages");
        serializer.endDocument();
        fstr.flush();
        FileUtils.sync(fstr);
        fstr.close();

        //3 写入成功，删除备份文件
        mBackupSettingsFilename.delete();
        return;

    } catch(java.io.IOException e) {}
    //4 写入过程有异常，就删了packages.xml文件，保留备份文件
    if (mSettingsFilename.exists()) {
        if (!mSettingsFilename.delete()) {
        }
    }
}
void writePackageLPr(TypedXmlSerializer serializer, final PackageSetting pkg){
    serializer.startTag(null, "package");
    serializer.attribute(null, ATTR_NAME, pkg.getPackageName());
    serializer.attributeLong(null, "version", pkg.getVersionCode());
    if (!pkg.hasSharedUser()) {
        serializer.attributeInt(null, "userId", pkg.getAppId());
    } else {
        serializer.attributeInt(null, "sharedUserId", pkg.getAppId());
    }
    //...
    serializer.endTag(null, "package");
}
```

写入过程较简单。

+ 注释1 先判断packages.xml是否存在，如果不存在，说明是首次启动或者上次写入packages.xml时有异常把此文件删除了，就直接写入packages.xml；如果存在，再去检查备份文件是否存在，如果有备份文件，说明上次更新packages.xml有异常，并且packages.xml没删除成功，直接删掉package.xml再写入此文件，如果备份文件不存在，就把packages.xml重命名为packages-backup.xml，再写入packages.xml
+ 注释2遍历已经缓存的mPackages，通过writePackageLPr写入packages.xml，可以看到writePackageLPr写入的详细字段信息
+ 注释3和4 如果写入成功，直接删除备份文件，否则删除packages.xml文件

初看这块感觉逻辑不太对，假如有异常，packages.xml都删除了，此时还保留备份文件，再重启时判断packages.xml是否存在时，假如不存在，备份文件完全没有用到就直接写入packages.xml，难道备份文件存在的作用只是在packages.xml也存在时才有用？（比如正在写入package.xml时断电或者删除packages.xml不成功等异常，导致此时存在packages.xml和备份文件都存在），这种场景下也只是删除了packages.xml，没看出备份文件存在的必要性，或者随便写入某个文件记录下即可。要回答这问题要看前面readLPw的过程，读的时候先看是否有备份文件，有的话就用备份并把packages.xml删除，没有的话再看是否有packages.xml文件，如果有就用，没有就认为是首次启动，也就是说读的时候会用到备份文件，否则无法还原更改packages.xml前的场景。

写入完成后PMS启动流程的大部分都已经完成了，后面SystemServer.startOtherServices会执行PMS.systemReady方法，这里会执行关联服务的systemReady，至此PMS启动流程就结束了。

#### PMS查询流程

使用场景比如在launcher显示应用列表，或者点击某个icon去启动某个应用，或者就是查询某个包的信息。

```java
//app查询本机所有应用信息
PackageManager pm = mContext.getPackageManager();
Intent intent = new Intent();
intent.setAction(Intent.ACTION_MAIN);
intent.addCategory(Intent.CATEGORY_LAUNCHER);
List<ResolveInfo> list =  pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);

//获取某个apk的启动intent
Intent launchIntent = packageManager.getLaunchIntentForPackage("com.android.launcher3");
mContext.startActivity(launchIntent);
//查询某个apk的包信息
PackageInfo packageInfo = packageManager.getPackageInfo("com.android.launcher3", 0);
```

先看getPackageManager返回的PackageManager

> frameworks/base/core/java/android/app/ContextImpl.java

```java
private PackageManager mPackageManager;    
public PackageManager getPackageManager() {
    if (mPackageManager != null) {
        return mPackageManager;
    }
    //这里拿到PMS的代理类IPackageManager
    final IPackageManager pm = ActivityThread.getPackageManager();
    if (pm != null) {
        //把代理类IPackageManager封装到ApplicationPackageManager返回
        return (mPackageManager = new ApplicationPackageManager(this, pm));
    }
    return null;
}
```

客户端拿到的PackageManager其实是封装了IPackageManager的本地类ApplicationPackageManager，所以客户端调用queryIntentActivities，getLaunchIntentForPackage，getPackageInfo是调用的ApplicationPackageManager的方法

> frameworks/base/core/java/android/app/ApplicationPackageManager.java

```java
public List<ResolveInfo> queryIntentActivities(Intent intent, int flags) {
    return queryIntentActivities(intent, ResolveInfoFlags.of(flags));
}
public List<ResolveInfo> queryIntentActivities(Intent intent, ResolveInfoFlags flags) {
    return queryIntentActivitiesAsUser(intent, flags, getUserId());
}
public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, int flags, int userId) {
    return queryIntentActivitiesAsUser(intent, ResolveInfoFlags.of(flags), userId);
}
public List<ResolveInfo> queryIntentActivitiesAsUser(Intent intent, ResolveInfoFlags flags, int userId) {
    ParceledListSlice<ResolveInfo> parceledList = mPM.queryIntentActivities(intent...);
    if (parceledList == null) {
        return Collections.emptyList();
    }
    return parceledList.getList();
}
```

套娃式的调用到PMS.queryIntentActivities，PMS的内部binder类IPackageManagerImpl没实现此方法，是在父类IPackageManagerBase实现的

>frameworks/base/services/core/java/com/android/server/pm/IPackageManagerBase.java

```java
ParceledListSlice<ResolveInfo> queryIntentActivities(Intent intent...) {
    return new ParceledListSlice<>(snapshot().queryIntentActivitiesInternal(intent,resolvedType, flags, userId));
}
protected Computer snapshot() {
    return mService.snapshotComputer();
}
```

调用到ComputerEngine.queryIntentActivitiesInternal

> frameworks/base/services/core/java/com/android/server/pm/ComputerEngine.java

```java
public final List<ResolveInfo> queryIntentActivitiesInternal(Intent intent...) {
    return queryIntentActivitiesInternal(...);
}
public final List<ResolveInfo> queryIntentActivitiesInternal(Intent intent,...) {
    ComponentName comp = intent.getComponent();
    List<ResolveInfo> list = Collections.emptyList();
   	//对于查的是某一个应用，只查询一个即可，所以ArrayList直接申请大小为1就行
    if (comp != null) {
        final ActivityInfo ai = getActivityInfo(comp, flags, userId);
        //...
        final ResolveInfo ri = new ResolveInfo();
        ri.activityInfo = ai;
        list = new ArrayList<>(1);
        list.add(ri);
    }else{
        //对于查的是所有应用，调用queryIntentActivitiesInternalBody把结果封装到QueryIntentActivitiesResult
        QueryIntentActivitiesResult lockedResult = queryIntentActivitiesInternalBody(...);
        if (lockedResult.answer != null) {
            skipPostResolution = true;
            list = lockedResult.answer;
        } 
    }
    //如果skipPostResolution为true，直接返回list
     return skipPostResolution ? list : applyPostResolutionFilter(list...);
}
```

这里根据传进来的ComponentName是否为空来判断是要查一个应用还是所有应用。对于所有应用走queryIntentActivitiesInternalBody，对于一个应用走getActivityInfo，我们分别来看下，先看getActivityInfo。

```java
public final ActivityInfo getActivityInfo(ComponentName component...) {
    return getActivityInfoInternal(component, flags, Binder.getCallingUid(), userId);
}
public final ActivityInfo getActivityInfoInternal(ComponentName component,long flags, int filterCallingUid, int userId) {
    //省略校验userid和权限
    return getActivityInfoInternalBody(component, flags, filterCallingUid, userId);
}
protected ActivityInfo getActivityInfoInternalBody(ComponentName component,long flags, int filterCallingUid, int userId) {
    ParsedActivity a = mComponentResolver.getActivity(component);
    //1 假设设备已经安装了此应用，这时候a不为空，就从mPackages来拿应用的信息AndroidPackage
    AndroidPackage pkg = a == null ? null : mPackages.get(a.getPackageName());
    if (pkg != null && mSettings.isEnabledAndMatch(pkg, a, flags, userId)) {
        PackageStateInternal ps = mSettings.getPackage(component.getPackageName());
        if (ps == null) return null;
        //2 根据查到的pkg去构建activityInfo返回
        return PackageInfoUtils.generateActivityInfo(pkg,a, flags, ps.getUserStateOrDefault(userId), userId, ps);
    }
    
    if (resolveComponentName().equals(component)) {
        return PackageInfoWithoutStateUtils.generateDelegateActivityInfo(mResolveActivity...);
    }
    return null;
}
```

+ 注释1从ComputerEngine缓存的mPackages里查找AndroidPackage
+ 注释2如果查到就构建ActivityInfo返回，构建过程可以理解成AndroidPackage的值赋值给ActivityInfo，不再赘述

注意的是ComputerEngine里面的mPackages本质也是PMS里的mPackages，我们看下其赋值过程

```java
//PMS.java
final WatchedArrayMap<String, AndroidPackage> mPackages = new WatchedArrayMap<>();
private final SnapshotCache<WatchedArrayMap<String, AndroidPackage>> mPackagesSnapshot = new SnapshotCache.Auto(mPackages, mPackages, "PackageManagerService.mPackages");

//PMS.java构造函数最后
public PackageManagerService(){
    //...
    mLiveComputer = createLiveComputer();
}
private ComputerLocked createLiveComputer() {
    return new ComputerLocked(new Snapshot(Snapshot.LIVE));
}

//PMS的内部类Snapshot
class Snapshot {
    public final WatchedArrayMap<String, AndroidPackage> packages;
    Snapshot(int type) {
        //...
        //内部类Snapshot持有了PMS的Packages的快照
        packages = mPackagesSnapshot.snapshot();
    }
}
//以上都是PMS.java

//ComputerLocked.java
public final class ComputerLocked extends ComputerEngine {
    ComputerLocked(PackageManagerService.Snapshot args) {
        super(args, -1);
    }
}
//ComputerLocked的父类ComputerEngine
private final WatchedArrayMap<String, AndroidPackage> mPackages;
ComputerEngine(PackageManagerService.Snapshot args, int version) {
    mSettings = new Settings(args.settings);
    mPackages = args.packages;
    //...
}
```

可以看到ComputerEngine内部持有的是最新的settings和AndroidPackage

再看查询多个情况queryIntentActivitiesInternalBody

```java
public  QueryIntentActivitiesResult queryIntentActivitiesInternalBody( Intent intent...) {
    //...
    List<ResolveInfo> result = null;
    if (pkgName == null) {
        //1 从本地缓存的mComponentResolver查询ResolveInfo列表
        result = filterIfNotSystemUser(mComponentResolver.queryActivities(this,intent, resolvedType, flags, userId), userId);
        //对查找的过滤
    }else{
        //先从settings里查找这个pkg，再通过reslover查找这个应用详情
        final PackageStateInternal setting =
            getPackageStateInternal(pkgName, Process.SYSTEM_UID);
        result = filterIfNotSystemUser(mComponentResolver.queryActivities( setting.getAndroidPackage().getActivities()...), userId);

    }
    return new QueryIntentActivitiesResult(sortResult, addInstant, result);
}

public PackageStateInternal getPackageStateInternal(String packageName,int callingUid) {
    return mSettings.getPackage(packageName);
}
```

+ 注释1对于查找所有应用信息，先从ComputerEngine的本地缓存mComponentResolver去查找ResolveInfo列表，此数据在上面commitPackageSettings时已经赋值。再进行一些过滤，比如有些应用设置的system_user_only，这些数据不会返回。
+ 注释2对于查找某个应用的信息，先从本地settings里查找，再通过mComponentResolver去查找此应用的详细信息。

总结下：PMS查询操作是通过ComputerEngine来完成的，而ComputerEngine是在pms启动时缓存了PMS的mPackages，settings和mComponentResolver的这些信息。

#### adb install安装流程

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

private int doCommitSession(int sessionId, boolean logSuccess) {
    session = new PackageInstaller.Session(
        mInterface.getPackageInstaller().openSession(sessionId));
    session.commit(receiver.getIntentSender());
    //...
}
```

这里通过doCommitSession调用到PackageInstallerSession.commit，后面就是通过PackageInstall去真正走安装流程。

调用流程如下：PackageInstallerSession.commit -> dispatchSessionSealed ->Handler(MSG_ON_SESSION_SEALED) -> handleSessionSealed ->dispatchStreamValidateAndCommit ->Handler(MSG_STREAM_VALIDATE_AND_COMMIT) ->handleStreamValidateAndCommit->Handler(MSG_INSTALL) -> handleInstall ->verify->verifyNonStaged -> onVerificationComplete->install->installNonStaged->InstallParams.installStage->PackageHandler(INIT_COPY)->HandlerParams.startCopy->InstallParams.handleReturnCode->processPendingInstall->processInstallRequestsAsync->InstallPackageHelper.processInstallRequests->installPackagesTracedLI->installPackagesLI

我们重点关注安装包的帮助类InstallPackageHelper.installPackagesLI

```java
   private void installPackagesLI(List<InstallRequest> requests) {
       //...
       //1 第一阶段 Prepare
       prepareResult =preparePackageLI(request.mArgs, request.mInstallResult);

       //2 第二阶段 Scan 调用到scanPackageNewLI，上面已经看过了
       final ScanResult result = scanPackageTracedLI(
           prepareResult.mPackageToScan, prepareResult.mParseFlags,
           prepareResult.mScanFlags, System.currentTimeMillis(),
           request.mArgs.mUser, request.mArgs.mAbiOverride);
       
       //3 第三阶段 Reconcile
       ReconcileRequest reconcileRequest = new ReconcileRequest(preparedScans, installArgs,installResults, prepareResults, Collections.unmodifiableMap(mPm.mPackages), versionInfos);
       reconciledPackages = ReconcilePackageUtils.reconcilePackages(reconcileRequest, mSharedLibraries,mPm.mSettings.getKeySetManagerService(), mPm.mSettings);
       
       //4 第四阶段 Commit
       commitRequest = new CommitRequest(reconciledPackages,mPm.mUserManager.getUserIds());
       commitPackagesLocked(commitRequest);

       executePostCommitSteps(commitRequest);
   }
```

安装过程分为以上四个阶段

+ prepare阶段
+ Scan阶段 
+ Reconcile阶段
+ commit阶段

后面3个步骤在前面已经介绍过了，只看第一个prepare不同的地方

> frameworks/base/services/core/java/com/android/server/pm/InstallPackageHelper.java

```java
private PrepareResult preparePackageLI(InstallArgs args, PackageInstalledInfo res){
     final File tmpPackageFile = new File(args.getCodePath());
    //...
    final ParsedPackage parsedPackage;
    try (PackageParser2 pp = mPm.mInjector.getPreparingPackageParser()) {
        //1 parsePackage解析指定的apk文件
        parsedPackage = pp.parsePackage(tmpPackageFile, parseFlags, false);
        AndroidPackageUtils.validatePackageDexMetadata(parsedPackage);
    } catch (PackageManagerException e) {} 
    //省略设置签名信息，校验版本信息等
    
    //2 根据解析后的pkgName去判断是否此次安装是更新
    String pkgName = res.mName = parsedPackage.getPackageName();
    boolean replace = false;
    synchronized (mPm.mLock) {
        String oldName = mPm.mSettings.getRenamedPackageLPr(pkgName);
        if (parsedPackage.getOriginalPackages().contains(oldName)
            && mPm.mPackages.containsKey(oldName)) {
            parsedPackage.setPackageName(oldName);
            pkgName = parsedPackage.getPackageName();
            replace = true;
        }else if (mPm.mPackages.containsKey(pkgName)) {
            replace = true;
        }
    }
    //如果是更新应用 先检查下如果新版本小于等于22 旧版本大于22抛异常；如果旧版本是persistent持久的，不允许更新抛异常
    if (replace) {
        AndroidPackage oldPackage = mPm.mPackages.get(pkgName);
        final int oldTargetSdk = oldPackage.getTargetSdkVersion();
        final int newTargetSdk = parsedPackage.getTargetSdkVersion();
        if (oldTargetSdk > Build.VERSION_CODES.LOLLIPOP_MR1
            && newTargetSdk <= Build.VERSION_CODES.LOLLIPOP_MR1) { 
         throw new PrepareFailure("new target SDK...");}
        if (oldPackage.isPersistent()&& ((installFlags & PackageManager.INSTALL_STAGED) == 0)) {
            throw new PrepareFailure( " is a persistent app. ");
        }
    }
    //省略校验签名，shareduser信息等
    
    //遍历AndroidManifest里所有声明的权限，校验有没有声明不允许声明的
    final int n = ArrayUtils.size(parsedPackage.getPermissions());
    for (int i = n - 1; i >= 0; i--) {
        final ParsedPermission perm = parsedPackage.getPermissions().get(i);
		//省略校验权限
    }
    
    //如果是系统应用，不能安装到sd卡和设置instant属性
    if (systemApp) {
        if (onExternal) {
            throw new PrepareFailure("Cannot install updates to system apps on sdcard");
        } else if (instantApp) {
            throw new PrepareFailure("Cannot update a system app with an instant app");
        }
    }
    //...
    
    return new PrepareResult(replace, targetScanFlags, targetParseFlags, oldPackage, parsedPackage, replace /* clearCodeCache */, sysPkg, ps, disabledPs);
}
```

准备阶段先通过parsePackage去解析指定apk的AndroidManifest文件，上面已经详细看过parsePackage方法了。然后跟PMS缓存的mPackages比较，如果相等说明是更新，对更新的应用做一些版本和属性的校验。再校验签名shareduser等，还会遍历声明的权限，看是否有不允许声明的权限。准备阶段本质上就是解析Manifest文件，然后去各种校验，校验失败的话直接抛异常，这里可以看下安装失败都会有哪些原因。

总的来说，adb install命令会先在adb进程构建服务端执行的shell语句后通过socket传给shell进程执行，shell进程执行cmd package 指令后获取指定的服务，比如PMS，并通过binder机制调用其shellCommand，最终再根据传的adb后面的参数，比如install/uninstall执行相应的安装/卸载。

实际上真正的安装过程是在installd守护进程中做的，在安装流程的第四步commit里会调用installd进程去真正安装，由于本文只关注PMS相关不再详解，后面会专门分析installd安装过程。

#### 总结

PMS主要负责所有应用的管理，包括安装更新卸载以及权限管理。在SystemServer的启动引导服务中通过调用PMS的main方法启动，main里先构建注射器，比如settings，SystemConfig，各种关联服务比如PermissionManager，PackageInstallerService，然后调用构造函数来启动，待启动成功后添加到以package为key的系统服务中。构造函数里启动流程如下：先准备Settings，Settings是辅助PMS保存所有应用信息的类，先往setting里存入系统的共享用户id，比如android.uid.system等，我们的系统应用都会配置此sharedUserId，相同共享用户信息的应用具有相同的权限。然后再准备SystemConfig，通过解析(空，vendor，oem，odm，product，system）/etc/（permissions，sysconfig）这12个文件夹下的所有xml以加载配置全局，比如某些应用或者uid对应的权限等。Settings和SystemConfig准备好后会通过Settings.readLPw去读packages.xml或者packages-backup.xml备份文件，如果有备份文件，说明上次写入packages.xml时有异常，用备份文件并把packages.xml删除，如果两个都没有说明是首次开机，首次开机会调用jni层做一些拷贝动作。非首次开机会把packages.xml解析到Settings中，比如所有应用信息会解析到(PackageSetting)mPackages，所有的共享用户信息会解析到(SharedUserSetting)mSharedUsers中。然后扫描系统应用和非系统应用，系统目录主要扫描system,vendor,oem,odm,product,system_ext下的overlay，priv-app，app目录（先扫这些目录下的overlay，再扫system/framework，最后再扫这些目录的priv-app，app目录），非系统目录扫/data/app，扫描过程最终都调用了InitAppsHelper.scanDirTracedLI去扫描目录，通过线程池+阻塞队列的方式扫描了所有应用的AndroidManifest.xml文件，每解析完一个后会调用addForInitLI去处理解析后的数据，比如构建PackageSetting对象，每个应用对应一个PackageSetting对象，最终处理完的数据会缓存到settings和PMS里，然后会通过Settings.writeLPr去重新写入packages.xml，到这里PMS基本启动完成，最后在SystemServer.startOtherServices会执行PMS.systemReady方法，这里会执行关联服务的systemReady，至此PMS启动流程就结束了