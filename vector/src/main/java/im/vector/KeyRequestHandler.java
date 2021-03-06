/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector;

import android.app.Activity;
import android.content.DialogInterface;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.crypto.IncomingRoomKeyRequest;
import org.matrix.androidsdk.crypto.IncomingRoomKeyRequestCancellation;
import org.matrix.androidsdk.crypto.data.MXDeviceInfo;
import org.matrix.androidsdk.crypto.data.MXUsersDevicesMap;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.MatrixError;
import org.matrix.androidsdk.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import im.vector.activity.CommonActivityUtils;

/**
 * Manage the key share events
 */
public class KeyRequestHandler {
    private static final String LOG_TAG = KeyRequestHandler.class.getSimpleName();

    // shared instance
    private static KeyRequestHandler mInstance = null;

    // the user/device for which we currently have a dialog open
    private String mCurrentUser;
    private String mCurrentDevice;
    private AlertDialog mAlertDialog;

    // userId -> deviceId -> [keyRequest]
    private final Map<String, Map<String, List<IncomingRoomKeyRequest>>> mPendingKeyRequests = new HashMap<>();

    /**
     * Provide the shared instance
     *
     * @return the shared instance
     */
    public static KeyRequestHandler getSharedInstance() {
        if (null == mInstance) {
            mInstance = new KeyRequestHandler();
        }

        return mInstance;
    }

    /**
     * Constructor
     */
    private KeyRequestHandler() {
    }

    /**
     * Handle incoming key request.
     *
     * @param keyRequest the key request.
     */
    public void handleKeyRequest(IncomingRoomKeyRequest keyRequest) {
        String userId = keyRequest.mUserId;
        String deviceId = keyRequest.mDeviceId;
        String requestId = keyRequest.mRequestId;

        if (TextUtils.isEmpty(userId) || TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(requestId)) {
            Log.e(LOG_TAG, "## handleKeyRequest() : invalid parameters");
            return;
        }

        if (!mPendingKeyRequests.containsKey(userId)) {
            mPendingKeyRequests.put(userId, new HashMap<String, List<IncomingRoomKeyRequest>>());
        }

        if (!mPendingKeyRequests.get(userId).containsKey(deviceId)) {
            mPendingKeyRequests.get(userId).put(deviceId, new ArrayList<IncomingRoomKeyRequest>());
        }

        List<IncomingRoomKeyRequest> requests = mPendingKeyRequests.get(userId).get(deviceId);

        if (requests.contains(keyRequest)) {
            Log.d(LOG_TAG, "## handleKeyRequest() : Already have this key request, ignoring");
            return;
        }

        requests.add(keyRequest);

        if (null != mAlertDialog) {
            // ignore for now
            Log.d(LOG_TAG, "## handleKeyRequest() : Key request, but we already have a dialog open");
            return;
        }

        processNextRequest();
    }

    /**
     * Manage a cancellation request.
     *
     * @param cancellation the cancellation request.
     */
    public void handleKeyRequestCancellation(IncomingRoomKeyRequestCancellation cancellation) {
        // see if we can find the request in the queue
        String userId = cancellation.mUserId;
        String deviceId = cancellation.mDeviceId;
        String requestId = cancellation.mRequestId;

        if (TextUtils.isEmpty(userId) || TextUtils.isEmpty(deviceId) || TextUtils.isEmpty(requestId)) {
            Log.e(LOG_TAG, "## handleKeyRequestCancellation() : invalid parameters");
            return;
        }

        if (TextUtils.equals(userId, mCurrentUser) && TextUtils.equals(deviceId, mCurrentDevice)) {
            Log.d(LOG_TAG, "## handleKeyRequestCancellation() : room key request cancellation for the user we currently have a dialog open for ");

            if (null != mAlertDialog) {
                mAlertDialog.cancel();
            }
            return;
        }

        if (!mPendingKeyRequests.containsKey(userId)) {
            return;
        }

        List<IncomingRoomKeyRequest> requests = mPendingKeyRequests.get(userId).get(deviceId);

        if (null == requests) {
            return;
        }

        if (!requests.contains(cancellation)) {
            return;
        }

        Log.d(LOG_TAG, "## handleKeyRequestCancellation() : Forgetting room key request");

        requests.remove(cancellation);

        if (requests.isEmpty()) {
            mPendingKeyRequests.get(userId).remove(deviceId);
        }

        if (mPendingKeyRequests.get(userId).isEmpty()) {
            mPendingKeyRequests.remove(userId);
        }
    }

    /**
     * Manage the next request
     */
    public void processNextRequest() {
        if ((null != mCurrentUser) || (null != mCurrentDevice)) {
            Log.d(LOG_TAG, "## processNextRequest() : nothing to do");
            return;
        }

        if (mPendingKeyRequests.isEmpty()) {
            return;
        }

        String userId = mPendingKeyRequests.keySet().iterator().next();

        if (mPendingKeyRequests.get(userId).isEmpty()) {
            return;
        }

        String deviceId = mPendingKeyRequests.get(userId).keySet().iterator().next();

        Log.d(LOG_TAG, "## processNextRequest() : Starting KeyShareDialog for " + userId + ":" + deviceId);

        mCurrentUser = userId;
        mCurrentDevice = deviceId;

        initKeyShareDialog();
    }

