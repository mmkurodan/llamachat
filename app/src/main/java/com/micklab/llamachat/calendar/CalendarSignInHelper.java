package com.micklab.llamachat.calendar;

import android.content.Context;
import android.content.Intent;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;

public class CalendarSignInHelper {
    public static final Scope CALENDAR_EVENTS_SCOPE =
            new Scope("https://www.googleapis.com/auth/calendar.events");
    public static final Scope CALENDAR_READONLY_SCOPE =
            new Scope("https://www.googleapis.com/auth/calendar.readonly");

    private final ComponentActivity activity;
    private final GoogleSignInClient signInClient;

    public CalendarSignInHelper(ComponentActivity activity) {
        this.activity = activity;
        GoogleSignInOptions signInOptions = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(
                        CALENDAR_EVENTS_SCOPE,
                        CALENDAR_READONLY_SCOPE
                )
                .build();
        signInClient = GoogleSignIn.getClient(activity, signInOptions);
    }

    public void launchSignIn(ActivityResultLauncher<Intent> launcher) {
        if (launcher == null) return;
        launcher.launch(signInClient.getSignInIntent());
    }

    public void signOut(Runnable onComplete) {
        signInClient.signOut().addOnCompleteListener(activity, task -> {
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    public GoogleSignInAccount getLastSignedInAccount(Context context) {
        return GoogleSignIn.getLastSignedInAccount(context);
    }

    public boolean hasReadAccess(Context context) {
        return hasReadAccess(getLastSignedInAccount(context));
    }

    public boolean hasWriteAccess(Context context) {
        return hasWriteAccess(getLastSignedInAccount(context));
    }

    public static boolean hasReadAccess(GoogleSignInAccount account) {
        return account != null && (
                GoogleSignIn.hasPermissions(account, CALENDAR_EVENTS_SCOPE)
                        || GoogleSignIn.hasPermissions(account, CALENDAR_READONLY_SCOPE)
        );
    }

    public static boolean hasWriteAccess(GoogleSignInAccount account) {
        return account != null && GoogleSignIn.hasPermissions(account, CALENDAR_EVENTS_SCOPE);
    }
}
