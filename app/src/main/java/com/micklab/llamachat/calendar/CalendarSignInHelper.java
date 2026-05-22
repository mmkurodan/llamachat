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
import com.micklab.llamachat.R;

public class CalendarSignInHelper {
    private final ComponentActivity activity;
    private final GoogleSignInClient signInClient;

    public CalendarSignInHelper(ComponentActivity activity) {
        this.activity = activity;
        GoogleSignInOptions signInOptions = new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestIdToken(activity.getString(R.string.server_client_id))
                .requestScopes(
                        new Scope("https://www.googleapis.com/auth/calendar.events"),
                        new Scope("https://www.googleapis.com/auth/calendar.readonly")
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
}
