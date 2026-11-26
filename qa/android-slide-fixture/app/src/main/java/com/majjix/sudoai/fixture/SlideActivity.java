package com.majjix.sudoai.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;

/** No permissions, network, storage or user data. Only invented qualification text. */
public final class SlideActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView slide = new TextView(this);
        slide.setText("SudoAI Pixel screen fixture\n\nMorgan owns the network checklist.\n\nReview is Friday at ten.\n\nCodeword: amber lighthouse 47\n\nInvented test facts only");
        slide.setTextSize(30);
        slide.setTextColor(Color.WHITE);
        slide.setBackgroundColor(Color.rgb(16, 25, 29));
        slide.setGravity(Gravity.CENTER);
        slide.setPadding(36, 60, 36, 60);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.rgb(16, 25, 29));
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        layout.addView(slide, new LinearLayout.LayoutParams(-1, 0, 1));
        Button offline = new Button(this);
        offline.setText("Show offline fact");
        offline.setOnClickListener(view -> slide.setText(
            "SudoAI offline payload fixture\n\n"
            + "Taylor owns the offline recovery checklist.\n\n"
            + "Offline review is Tuesday at nine.\n\n"
            + "Codeword: cobalt meadow 83\n\n"
            + "Invented offline test facts only"));
        layout.addView(offline, new LinearLayout.LayoutParams(-1, -2));
        setContentView(layout);
    }
}
