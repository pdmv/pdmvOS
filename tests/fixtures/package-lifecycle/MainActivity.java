package dev.pdmv.os.qa.fixture;
public final class MainActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){super.onCreate(state);android.widget.TextView text=new android.widget.TextView(this);text.setText("Ứng dụng kiểm thử cài đặt và gỡ cài đặt pdmvOS");text.setTextColor(-1);text.setTextSize(24);text.setGravity(17);text.setBackgroundColor(0xff1455aa);setContentView(text);}
}
