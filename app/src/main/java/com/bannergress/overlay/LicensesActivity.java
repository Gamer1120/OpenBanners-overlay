package com.bannergress.overlay;

import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import com.google.common.io.ByteStreams;

/** Shows the copyright notices and licence texts that must ship with the app (res/raw/licenses.txt). */
public class LicensesActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.preferences_licenses);
        TextView text = new TextView(this);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        text.setPadding(padding, padding, padding, padding);
        text.setTextIsSelectable(true);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setTextSize(11);
        try (InputStream in = getResources().openRawResource(R.raw.licenses)) {
            text.setText(new String(ByteStreams.toByteArray(in), StandardCharsets.UTF_8));
        } catch (IOException e) {
            text.setText(e.toString());
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
    }
}
