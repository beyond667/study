package demo.beyond.com.blog.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(ServiceActivity.this)) {
                //若没有权限，提示获取.
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                Toast.makeText(ServiceActivity.this,"需要取得权限以使用悬浮窗",Toast.LENGTH_SHORT).show();
                startActivity(intent);
            }
        }

    }
    @OnClick({R.id.bt_start,R.id.bt_stop,R.id.bt_bind,R.id.bt_unbind,R.id.bt_intent_service,
            R.id.bt_bind_important,})
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
            case R.id.bt_intent_service:
                Intent intent1 = new Intent(ServiceActivity.this,MyIntentService.class);
                intent.putExtra("11","aa");
                startService(intent1);
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
