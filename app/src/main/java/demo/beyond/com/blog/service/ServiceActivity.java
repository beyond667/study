package demo.beyond.com.blog.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;

import butterknife.ButterKnife;
import butterknife.OnClick;
import demo.beyond.com.blog.MainActivity;
import demo.beyond.com.blog.R;

public class ServiceActivity extends AppCompatActivity{

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_service);


        ButterKnife.bind(this);
    }
    @OnClick({R.id.bt_start,R.id.bt_stop,R.id.bt_bind,R.id.bt_unbind,
            R.id.bt_bind_important,R.id.bt_not_foreground,R.id.bt_debug_unbind})
    void clickView(View view) {
        Intent intent = new Intent(ServiceActivity.this,MyService.class);
        switch (view.getId()) {
            case R.id.bt_start:
                startService(new Intent(ServiceActivity.this,MyService.class));
                break;
            case R.id.bt_stop:
                stopService(new Intent(ServiceActivity.this,MyService.class));
                break;
            case R.id.bt_bind:
                bindService(intent,connection,BIND_AUTO_CREATE);
                break;
            case R.id.bt_bind_important:
                bindService(intent,connection,BIND_IMPORTANT);
                break;
            case R.id.bt_not_foreground:
                bindService(intent,connection,BIND_NOT_FOREGROUND);
                break;
            case R.id.bt_debug_unbind:
                bindService(intent,connection,BIND_DEBUG_UNBIND);
                break;
            case R.id.bt_unbind:
                unbindService(connection);
                break;
            default:
                break;
        }
    }

    private ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            MyService.MyBinder binder = (MyService.MyBinder)iBinder;
            binder.getService().myMethod();
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.e("===","=====onServiceDisconnected");
        }
    };
}
