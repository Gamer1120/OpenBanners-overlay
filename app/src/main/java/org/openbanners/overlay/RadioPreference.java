package org.openbanners.overlay;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.CheckBoxPreference;

/**
 * One option of a choice shown as a radio button (Settings → Passphrases). Not stored itself: the change listener
 * stores the choice and checks the right option. Tapping the checked option does nothing.
 */
public class RadioPreference extends CheckBoxPreference {
    public RadioPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.preference_widget_radio);
        setPersistent(false);
    }

    @Override
    protected void onClick() {
        if (!isChecked() && callChangeListener(true)) setChecked(true);
    }
}
