package com.micklab.llamachat.calendar;

import android.content.Context;
import android.text.TextUtils;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.api.client.extensions.android.http.AndroidHttp;
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;

import java.util.Collections;
import java.util.Arrays;
import java.util.List;

public final class CalendarServiceFactory {
    private static final List<String> READ_SCOPES = Collections.singletonList(
            "https://www.googleapis.com/auth/calendar.readonly"
    );
    private static final List<String> WRITE_SCOPES = Collections.singletonList(
            "https://www.googleapis.com/auth/calendar.events"
    );

    private CalendarServiceFactory() {
    }

    public static Calendar createReadService(Context context) {
        return createService(context, READ_SCOPES, "read");
    }

    public static Calendar createWriteService(Context context) {
        return createService(context, WRITE_SCOPES, "write");
    }

    private static Calendar createService(Context context, List<String> scopes, String accessType) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        if (account == null) {
            CalendarDebugLogger.log(context, "createService(" + accessType + "): account is null");
            return null;
        }
        String accountName = account.getEmail();
        if (TextUtils.isEmpty(accountName) && account.getAccount() != null) {
            accountName = account.getAccount().name;
        }
        if (TextUtils.isEmpty(accountName)) {
            CalendarDebugLogger.log(context, "createService(" + accessType + "): accountName is empty");
            return null;
        }

        CalendarDebugLogger.log(
                context,
                "createService(" + accessType + "): account=" + accountName
                        + ", scopes=" + scopes
                        + ", grantedScopes=" + CalendarSignInHelper.describeGrantedScopes(account)
        );

        GoogleAccountCredential credential = GoogleAccountCredential.usingOAuth2(context, scopes);
        credential.setSelectedAccountName(accountName);

        return new Calendar.Builder(
                AndroidHttp.newCompatibleTransport(),
                GsonFactory.getDefaultInstance(),
                credential
        ).setApplicationName("Dual AI Chat").build();
    }
}
