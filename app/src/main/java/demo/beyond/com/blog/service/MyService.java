package demo.beyond.com.blog.service;

import android.app.AlertDialog;
import android.app.Service;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

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
//        Toast.makeText(this, "this is service", Toast.LENGTH_LONG).show();
        return START_STICKY;
    }

    private void showDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("提示");
        builder.setMessage("这里是service弹出的系统dialog");
        builder.setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        builder.setPositiveButton("确定", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {

            }
        });
        final AlertDialog dialog = builder.create();
        //在dialog  show方法之前添加如下代码，表示该dialog是一个系统的dialog
        //8.0新特性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        } else {
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        }
        dialog.show();

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
        showDialog();
    }
}
