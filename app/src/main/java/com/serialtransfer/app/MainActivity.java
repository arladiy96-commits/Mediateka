package com.serialtransfer.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private TextView text(String value, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        return view;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(15, 21, 28));
        getWindow().setNavigationBarColor(Color.rgb(15, 21, 28));

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(dp(24), dp(24), dp(24), dp(24));
        layout.setBackgroundColor(Color.rgb(15, 21, 28));

        TextView title = text("SerialTransfer", 28, Color.WHITE);
        layout.addView(title);

        TextView subtitle = text("Передача сериалов по Wi-Fi", 16, Color.rgb(180, 194, 207));
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.topMargin = dp(10);
        layout.addView(subtitle, subtitleParams);

        TextView status = text(
                "Первый запуск: Android-проект создан.\n\n"
                + "Совместимость: Android 7 и новее.\n"
                + "Режимы отправителя, получателя и запись на microSD "
                + "будут добавлены в следующих обновлениях.",
                15, Color.rgb(215, 222, 231));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(30);
        layout.addView(status, statusParams);

        setContentView(layout);
    }
}
