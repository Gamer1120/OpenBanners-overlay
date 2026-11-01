package org.openbanners.overlay;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.preference.CheckBoxPreference;
import androidx.preference.PreferenceViewHolder;

/** A checkbox that belongs to the option above it (indented), e.g. the mission-number fallback under OpenBanners. */
public class SubCheckBoxPreference extends CheckBoxPreference {
    public SubCheckBoxPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        View view = holder.itemView;
        Object original = view.getTag(R.id.original_padding_start);
        if (!(original instanceof Integer)) {
            original = view.getPaddingStart();
            view.setTag(R.id.original_padding_start, original);
        }
        int indent = Math.round(32 * view.getResources().getDisplayMetrics().density);
        view.setPaddingRelative((Integer) original + indent, view.getPaddingTop(), view.getPaddingEnd(), view.getPaddingBottom());
    }
}
