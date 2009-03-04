/*
 * Copyright (C) 2008 Esmertec AG.
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.im.app;

import com.android.im.IChatSession;
import com.android.im.IChatSessionManager;
import com.android.im.IImConnection;
import com.android.im.engine.ImConnection;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.provider.Im;
import android.text.TextUtils;
import android.util.Log;

import java.util.Iterator;
import java.util.Set;

public class ImUrlActivity extends Activity {
    private static final String[] ACCOUNT_PROJECTION = {
        Im.Account._ID,
        Im.Account.PASSWORD,
    };
    private static final int ACCOUNT_ID_COLUMN = 0;
    private static final int ACCOUNT_PW_COLUMN = 1;

    private String mProviderName;
    private String mToAddress;

    private ImApp mApp;
    private IImConnection mConn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if (Intent.ACTION_SENDTO.equals(intent.getAction())) {
            if (!resolveIntent(intent)) {
                finish();
                return;
            }

            if (TextUtils.isEmpty(mToAddress)) {
                Log.w(ImApp.LOG_TAG, "<ImUrlActivity>Invalid to address:" + mToAddress);
                finish();
                return;
            }
            mApp = ImApp.getApplication(this);
            mApp.callWhenServiceConnected(new Handler(), new Runnable(){
                public void run() {
                    handleIntent();
                }});

        } else {
            finish();
        }
    }

    void handleIntent() {
        ContentResolver cr = getContentResolver();
        long providerId = Im.Provider.getProviderIdForName(cr, mProviderName);
        long accountId;

        mConn= mApp.getConnection(providerId);
        if (mConn == null) {
            Cursor c = DatabaseUtils.queryAccountsForProvider(cr, ACCOUNT_PROJECTION, providerId);
            if (c == null) {
                addAccount(providerId);
            } else {
                accountId = c.getLong(ACCOUNT_ID_COLUMN);
                if (c.isNull(ACCOUNT_PW_COLUMN)) {
                    editAccount(accountId);
                } else {
                    signInAccount(accountId);
                }
            }
        } else {
            try {
                int state = mConn.getState();
                accountId = mConn.getAccountId();

                if (state < ImConnection.LOGGED_IN) {
                    signInAccount(accountId);
                } else if (state == ImConnection.LOGGED_IN || state == ImConnection.SUSPENDED) {
                    if (!isValidToAddress()) {
                        showContactList(accountId);
                    } else {
                        openChat(providerId, accountId);
                    }
                }
            } catch (RemoteException e) {
                // Ouch!  Service died!  We'll just disappear.
                Log.w("ImUrlActivity", "Connection disappeared!");
            }
        }
        finish();
    }

    private void addAccount(long providerId) {
        Intent  intent = new Intent(this, AccountActivity.class);
        intent.setAction(Intent.ACTION_INSERT);
        intent.setData(ContentUris.withAppendedId(Im.Provider.CONTENT_URI, providerId));
        intent.putExtra(ImApp.EXTRA_INTENT_SEND_TO_USER, mToAddress);
        startActivity(intent);
    }

    private void editAccount(long accountId) {
        Uri accountUri = ContentUris.withAppendedId(Im.Account.CONTENT_URI, accountId);
        Intent intent = new Intent(this, AccountActivity.class);
        intent.setAction(Intent.ACTION_EDIT);
        intent.setData(accountUri);
        intent.putExtra(ImApp.EXTRA_INTENT_SEND_TO_USER, mToAddress);
        startActivity(intent);
    }

    private void signInAccount(long accountId) {
        Uri accountUri = ContentUris.withAppendedId(Im.Account.CONTENT_URI, accountId);
        Intent intent = new Intent(this, SigningInActivity.class);
        intent.setData(accountUri);
        intent.putExtra(ImApp.EXTRA_INTENT_SEND_TO_USER, mToAddress);
        startActivity(intent);
    }

    private void showContactList(long accountId) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setData(Im.Contacts.CONTENT_URI);
        intent.addCategory(ImApp.IMPS_CATEGORY);
        intent.putExtra("accountId", accountId);

        startActivity(intent);
    }

    private void openChat(long provider, long account) {
        try {
            IChatSessionManager manager = mConn.getChatSessionManager();
            IChatSession session = manager.getChatSession(mToAddress);
            if(session == null) {
                session = manager.createChatSession(mToAddress);
            }

            Uri data = ContentUris.withAppendedId(Im.Chats.CONTENT_URI, session.getId());
            Intent i = new Intent(Intent.ACTION_VIEW, data);
            i.putExtra("from", mToAddress);
            i.putExtra("providerId", provider);
            i.putExtra("accountId", account);
            i.addCategory(ImApp.IMPS_CATEGORY);
            startActivity(i);
        } catch (RemoteException e) {
            // Ouch!  Service died!  We'll just disappear.
            Log.w("ImUrlActivity", "Connection disappeared!");
        }
    }

    private boolean resolveIntent(Intent intent) {
        Uri data = intent.getData();
        String host = data.getHost();

        if (Log.isLoggable(ImApp.LOG_TAG, Log.DEBUG)) {
            log("resolveIntent: host=" + host);
        }

        if (TextUtils.isEmpty(host)) {
            Set<String> categories = intent.getCategories();
            if (categories != null) {
                Iterator<String> iter = categories.iterator();
                if (iter.hasNext()) {
                    String category = iter.next();
                    String providerName = getProviderNameForCategory(category);
                    mProviderName = findMatchingProvider(providerName);
                    if (mProviderName == null) {
                        Log.w(ImApp.LOG_TAG,
                                "resolveIntent: IM provider "+ category + " not supported");
                        return false;
                    }
                }
            }
            mToAddress = data.getSchemeSpecificPart();
        } else {
            mProviderName = findMatchingProvider(host);

            if (mProviderName == null) {
                Log.w(ImApp.LOG_TAG, "resolveIntent: IM provider "+ host + " not supported");
                return false;
            }

            String path = data.getPath();

            if (Log.isLoggable(ImApp.LOG_TAG, Log.DEBUG)) log("resolveIntent: path=" + path);

            if (!TextUtils.isEmpty(path)) {
                int index;
                if ((index = path.indexOf('/')) != -1) {
                    mToAddress = path.substring(index+1);
                }
            }
        }

        if (Log.isLoggable(ImApp.LOG_TAG, Log.DEBUG)) {
            log("resolveIntent: provider=" + mProviderName + ", to=" + mToAddress);
        }

        return true;
    }

    private String getProviderNameForCategory(String providerCategory) {
        if (providerCategory != null) {
            if (providerCategory.equalsIgnoreCase("com.android.im.category.AIM")) {
                return Im.ProviderNames.AIM;
            } else if (providerCategory.equalsIgnoreCase("com.android.im.category.MSN")) {
                return Im.ProviderNames.MSN;
            } else if (providerCategory.equalsIgnoreCase("com.android.im.category.YAHOO")) {
                return Im.ProviderNames.YAHOO;
            }
        }

        return null;
    }

    private String findMatchingProvider(String provider) {
        if (TextUtils.isEmpty(provider)) {
            return null;
        }

        if (Im.ProviderNames.AIM.equalsIgnoreCase(provider)) {
            return Im.ProviderNames.AIM;
        }

        if (Im.ProviderNames.MSN.equalsIgnoreCase(provider)) {
            return Im.ProviderNames.MSN;
        }

        if (Im.ProviderNames.YAHOO.equalsIgnoreCase(provider)) {
            return Im.ProviderNames.YAHOO;
        }

        return null;
    }

    private boolean isValidToAddress() {
        if (TextUtils.isEmpty(mToAddress)) {
            return false;
        }

        if (mToAddress.indexOf('/') != -1) {
            return false;
        }

        return true;
    }

    private static void log(String msg) {
        Log.d(ImApp.LOG_TAG, "<ImUrlActivity> " + msg);
    }
}
