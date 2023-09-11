### JNI

#### 背景



##### 开始

新建个c++项目`cjnitest`,修改下默认的MainActivity，新加个与jni通信的方法`getStringPwd`和静态方法`getStaticPwd`，传递java层的name过去，jni处理后返回响应的String。另声明了两个静态常量name和age

```java
public class MainActivity extends AppCompatActivity {
    static {
        System.loadLibrary("cjnitest");
    }
    public native String getStringPwd(String name);
    public native String stringFromJNI();
    public static native String getStaticPwd(String name);

    public static int age = 35;
    public static String name = "paul";

    private ActivityMainBinding binding;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        TextView tv = binding.sampleText;
        tv.setText(stringFromJNI());
    }
    //专门做个方法给native调用
    private int addByJava(int number1, int number2) {
        return number1 + number2 + 10;
    }
}
```

最新的jni中已经没有头文件，早期需要通过`javah 包名.类名`编译。  

如：通过`javah com.paul.cjnitest.MainActivity`编译后生成`com_paul_cjnitest_MainActivity.h`

```c++
#include <jni.h>
//如果未定义宏_Included_com_paul_cjnitest_MainActivity，就定义
#ifndef _Included_com_paul_cjnitest_MainActivity
#define _Included_com_paul_cjnitest_MainActivity

//如果定义c++，即是c++环境
#ifdef __cplusplus
//采用c的方式，禁止函数重载（即c++是允许同名的函数，c不允许同名函数，为了解决同名问题，jni默认采用c的方式）
extern "C" {
#endif
//取消宏com_paul_cjnitest_MainActivity_age
#undef com_paul_cjnitest_MainActivity_age
//重新定义宏com_paul_cjnitest_MainActivity_age 35L 对应java的 public static final int age = 35;
#define com_paul_cjnitest_MainActivity_age 35L
//java层定义的方法会被声明为对应的jni方法
JNIEXPORT jstring JNICALL Java_com_paul_cjnitest_MainActivity_getStringPwd
  (JNIEnv *, jobject, jstring);

JNIEXPORT jstring JNICALL Java_com_paul_cjnitest_MainActivity_stringFromJNI
  (JNIEnv *, jobject);
//java层定义的静态方法与非静态的区别是传过来的是jclass，即是类，而非静态传过来的是jobject，即是对象
JNIEXPORT jstring JNICALL Java_com_paul_cjnitest_MainActivity_getStaticPwd
  (JNIEnv *, jclass, jstring);

//对应上面的如果是c++环境， 增加个反大括号，因为上面extern "C" { 添加了大括号
#ifdef __cplusplus
}
#endif
#endif

```

可以看到：

1. int静态常量会被定义为宏
2. 判断如果是c++环境，会采用c的方式来禁止函数重载
3. java声明的静态native方法和非静态native方法唯一区别是静态传的是类，非静态传的是调用对象

最新已经不需要通过javah编译，即没头文件，更方便些。



#### 线程

+ JNIEnv 无法跨线程，可以跨函数
+ jobject 不能跨线程，不能跨函数
+ JavaVM 可以跨线程，可以跨函数

​	

需求：java调用native方法，传递对象，在native方法里新起线程，并把对象传给子线程，子线程处理完后调用java的UI方法

```java
//MainActivity定义native方法和点击事件
public native String testThread(Person person);
public void clickTestThread(View view) {
    Person person = new Person("张三",20);
    testThread(person);
}
```

native处理

```c++
JavaVM *jvm;
int JNI_OnLoad(JavaVM *vm, void *re) {
    jvm = vm;
    return JNI_VERSION_1_6;
}

struct MyContext {
    jobject instance;
    jobject person;
    // JNIEnv * env;无法在子线程使用JNIEnv
};

void * subThreadRun(void *args) {
    JNIEnv *subEnv = nullptr;
    //jint AttachCurrentThread(JNIEnv** p_env, void* thr_args)
    jint i = jvm->AttachCurrentThread(&subEnv, nullptr);
    //i == 0 代表成功 不等于0代表失败
    if (i != 0) {
        return 0;
    }

    MyContext *myContext = static_cast<MyContext *>(args);
    jclass personClz = subEnv->GetObjectClass(myContext->person);
    jmethodID getName = subEnv->GetMethodID(personClz,"getName","()Ljava/lang/String;");
    jmethodID getAge = subEnv->GetMethodID(personClz,"getAge","()I");
    jint ageValue = subEnv->CallIntMethod(myContext->person,getAge);
    jstring  nameVaule = static_cast<jstring>(subEnv->CallObjectMethod(myContext->person,getName));
    const char * nameChar  = subEnv->GetStringUTFChars(nameVaule,0);
    LOGE("异步线程拿到的person name为%s,age为%d",nameChar,ageValue);

    jclass mainClz = subEnv->GetObjectClass(myContext->instance);
    jmethodID  showDialogByOtherThread = subEnv->GetMethodID(mainClz,"showDialogByOtherThread","(Ljava/lang/String;)V");
    subEnv->CallVoidMethod(myContext->instance,showDialogByOtherThread,subEnv->NewStringUTF("这里异步返回的数据"));

    //收尾 把产生的myContext指针和全局引用回收
    delete(myContext);
    myContext = 0;
    subEnv->DeleteGlobalRef(myContext->instance);
    subEnv->DeleteGlobalRef(myContext->person);
    jvm->DetachCurrentThread();

    return 0;
}
/*
 * note 测试异步线程，在主线程中新建异步线程，并把参数传递给异步线程，异步线程处理完后调用java的方法修改ui
     JNIEnv 无法跨线程，可以跨函数
     jobject 不能跨线程，不能跨函数
     JavaVM 可以跨线程，可以跨函数
 */
extern "C"
JNIEXPORT jstring JNICALL
Java_com_paul_cjnitest_MainActivity_testThread(JNIEnv *env, jobject thiz, jobject person) {
    MyContext *myContext = new MyContext;
    //note 把MainActivity和传过来的person声明为全局引用 传递给子线程
    myContext->instance = env->NewGlobalRef(thiz);
    myContext->person = env->NewGlobalRef(person);
    //新起线程 并join到主线程
    pthread_t pid;
    //int pthread_create(pthread_t* __pthread_ptr, pthread_attr_t const* __attr, void* (*__start_routine)(void*), void*);
    pthread_create(&pid, 0, subThreadRun, myContext);
    pthread_join(pid, nullptr);

    string result = "this is testThread";
    return env->NewStringUTF(result.c_str());
}
```

* jobject需要通过`env->NewGlobalRef(xx)`声明为全局引用，再在`pthread_create`创建子线程时传参过去；  

* JNIEnv通过`JNI_OnLoad`方法先记录JavaVM，再通过`jvm->AttachCurrentThread(&subEnv, nullptr);`这种方式把当前线程附加在JVM中，使用完后需要`jvm->DetachCurrentThread();`来去掉关联



