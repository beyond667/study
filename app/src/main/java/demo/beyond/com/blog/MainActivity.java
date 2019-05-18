package demo.beyond.com.blog;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;

import butterknife.ButterKnife;
import butterknife.OnClick;
import demo.beyond.com.blog.service.ServiceActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
    }

    @OnClick({R.id.service_activity})
    void clickView(View view) {
        switch (view.getId()) {
            case R.id.service_activity:
                startActivity(new Intent(MainActivity.this, ServiceActivity.class));
                break;
            default:
                break;
        }
    }

}
