package org.openbanners.overlay.api;

import android.content.Context;

import androidx.annotation.StringRes;

import java.io.IOException;

/**
 * An error whose message is a string resource, so it's shown in the app's language. The English text stays
 * the exception message for logs and tests.
 */
public class UserMessageException extends IOException {
    @StringRes
    private final int messageRes;
    private final Object[] args;

    public UserMessageException(@StringRes int messageRes, String english, Object... args) {
        super(english);
        this.messageRes = messageRes;
        this.args = args;
    }

    /** A message that is shown as is (e.g. one from the server, which has no translation here). */
    public UserMessageException(String message) {
        this(0, message);
    }

    public String message(Context context) {
        return messageRes == 0 ? getMessage() : context.getString(messageRes, args);
    }

    /** The message to show for {@code e}: translated if it is a {@link UserMessageException}. */
    public static String messageOf(Context context, Throwable e) {
        if (e instanceof UserMessageException) return ((UserMessageException) e).message(context);
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
