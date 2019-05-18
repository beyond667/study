package demo.beyond.com.blog.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

public class MyService extends Service {
    public MyService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e("==", "======onCreate==");
    }

    @Override
    public IBinder onBind(Intent intent) {
        Log.e("==", "======onBind==");
        return new MyBinder();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.e("==", "======onUnbind==");
        return super.onUnbind(intent);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.e("==", "======onStartCommand==");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.e("==", "======onDestroy==");
        super.onDestroy();
    }

    class MyBinder extends Binder {
        public MyService getService() {
            return MyService.this;
        }
    }

    public void myMethod() {
        Log.e("==", "======myMethod==");
    }
}
