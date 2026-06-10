package com.galeria.android;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {
    static final int BG = Color.rgb(16, 18, 20);
    static final int SURFACE = Color.rgb(26, 29, 33);
    static final int TEXT = Color.rgb(245, 247, 250);
    static final int MUTED = Color.rgb(170, 178, 189);
    static final int ACCENT = Color.rgb(75, 163, 255);

    private Ui() {
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView title(Context context, String text, int sp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(TEXT);
        view.setTextSize(sp);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setSingleLine(true);
        return view;
    }

    static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(MUTED);
        view.setTextSize(14);
        view.setGravity(android.view.Gravity.CENTER);
        return view;
    }

    static Button button(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setAllCaps(false);
        button.setBackgroundColor(SURFACE);
        button.setMinHeight(dp(context, 42));
        return button;
    }

    static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    static void setPadding(View view, int left, int top, int right, int bottom) {
        Context context = view.getContext();
        view.setPadding(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
    }
}
