package com.galeria.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

final class Ui {
    static final String PREFS = "gallery_albums";
    static final int BG = Color.rgb(16, 18, 20);
    static final int SURFACE = Color.rgb(26, 26, 26);
    static final int SEARCH = Color.rgb(18, 18, 18);
    static final int TEXT = Color.rgb(245, 247, 250);
    static final int MUTED = Color.rgb(170, 178, 189);
    static final int ACCENT = Color.rgb(245, 247, 250);

    private Ui() {
    }

    static boolean darkMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("dark_mode", true);
    }

    static int bg(Context context) {
        return darkMode(context) ? BG : Color.rgb(248, 248, 248);
    }

    static int surface(Context context) {
        return darkMode(context) ? SURFACE : Color.rgb(238, 238, 238);
    }

    static int search(Context context) {
        return darkMode(context) ? SEARCH : Color.rgb(232, 232, 232);
    }

    static int text(Context context) {
        return darkMode(context) ? TEXT : Color.rgb(18, 18, 18);
    }

    static int muted(Context context) {
        return darkMode(context) ? MUTED : Color.rgb(92, 92, 92);
    }

    static int accent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.getInt("theme_color", darkMode(context) ? TEXT : Color.rgb(18, 18, 18));
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView title(Context context, String text, int sp) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(text(context));
        view.setTextSize(sp);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setSingleLine(true);
        return view;
    }

    static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextColor(muted(context));
        view.setTextSize(14);
        view.setGravity(android.view.Gravity.CENTER);
        return view;
    }

    static Button button(Context context, String text) {
        Button button = new Button(context);
        button.setText(text);
        button.setTextColor(text(context));
        button.setAllCaps(false);
        button.setBackgroundColor(surface(context));
        button.setMinHeight(dp(context, 42));
        return button;
    }

    static GradientDrawable rounded(int color, int radiusDp, Context context) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    static void setPadding(View view, int left, int top, int right, int bottom) {
        Context context = view.getContext();
        view.setPadding(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom));
    }
}
