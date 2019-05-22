package demo.beyond.com.blog.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import demo.beyond.com.blog.IMyAidlInterface;
import demo.beyond.com.blog.IMyListener;
import demo.beyond.com.blog.R;

/**
 * 这个类单独放到新建的app中用来模拟客户端调用服务端方法，不想新建项目了。
 */
public class ClientActivity extends AppCompatActivity {
    private EditText params1Tv;
    private EditText params2Tv;
    private TextView resultTv;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client);
        params1Tv = findViewById(R.id.param1);
        params2Tv = findViewById(R.id.param2);
        resultTv = findViewById(R.id.tv_result);
        initView();
    }

    private IMyAidlInterface iMyAidlInterface;
    private IMyListener mCallback = new IMyListener.Stub() {
        @Override
        public void onSuccess(int result) throws RemoteException {
            resultTv.setText("" + result);
        }
    };

    private void initView() {
        findViewById(R.id.botton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                String params1 = params1Tv.getText().toString();
                String params2 = params2Tv.getText().toString();
                try {
                    if (iMyAidlInterface == null) {
                        Toast.makeText(ClientActivity.this, "没注册", Toast.LENGTH_LONG).show();
                    } else {
                        iMyAidlInterface.operation(Integer.parseInt(params1), Integer.parseInt(params2), mCallback);
                    }

                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
        });
        findViewById(R.id.bind).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bindService();
            }
        });
    }

    private void bindService() {
        Intent intent = new Intent();
        intent.setClassName("demo.beyond.com.blog", "demo.beyond.com.blog.service.AIDLService");
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, final IBinder service) {
            iMyAidlInterface = IMyAidlInterface.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            iMyAidlInterface = null;
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceConnection != null) {
            unbindService(serviceConnection);
        }
    }
}
