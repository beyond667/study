package demo.beyond.com.blog.service;

import android.app.IntentService;
import android.content.Intent;
import android.support.annotation.Nullable;
import android.util.Log;

public class MyIntentService extends IntentService {
    public MyIntentService() {
        super("MyIntentService");
    }
    public MyIntentService(String name) {
        super(name);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("==", "======onCreate==");
    }

    @Override
    public void onStart(@Nullable Intent intent, int startId) {
        Log.e("==", "======onStart==");
        super.onStart(intent, startId);
    }

    @Override
    protected void onHandleIntent(@Nullable Intent intent) {
        Log.e("==", "======onHandleIntent==");
        try {
            // 模拟耗时操作
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e("==", "======onStartCommand==");
        return super.onStartCommand(intent,flags,startId);
    }


    @Override
    public void onDestroy() {
        Log.e("==", "======onDestroy==");
        super.onDestroy();
    }


}