    /**
     * The Key share dialog is closed.
     *
     * @param share          true to share the key.
     * @param ignoreRequests true to make the crypto module forget all keyshare requests coming from the current user's device.
     */
    private void onDisplayKeyShareDialogClose(boolean share, boolean ignoreRequests) {
        // sanity check
        if (mPendingKeyRequests.containsKey(mCurrentUser)) {

            List<IncomingRoomKeyRequest> requests = mPendingKeyRequests.get(mCurrentUser).get(mCurrentDevice);

            if (share) {
                for (IncomingRoomKeyRequest req : requests) {
                    if (null != req.mShare) {
                        try {
                            req.mShare.run();
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## onDisplayKeyShareDialogClose() : req.mShare failed " + e.getMessage(), e);
                        }
                    }
                }
            } else if (ignoreRequests) {
                for (IncomingRoomKeyRequest req : requests) {
                    if (null != req.mIgnore) {
                        try {
                            req.mIgnore.run();
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## onDisplayKeyShareDialogClose() : req.mIgnore failed " + e.getMessage(), e);
                        }
                    }
                }
            }

            mPendingKeyRequests.get(mCurrentUser).remove(mCurrentDevice);

            if (mPendingKeyRequests.get(mCurrentUser).isEmpty()) {
                mPendingKeyRequests.remove(mCurrentUser);
            }
        }

        mCurrentUser = null;
        mCurrentDevice = null;
        mAlertDialog = null;

        processNextRequest();
    }

    /**
     * Prepare the share key dialog
     */
    private void initKeyShareDialog() {
        if (null == VectorApp.getCurrentActivity()) {
            // wait until an activity is ready
            mCurrentUser = null;
            mCurrentDevice = null;
            return;
        }

        // TODO manage multi sessions
        final MXSession session = Matrix.getInstance(VectorApp.getInstance()).getDefaultSession();

        session.getCrypto().getDeviceList().downloadKeys(Arrays.asList(mCurrentUser), false, new ApiCallback<MXUsersDevicesMap<MXDeviceInfo>>() {
            @Override
            public void onSuccess(MXUsersDevicesMap<MXDeviceInfo> devicesMap) {
                final MXDeviceInfo deviceInfo = devicesMap.getObject(mCurrentDevice, mCurrentUser);

                if (null == deviceInfo) {
                    Log.e(LOG_TAG, "## displayKeyShareDialog() : No details found for device " + mCurrentUser + ":" + mCurrentDevice);
                    onDisplayKeyShareDialogClose(false, false);
                    return;
                }

                if (deviceInfo.isUnknown()) {
                    session.getCrypto()
                            .setDeviceVerification(MXDeviceInfo.DEVICE_VERIFICATION_UNVERIFIED, mCurrentDevice, mCurrentUser, new SimpleApiCallback<Void>() {
                                @Override
                                public void onSuccess(Void info) {
                                    displayKeyShareDialog(session, deviceInfo, true);
                                }
                            });
                } else {
                    displayKeyShareDialog(session, deviceInfo, false);
                }
            }

            private void onError(String errorMessage) {
                Log.e(LOG_TAG, "## displayKeyShareDialog : downloadKeys failed " + errorMessage);
                onDisplayKeyShareDialogClose(false, false);
            }

            @Override
            public void onNetworkError(Exception e) {
                onError(e.getMessage());
            }

            @Override
            public void onMatrixError(MatrixError e) {
                onError(e.getMessage());
            }

            @Override
            public void onUnexpectedError(Exception e) {
                onError(e.getMessage());
            }
        });
    }

    /**
     * Display the share key dialog
     *
     * @param session      the session
     * @param deviceInfo   the device info
     * @param wasNewDevice true if the device was a new one.
     */
    private void displayKeyShareDialog(final MXSession session, final MXDeviceInfo deviceInfo, final boolean wasNewDevice) {
        if (null == VectorApp.getCurrentActivity()) {
            // wait that an activity is ready
            mCurrentUser = null;
            mCurrentDevice = null;
            return;
        }

        final Activity activity = VectorApp.getCurrentActivity();

        String deviceName = TextUtils.isEmpty(deviceInfo.displayName()) ? deviceInfo.deviceId : deviceInfo.displayName();
        String dialogText = wasNewDevice ? activity.getString(R.string.you_added_a_new_device, deviceName)
                : activity.getString(R.string.your_unverified_device_requesting, deviceName);

        mAlertDialog = new AlertDialog.Builder(activity)
                .setMessage(dialogText)
                .setCancelable(false)
                .setNegativeButton(R.string.ignore_request, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        onDisplayKeyShareDialogClose(false, true);
                    }
                })
                .setNeutralButton(R.string.share_without_verifying,
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                onDisplayKeyShareDialogClose(true, false);
                            }
                        })
                // Tchap: disable the verification option
//                .setPositiveButton(R.string.start_verification,
//                        new DialogInterface.OnClickListener() {
//                            public void onClick(final DialogInterface dialog, int id) {
//                                CommonActivityUtils.displayDeviceVerificationDialog(deviceInfo, mCurrentUser, session, activity, new SimpleApiCallback<Void>() {
//                                    @Override
//                                    public void onSuccess(Void info) {
//                                        if (deviceInfo.isVerified()) {
//                                            onDisplayKeyShareDialogClose(true, false);
//                                        } else {
//                                            displayKeyShareDialog(session, deviceInfo, wasNewDevice);
//                                        }
//                                    }
//                                });
//                            }
//                        })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override
                    public void onCancel(DialogInterface dialogInterface) {
                        onDisplayKeyShareDialogClose(false, true);
                    }
                })
                .show();
    }
}
