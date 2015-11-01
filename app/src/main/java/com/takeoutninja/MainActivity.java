package com.takeoutninja;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.gms.auth.api.Auth;
import com.google.android.gms.auth.api.credentials.Credential;
import com.google.android.gms.auth.api.credentials.CredentialRequest;
import com.google.android.gms.auth.api.credentials.CredentialRequestResult;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.gcm.GoogleCloudMessaging;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity implements
        View.OnClickListener,
        GoogleApiClient.ConnectionCallbacks,
        GoogleApiClient.OnConnectionFailedListener {

    private static final String TAG = MainActivity.class.getSimpleName();
    private static final String KEY_IS_RESOLVING = "is_resolving";
    private static final int RC_SAVE = 1;
    private static final int RC_HINT = 2;
    private static final int RC_READ = 3;

    private AtomicInteger msgId = new AtomicInteger();
    private GoogleApiClient mCredentialsApiClient;
    private Credential mCurrentCredential;
    private boolean mIsResolving = false;
    private EditText mEmailField;
    private EditText mPasswordField;

    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;
    private BroadcastReceiver mRegistrationBroadcastReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mEmailField = (EditText) findViewById(R.id.edit_text_email);
        mPasswordField = (EditText) findViewById(R.id.edit_text_password);
        findViewById(R.id.button_save_credential).setOnClickListener(this);
        findViewById(R.id.button_delete_loaded_credential).setOnClickListener(this);

        if (savedInstanceState != null) {
            mIsResolving = savedInstanceState.getBoolean(KEY_IS_RESOLVING);
        }

        mCredentialsApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .enableAutoManage(this, 0, this)
                .addApi(Auth.CREDENTIALS_API)
                .build();

        mRegistrationBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                SharedPreferences sharedPreferences =
                        PreferenceManager.getDefaultSharedPreferences(context);
                boolean sentToken = sharedPreferences
                        .getBoolean(QuickstartPreferences.SENT_TOKEN_TO_SERVER, false);
                if (sentToken) {
                    //message sent
                } else {
                    //error state
                }
            }
        };

        if (checkPlayServices()) {
            // Start IntentService to register this application with GCM.
            Intent intent = new Intent(this, RegistrationIntentService.class);
            startService(intent);
        }

    }

    @Override
    public void onStart() {
        super.onStart();
        // Attempt auto-sign in.
        if (!mIsResolving) {
            requestCredentials(true);
        }
    }

    /**
     * Request Credentials from the Credentials API.
     * @param shouldResolveHint true if resolutions for hints should occur. Setting
     *                          shouldResolveHint to false will not show UI unless there is a known
     *                          Credential and is therefore appropriate for app start.
     */
    private void requestCredentials(final boolean shouldResolveHint) {
        // Request all of the user's saved username/password credentials.  We are not using
        // setAccountTypes so we will not load any credentials from other Identity Providers.
        CredentialRequest request = new CredentialRequest.Builder()
                .setSupportsPasswordLogin(true)
                .build();
        showProgress();
        Auth.CredentialsApi.request(mCredentialsApiClient, request).setResultCallback(
                new ResultCallback<CredentialRequestResult>() {
                    @Override
                    public void onResult(CredentialRequestResult credentialRequestResult) {
                        hideProgress();
                        if (credentialRequestResult.getStatus().isSuccess()) {
                            // Successfully read the credential without any user interaction, this
                            // means there was only a single credential and the user has auto
                            // sign-in enabled.
                            processRetrievedCredential(credentialRequestResult.getCredential(), false);



                        } else {
                            // Reading the credential requires a resolution, which means the user
                            // may be asked to pick among multiple credentials if they exist.
                            Status status = credentialRequestResult.getStatus();
                            if (status.getStatusCode() == CommonStatusCodes.SIGN_IN_REQUIRED) {
                                if (!shouldResolveHint) {
                                    Log.d(TAG, "requestCredentials: ignoring hint.");
                                    return;
                                }
                                // This is a "hint" credential, which will have an ID but not
                                // a password.  This can be used to populate the username/email
                                // field of a sign-up form or to initialize other services.
                                resolveResult(status, RC_HINT);
                            } else if (status.getStatusCode() ==
                                    CommonStatusCodes.RESOLUTION_REQUIRED) {
                                // This is most likely the case where the user has multiple saved
                                // credentials and needs to pick one
                                resolveResult(status, RC_READ);
                            } else {
                                Log.w(TAG, "Unexpected status code: " + status.getStatusCode());
                            }
                        }
                    }
                });
    }

    /**
     * Attempt to resolve a non-successful Status from an asynchronous request.
     * @param status the Status to resolve.
     * @param requestCode the request code to use when starting an Activity for result,
     *                    this will be passed back to onActivityResult.
     */
    private void resolveResult(Status status, int requestCode) {
        // We don't want to fire multiple resolutions at once since that can result
        // in stacked dialogs after rotation or another similar event.
        if (mIsResolving) {
            Log.w(TAG, "resolveResult: already resolving.");
            return;
        }

        Log.d(TAG, "Resolving: " + status);
        if (status.hasResolution()) {
            Log.d(TAG, "STATUS: RESOLVING");
            try {
                status.startResolutionForResult(MainActivity.this, requestCode);
                mIsResolving = true;
            } catch (IntentSender.SendIntentException e) {
                Log.e(TAG, "STATUS: Failed to send resolution.", e);
                hideProgress();
            }
        } else {
            Log.e(TAG, "STATUS: FAIL");
            showToast("Could Not Resolve Error");
            hideProgress();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "onActivityResult:" + requestCode + ":" + resultCode + ":" + data);
        hideProgress();

        switch (requestCode) {
            case RC_HINT:
                // Drop into handling for RC_READ
            case RC_READ:
                if (resultCode == RESULT_OK) {
                    boolean isHint = (requestCode == RC_HINT);
                    Credential credential = data.getParcelableExtra(Credential.EXTRA_KEY);
                    processRetrievedCredential(credential, isHint);
                } else {
                    Log.e(TAG, "Credential Read: NOT OK");
                    showToast("Credential Read Failed");
                }

                mIsResolving = false;
                break;
            case RC_SAVE:
                if (resultCode == RESULT_OK) {
                    Log.d(TAG, "Credential Save: OK");
                    showToast("Credential Save Success");
                } else {
                    Log.e(TAG, "Credential Save: NOT OK");
                    showToast("Credential Save Failed");
                }

                mIsResolving = false;
                break;
        }
    }

    /**
     * Process a Credential object retrieved from a successful request.
     * @param credential the Credential to process.
     * @param isHint true if the Credential is hint-only, false otherwise.
     */
    private void processRetrievedCredential(Credential credential, boolean isHint) {
        Log.d(TAG, "Credential Retrieved: " + credential.getId() + ":" +
                anonymizePassword(credential.getPassword()));

        // If the Credential is not a hint, we should store it an enable the delete button.
        // If it is a hint, skip this because a hint cannot be deleted.
        if (!isHint) {
            mCurrentCredential = credential;
            findViewById(R.id.button_delete_loaded_credential).setEnabled(true);
            findViewById(R.id.button_save_credential).setEnabled(false);
            showToast("Credential Retrieved");
            //Successful SignIn. Lets go to the Next Activity
            sendCredentialsToBackendGCMServer(credential);
            Intent next = new Intent(this, LocationActivity.class);
            startActivity(next);
        } else {
            showToast("Credential Hint Retrieved");
        }

        mEmailField.setText(credential.getId());
        mPasswordField.setText(credential.getPassword());
    }


    /**
     * Converts a password to a series of asterisks the same length as the password, for better
     * and safer logging.
     */
    private String anonymizePassword(String password) {
        if (password == null) {
            return "null";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < password.length(); i++) {
            sb.append('*');
        }
        return sb.toString();
    }

    private void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private void showProgress() {
        findViewById(R.id.progress_bar).setVisibility(View.VISIBLE);
        findViewById(R.id.button_save_credential).setEnabled(false);
    }

    private void hideProgress() {
        findViewById(R.id.progress_bar).setVisibility(View.INVISIBLE);
        findViewById(R.id.button_save_credential).setEnabled(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(KEY_IS_RESOLVING, mIsResolving);
    }

    @Override
    public void onConnected(Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }


    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }

    /**
     * Called when the save button is clicked.  Reads the entries in the email and password
     * fields and attempts to save a new Credential to the Credentials API.
     */
    private void saveCredentialClicked() {
        String email = mEmailField.getText().toString();
        String password = mPasswordField.getText().toString();

        // Create a Credential with the user's email as the ID and storing the password.  We
        // could also add 'Name' and 'ProfilePictureURL' but that is outside the scope of this
        // minimal sample.
        Log.d(TAG, "Saving Credential:" + email + ":" + anonymizePassword(password));
        final Credential credential = new Credential.Builder(email)
                .setPassword(password)
                .build();

        showProgress();


        // NOTE: this method unconditionally saves the Credential built, even if all the fields
        // are blank or it is invalid in some other way.  In a real application you should contact
        // your app's back end and determine that the credential is valid before saving it to the
        // Credentials backend.

        sendCredentialsToBackendGCMServer(credential);

        Auth.CredentialsApi.save(mCredentialsApiClient, credential).setResultCallback(
                new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        if (status.isSuccess()) {
                            findViewById(R.id.button_save_credential).setEnabled(false);
                            findViewById(R.id.button_delete_loaded_credential).setEnabled(true);
                            Log.d(TAG, "SAVE: OK");
                            showToast("Credential Saved");
                            hideProgress();
                        } else {
                            resolveResult(status, RC_SAVE);
                        }
                    }
                });
    }

    private void sendCredentialsToBackendGCMServer(final Credential credential) {

        if(credential != null) {
            new AsyncTask() {
                @Override
                protected Object doInBackground(Object[] params) {
                    String msg;
                    try {
                        Bundle data = new Bundle();
                        data.putString("my_message", credential.getId());
                        data.putString("my_action", "CREDS");
                        String id = Integer.toString(msgId.incrementAndGet());
                        GoogleCloudMessaging.getInstance(getApplicationContext()).send(QuickstartPreferences.SENDER_ID + "@gcm.googleapis.com", id, data);
                        msg = "Sent message";
                        Log.d(TAG, "sendCredentialsToBackendGCMServer: OK!");
                    } catch (IOException ex) {
                        msg = "Error :" + ex.getMessage();
                        Log.d(TAG, "sendCredentialsToBackendGCMServer: ERROR!");
                    }
                    return msg;
                }

                @Override
                protected void onPostExecute(Object o) {
                    Log.d(TAG, "sendCredentialsToBackendGCMServer: " + o);
                }
            }.execute(null, null, null);
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.button_save_credential:
                saveCredentialClicked();
                //Successful Registration. Lets go to the Next Activity
                Intent next = new Intent(this, LocationActivity.class);
                startActivity(next);
                break;
            case R.id.button_delete_loaded_credential:
                deleteLoadedCredentialClicked();
                break;
        }
    }

    /**
     * Called when the delete credentials button is clicked.  This deletes the last Credential
     * that was loaded using the load button.
     */
    private void deleteLoadedCredentialClicked() {
        if (mCurrentCredential == null) {
            showToast("Error: no credential to delete");
            return;
        }

        showProgress();

        Auth.CredentialsApi.delete(mCredentialsApiClient, mCurrentCredential).setResultCallback(
                new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        hideProgress();
                        if (status.isSuccess()) {
                            // Credential delete succeeded, disable the delete button because we
                            // cannot delete the same credential twice. Clear text fields.
                            showToast("Credential Delete Success");
                            ((EditText) findViewById(R.id.edit_text_email)).setText("");
                            ((EditText) findViewById(R.id.edit_text_password)).setText("");
                            mCurrentCredential = null;
                        } else {
                            // Credential deletion either failed or was cancelled, this operation
                            // never gives a 'resolution' so we can display the failure message
                            // immediately.
                            Log.e(TAG, "Credential Delete: NOT OK");
                            showToast("Credential Delete Failed");
                        }
                    }
                });
    }


    private boolean checkPlayServices() {
        GoogleApiAvailability apiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = apiAvailability.isGooglePlayServicesAvailable(this);
        if (resultCode != ConnectionResult.SUCCESS) {
            if (apiAvailability.isUserResolvableError(resultCode)) {
                apiAvailability.getErrorDialog(this, resultCode, PLAY_SERVICES_RESOLUTION_REQUEST)
                        .show();
            } else {
                Log.i(TAG, "This device is not supported.");
                finish();
            }
            return false;
        }
        return true;
    }
    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(this).registerReceiver(mRegistrationBroadcastReceiver,
                new IntentFilter(QuickstartPreferences.REGISTRATION_COMPLETE));
    }

    @Override
    protected void onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mRegistrationBroadcastReceiver);
        super.onPause();
    }
}
