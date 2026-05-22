package com.micklab.llamachat.calendar;

import android.content.Context;
import android.text.TextUtils;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;

import java.util.Arrays;
import java.util.List;

public final class CalendarServiceFactory {
    private static final List<String> SCOPES = Arrays.asList(
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.readonly"
    );

    private CalendarServiceFactory() {
    }

    public static Calendar createService(Context context) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account == null) return null;
        String accountName = account.getEmail();
        if (TextUtils.isEmpty(accountName) && account.getAccount() != null) {
            accountName = account.getAccount().name;
        }
        if (TextUtils.isEmpty(accountName)) return null;

        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(context, SCOPES);
        credential.setSelectedAccountName(accountName);

        return new Calendar.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
        ).setApplicationName("Dual AI Chat").build();
    }
}
