package demo.beyond.com.blog;

import android.content.Intent;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ImageView;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import demo.beyond.com.blog.activity.IntentActivity;
import demo.beyond.com.blog.activity.LifeActivity;
import demo.beyond.com.blog.service.ServiceActivity;

public class MainActivity extends AppCompatActivity {

    @BindView(R.id.service_activity)
    Button serviceActivity;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
    }

    @OnClick({R.id.service_activity,R.id.activity,R.id.activity_life})
    void clickView(View view) {
        switch (view.getId()) {
            case R.id.service_activity:
                serviceActivity.startAnimation(AnimationUtils.loadAnimation(this,R.anim.anim_scale_alpha_out));
                startActivity(new Intent(MainActivity.this, ServiceActivity.class));
                break;

            case R.id.activity:
                startActivity(new Intent(MainActivity.this, IntentActivity.class));
                break;
            case R.id.activity_life:
                startActivity(new Intent(MainActivity.this, LifeActivity.class));
                break;
            default:
                break;
        }
    }

}
