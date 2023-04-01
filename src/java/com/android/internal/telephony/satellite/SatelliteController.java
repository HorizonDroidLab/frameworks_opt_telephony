/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.telephony.satellite;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.telephony.Rlog;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.satellite.ISatelliteDatagramCallback;
import android.telephony.satellite.ISatelliteProvisionStateCallback;
import android.telephony.satellite.ISatelliteStateCallback;
import android.telephony.satellite.ISatelliteTransmissionUpdateCallback;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.IIntegerConsumer;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.satellite.metrics.ControllerMetricsStats;
import com.android.internal.telephony.satellite.metrics.ProvisionMetricsStats;
import com.android.internal.telephony.satellite.metrics.SessionMetricsStats;
import com.android.internal.util.FunctionalUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Satellite controller is the backend service of
 * {@link android.telephony.satellite.SatelliteManager}.
 */
public class SatelliteController extends Handler {
    private static final String TAG = "SatelliteController";
    /** Whether enabling verbose debugging message or not. */
    private static final boolean DBG = false;
    /** File used to store shared preferences related to satellite. */
    public static final String SATELLITE_SHARED_PREF = "satellite_shared_pref";


    /** Message codes used in handleMessage() */
    //TODO: Move the Commands and events related to position updates to PointingAppController
    private static final int CMD_START_SATELLITE_TRANSMISSION_UPDATES = 1;
    private static final int EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE = 2;
    private static final int CMD_STOP_SATELLITE_TRANSMISSION_UPDATES = 3;
    private static final int EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE = 4;
    private static final int CMD_PROVISION_SATELLITE_SERVICE = 7;
    private static final int EVENT_PROVISION_SATELLITE_SERVICE_DONE = 8;
    private static final int CMD_DEPROVISION_SATELLITE_SERVICE = 9;
    private static final int EVENT_DEPROVISION_SATELLITE_SERVICE_DONE = 10;
    private static final int CMD_SET_SATELLITE_ENABLED = 11;
    private static final int EVENT_SET_SATELLITE_ENABLED_DONE = 12;
    private static final int CMD_IS_SATELLITE_ENABLED = 13;
    private static final int EVENT_IS_SATELLITE_ENABLED_DONE = 14;
    private static final int CMD_IS_SATELLITE_SUPPORTED = 15;
    private static final int EVENT_IS_SATELLITE_SUPPORTED_DONE = 16;
    private static final int CMD_GET_SATELLITE_CAPABILITIES = 17;
    private static final int EVENT_GET_SATELLITE_CAPABILITIES_DONE = 18;
    private static final int CMD_IS_SATELLITE_COMMUNICATION_ALLOWED = 19;
    private static final int EVENT_IS_SATELLITE_COMMUNICATION_ALLOWED_DONE = 20;
    private static final int CMD_GET_TIME_SATELLITE_NEXT_VISIBLE = 21;
    private static final int EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE = 22;
    private static final int EVENT_RADIO_STATE_CHANGED = 23;
    private static final int CMD_IS_SATELLITE_PROVISIONED = 24;
    private static final int EVENT_IS_SATELLITE_PROVISIONED_DONE = 25;
    private static final int EVENT_SATELLITE_PROVISION_STATE_CHANGED = 26;
    private static final int EVENT_PENDING_DATAGRAMS = 27;
    private static final int EVENT_SATELLITE_MODEM_STATE_CHANGED = 28;

    @NonNull private static SatelliteController sInstance;
    @NonNull private final Context mContext;
    @NonNull private final SatelliteModemInterface mSatelliteModemInterface;
    @NonNull private SatelliteSessionController mSatelliteSessionController;
    @NonNull private final PointingAppController mPointingAppController;
    @NonNull private final DatagramController mDatagramController;
    @NonNull private final ControllerMetricsStats mControllerMetricsStats;
    @NonNull private final ProvisionMetricsStats mProvisionMetricsStats;
    private SharedPreferences mSharedPreferences = null;
    private final CommandsInterface mCi;

    BluetoothAdapter mBluetoothAdapter = null;
    WifiManager mWifiManager = null;
    /** Shared preference key to store the existing state of Bluetooth and Wifi*/
    private static final String KEY_BLUETOOTH_DISABLED_BY_SCO = "bluetooth_disabled_by_sco";
    private static final String KEY_WIFI_DISABLED_BY_SCO = "wifi_disabled_by_sco";
    boolean mDisabledBTFlag = false;
    boolean mDisabledWifiFlag = false;
    private final AtomicBoolean mRegisteredForProvisionStateChangedWithSatelliteService =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForProvisionStateChangedWithPhone =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForPendingDatagramCountWithSatelliteService =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForPendingDatagramCountWithPhone =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteModemStateChangedWithSatelliteService =
            new AtomicBoolean(false);
    private final AtomicBoolean mRegisteredForSatelliteModemStateChangedWithPhone =
            new AtomicBoolean(false);
    /**
     * Map key: subId, value: callback to get error code of the provision request.
     */
    private final ConcurrentHashMap<Integer, Consumer<Integer>> mSatelliteProvisionCallbacks =
            new ConcurrentHashMap<>();

    /**
     * Map key: binder of the callback, value: callback to receive provision state changed events.
     */
    private final ConcurrentHashMap<IBinder, ISatelliteProvisionStateCallback>
            mSatelliteProvisionStateChangedListeners = new ConcurrentHashMap<>();

    private Boolean mIsSatelliteSupported = null;
    private final Object mIsSatelliteSupportedLock = new Object();
    private boolean mIsDemoModeEnabled = false;
    private Boolean mIsSatelliteEnabled = null;
    private final Object mIsSatelliteEnabledLock = new Object();
    private Boolean mIsSatelliteProvisioned = null;
    private final Object mIsSatelliteProvisionedLock = new Object();
    private SatelliteCapabilities mSatelliteCapabilities;
    private final Object mSatelliteCapabilitiesLock = new Object();
    private boolean mNeedsSatellitePointing = false;
    private final Object mNeedsSatellitePointingLock = new Object();

    /**
     * @return The singleton instance of SatelliteController.
     */
    public static SatelliteController getInstance() {
        if (sInstance == null) {
            loge("SatelliteController was not yet initialized.");
        }
        return sInstance;
    }

    /**
     * Create the SatelliteController singleton instance.
     * @param context The Context to use to create the SatelliteController.
     */
    public static void make(@NonNull Context context) {
        if (sInstance == null) {
            HandlerThread satelliteThread = new HandlerThread(TAG);
            satelliteThread.start();
            sInstance = new SatelliteController(context, satelliteThread.getLooper());
        }
    }

    /**
     * Create a SatelliteController to act as a backend service of
     * {@link android.telephony.satellite.SatelliteManager}
     *
     * @param context The Context for the SatelliteController.
     * @param looper The looper for the handler. It does not run on main thread.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public SatelliteController(@NonNull Context context, @NonNull Looper looper) {
        super(looper);

        mContext = context;
        Phone phone = SatelliteServiceUtils.getPhone();
        mCi = phone.mCi;
        // Create the SatelliteModemInterface singleton, which is used to manage connections
        // to the satellite service and HAL interface.
        mSatelliteModemInterface = SatelliteModemInterface.make(mContext, this);

        // Create the PointingUIController singleton,
        // which is used to manage interactions with PointingUI app.
        mPointingAppController = PointingAppController.make(mContext);

        // Create the SatelliteControllerMetrics to report controller metrics
        // should be called before making DatagramController
        mControllerMetricsStats = ControllerMetricsStats.make(mContext);
        mProvisionMetricsStats = ProvisionMetricsStats.getOrCreateInstance();

        // Create the DatagramController singleton,
        // which is used to send and receive satellite datagrams.
        mDatagramController = DatagramController.make(mContext, looper, mPointingAppController);

        requestIsSatelliteSupported(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                new ResultReceiver(this) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        logd("requestIsSatelliteSupported: resultCode=" + resultCode);
                    }
                });
        mCi.registerForRadioStateChanged(this, EVENT_RADIO_STATE_CHANGED, null);
        registerForSatelliteProvisionStateChanged();
        registerForPendingDatagramCount();
        registerForSatelliteModemStateChanged();
        try {
            mSharedPreferences = mContext.getSharedPreferences(SATELLITE_SHARED_PREF,
                    Context.MODE_PRIVATE);
        } catch (Exception e) {
            loge("Cannot get default shared preferences: " + e);
        }
        //if BT and Wifi was already disabled by Satellite Controller, reset
        if ((mSharedPreferences != null) &&
                (mSharedPreferences.contains(KEY_BLUETOOTH_DISABLED_BY_SCO) ||
                mSharedPreferences.contains(KEY_WIFI_DISABLED_BY_SCO))) {
            /**
             * read the flag from shared preference to check if the Bluetooth and Wifi was disabled
             * by Satellite Controller
             */
            mDisabledBTFlag = mSharedPreferences
                    .getBoolean(KEY_BLUETOOTH_DISABLED_BY_SCO, false);
            mDisabledWifiFlag = mSharedPreferences
                    .getBoolean(KEY_WIFI_DISABLED_BY_SCO, false);
            checkAndEnableBluetoothWifiState();
        }
    }

    private void internalInit() {

    }

    private static final class SatelliteControllerHandlerRequest {
        /** The argument to use for the request */
        public @NonNull Object argument;
        /** The caller needs to specify the phone to be used for the request */
        public @NonNull Phone phone;
        /** The result of the request that is run on the main thread */
        public @Nullable Object result;

        SatelliteControllerHandlerRequest(Object argument, Phone phone) {
            this.argument = argument;
            this.phone = phone;
        }
    }

    private static final class RequestSatelliteEnabledArgument {
        public boolean enableSatellite;
        public boolean enableDemoMode;
        @NonNull public Consumer<Integer> callback;

        RequestSatelliteEnabledArgument(boolean enableSatellite, boolean enableDemoMode,
                Consumer<Integer> callback) {
            this.enableSatellite = enableSatellite;
            this.enableDemoMode = enableDemoMode;
            this.callback = callback;
        }
    }

    private static final class ProvisionSatelliteServiceArgument {
        @NonNull public String token;
        @NonNull public byte[] provisionData;
        @NonNull public Consumer<Integer> callback;
        public int subId;

        ProvisionSatelliteServiceArgument(String token, byte[] provisionData,
                Consumer<Integer> callback, int subId) {
            this.token = token;
            this.provisionData = provisionData;
            this.callback = callback;
            this.subId = subId;
        }
    }

    /**
     * Arguments to send to SatelliteTransmissionUpdate registrants
     */
    public static final class SatelliteTransmissionUpdateArgument {
        @NonNull public Consumer<Integer> errorCallback;
        @NonNull public ISatelliteTransmissionUpdateCallback callback;
        public int subId;

        SatelliteTransmissionUpdateArgument(Consumer<Integer> errorCallback,
                ISatelliteTransmissionUpdateCallback callback, int subId) {
            this.errorCallback = errorCallback;
            this.callback = callback;
            this.subId = subId;
        }
    }

    @Override
    public void handleMessage(Message msg) {
        SatelliteControllerHandlerRequest request;
        Message onCompleted;
        AsyncResult ar;

        switch(msg.what) {
            case CMD_START_SATELLITE_TRANSMISSION_UPDATES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                        obtainMessage(EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE, request);
                mPointingAppController.startSatelliteTransmissionUpdates(onCompleted,
                        request.phone);
                break;
            }

            case EVENT_START_SATELLITE_TRANSMISSION_UPDATES_DONE: {
                handleStartSatelliteTransmissionUpdatesDone((AsyncResult) msg.obj);
                break;
            }

            case CMD_STOP_SATELLITE_TRANSMISSION_UPDATES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                        obtainMessage(EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE, request);
                mPointingAppController.stopSatelliteTransmissionUpdates(onCompleted, request.phone);
                break;
            }

            case EVENT_STOP_SATELLITE_TRANSMISSION_UPDATES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "stopSatelliteTransmissionUpdates");
                ((Consumer<Integer>) request.argument).accept(error);
                break;
            }

            case CMD_PROVISION_SATELLITE_SERVICE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                ProvisionSatelliteServiceArgument argument =
                        (ProvisionSatelliteServiceArgument) request.argument;
                if (mSatelliteProvisionCallbacks.containsKey(argument.subId)) {
                    argument.callback.accept(
                            SatelliteManager.SATELLITE_SERVICE_PROVISION_IN_PROGRESS);
                    notifyRequester(request);
                    break;
                }
                mSatelliteProvisionCallbacks.put(argument.subId, argument.callback);
                onCompleted = obtainMessage(EVENT_PROVISION_SATELLITE_SERVICE_DONE, request);
                // Log the current time for provision triggered
                mProvisionMetricsStats.setProvisioningStartTime();
                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface.provisionSatelliteService(argument.token,
                            argument.provisionData, onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.provisionSatelliteService(onCompleted, argument.token);
                } else {
                    loge("provisionSatelliteService: No phone object");
                    argument.callback.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
                    notifyRequester(request);
                    mProvisionMetricsStats
                            .setResultCode(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE)
                            .reportProvisionMetrics();
                    mControllerMetricsStats.reportProvisionCount(
                            SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
                }
                break;
            }

            case EVENT_PROVISION_SATELLITE_SERVICE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "provisionSatelliteService");
                handleEventProvisionSatelliteServiceDone(
                        (ProvisionSatelliteServiceArgument) request.argument, errorCode);
                notifyRequester(request);
                break;
            }

            case CMD_DEPROVISION_SATELLITE_SERVICE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                ProvisionSatelliteServiceArgument argument =
                        (ProvisionSatelliteServiceArgument) request.argument;
                onCompleted = obtainMessage(EVENT_DEPROVISION_SATELLITE_SERVICE_DONE, request);
                if (argument.callback != null) {
                    mProvisionMetricsStats.setProvisioningStartTime();
                }
                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface
                            .deprovisionSatelliteService(argument.token, onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.deprovisionSatelliteService(onCompleted, argument.token);
                } else {
                    loge("deprovisionSatelliteService: No phone object");
                    if (argument.callback != null) {
                        argument.callback.accept(
                                SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
                        mProvisionMetricsStats
                                .setResultCode(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE)
                                .reportProvisionMetrics();
                        mControllerMetricsStats.reportDeprovisionCount(
                                SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
                    }
                }
                break;
            }

            case EVENT_DEPROVISION_SATELLITE_SERVICE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                        "deprovisionSatelliteService");
                handleEventDeprovisionSatelliteServiceDone(
                        (ProvisionSatelliteServiceArgument) request.argument, errorCode);
                break;
            }

            case CMD_SET_SATELLITE_ENABLED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                handleSatelliteEnabled(request);
                break;
            }

            case EVENT_SET_SATELLITE_ENABLED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                RequestSatelliteEnabledArgument argument =
                        (RequestSatelliteEnabledArgument) request.argument;
                int error =  SatelliteServiceUtils.getSatelliteError(ar, "setSatelliteEnabled");
                if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                    if (argument.enableSatellite) {
                        //If satellite mode is enabled successfully, disable Bluetooth and wifi
                        disableBluetoothWifiState();
                    } else {
                        //Disabled satellite mode, Reset BT and Wifi if previously changed here
                        checkAndEnableBluetoothWifiState();
                    }
                    /**
                     * TODO for NTN-based satellites: Check if satellite is acquired.
                     */
                    if (mNeedsSatellitePointing) {
                        mPointingAppController.startPointingUI(false);
                    }
                    mIsDemoModeEnabled = argument.enableDemoMode;
                    updateSatelliteEnabledState(
                            argument.enableSatellite, "EVENT_SET_SATELLITE_ENABLED_DONE");
                }
                argument.callback.accept(error);

                if (argument.enableSatellite) {
                    if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                        mControllerMetricsStats.onSatelliteEnabled();
                        mControllerMetricsStats.reportServiceEnablementSuccessCount();
                    } else {
                        mControllerMetricsStats.reportServiceEnablementFailCount();
                    }
                    SessionMetricsStats.getInstance()
                            .setInitializationResult(error)
                            .setRadioTechnology(SatelliteManager.NT_RADIO_TECHNOLOGY_PROPRIETARY)
                            .reportSessionMetrics();
                } else {
                    mControllerMetricsStats.onSatelliteDisabled();
                }
                break;
            }

            case CMD_IS_SATELLITE_ENABLED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_IS_SATELLITE_ENABLED_DONE, request);
                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface.requestIsSatelliteEnabled(onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.isSatellitePowerOn(onCompleted);
                } else {
                    loge("isSatelliteEnabled: No phone object");
                    ((ResultReceiver) request.argument).send(
                            SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
                }
                break;
            }

            case EVENT_IS_SATELLITE_ENABLED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "isSatelliteEnabled");
                Bundle bundle = new Bundle();
                if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                    if (ar.result == null) {
                        loge("isSatelliteEnabled: result is null");
                        error = SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean enabled = ((int[]) ar.result)[0] == 1;
                        if (DBG) logd("isSatelliteEnabled: " + enabled);
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_ENABLED, enabled);
                        updateSatelliteEnabledState(enabled, "EVENT_IS_SATELLITE_ENABLED_DONE");
                    }
                } else if (error == SatelliteManager.SATELLITE_REQUEST_NOT_SUPPORTED) {
                    updateSatelliteSupportedState(false);
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_IS_SATELLITE_SUPPORTED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_IS_SATELLITE_SUPPORTED_DONE, request);

                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface.requestIsSatelliteSupported(onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.isSatelliteSupported(onCompleted);
                } else {
                    loge("isSatelliteSupported: No phone object");
                    ((ResultReceiver) request.argument).send(
                            SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
                }
                break;
            }

            case EVENT_IS_SATELLITE_SUPPORTED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar, "isSatelliteSupported");
                Bundle bundle = new Bundle();
                if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                    if (ar.result == null) {
                        loge("isSatelliteSupported: result is null");
                        error = SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean supported = (boolean) ar.result;
                        if (DBG) logd("isSatelliteSupported: " + supported);
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, supported);
                        updateSatelliteSupportedState(supported);
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_GET_SATELLITE_CAPABILITIES: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_GET_SATELLITE_CAPABILITIES_DONE, request);
                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface.requestSatelliteCapabilities(onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.getSatelliteCapabilities(onCompleted);
                } else {
                    loge("getSatelliteCapabilities: No phone object");
                    ((ResultReceiver) request.argument).send(
                            SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
                }
                break;
            }

            case EVENT_GET_SATELLITE_CAPABILITIES_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "getSatelliteCapabilities");
                Bundle bundle = new Bundle();
                if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                    if (ar.result == null) {
                        loge("getSatelliteCapabilities: result is null");
                        error = SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
                    } else {
                        SatelliteCapabilities capabilities = (SatelliteCapabilities) ar.result;
                        synchronized (mNeedsSatellitePointingLock) {
                            mNeedsSatellitePointing = capabilities.isPointingRequired();
                        }
                        if (DBG) logd("getSatelliteCapabilities: " + capabilities);
                        bundle.putParcelable(SatelliteManager.KEY_SATELLITE_CAPABILITIES,
                                capabilities);
                        synchronized (mSatelliteCapabilitiesLock) {
                            mSatelliteCapabilities = capabilities;
                        }
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_IS_SATELLITE_COMMUNICATION_ALLOWED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted =
                        obtainMessage(EVENT_IS_SATELLITE_COMMUNICATION_ALLOWED_DONE, request);
                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface
                            .requestIsSatelliteCommunicationAllowedForCurrentLocation(
                                    onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.isSatelliteCommunicationAllowedForCurrentLocation(onCompleted);
                } else {
                    loge("isSatelliteCommunicationAllowedForCurrentLocation: No phone object");
                    ((ResultReceiver) request.argument).send(
                            SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
                }
                break;
            }

            case EVENT_IS_SATELLITE_COMMUNICATION_ALLOWED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "isSatelliteCommunicationAllowedForCurrentLocation");
                Bundle bundle = new Bundle();
                if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                    if (ar.result == null) {
                        loge("isSatelliteCommunicationAllowedForCurrentLocation: result is null");
                        error = SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean communicationAllowed = (boolean) ar.result;
                        if (DBG) {
                            logd("isSatelliteCommunicationAllowedForCurrentLocation: "
                                    + communicationAllowed);
                        }
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_COMMUNICATION_ALLOWED,
                                communicationAllowed);
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case CMD_GET_TIME_SATELLITE_NEXT_VISIBLE: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE,
                        request);
                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface
                            .requestTimeForNextSatelliteVisibility(onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.requestTimeForNextSatelliteVisibility(onCompleted);
                } else {
                    loge("requestTimeForNextSatelliteVisibility: No phone object");
                    ((ResultReceiver) request.argument).send(
                            SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
                }
                break;
            }

            case EVENT_GET_TIME_SATELLITE_NEXT_VISIBLE_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error = SatelliteServiceUtils.getSatelliteError(ar,
                        "requestTimeForNextSatelliteVisibility");
                Bundle bundle = new Bundle();
                if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                    if (ar.result == null) {
                        loge("requestTimeForNextSatelliteVisibility: result is null");
                        error = SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
                    } else {
                        int nextVisibilityDuration = ((int[]) ar.result)[0];
                        if (DBG) {
                            logd("requestTimeForNextSatelliteVisibility: " +
                                    nextVisibilityDuration);
                        }
                        bundle.putInt(SatelliteManager.KEY_SATELLITE_NEXT_VISIBILITY,
                                nextVisibilityDuration);
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case EVENT_RADIO_STATE_CHANGED: {
                if (mCi.getRadioState() == TelephonyManager.RADIO_POWER_OFF
                        || mCi.getRadioState() == TelephonyManager.RADIO_POWER_UNAVAILABLE) {
                    logd("Radio State Changed to " + mCi.getRadioState());
                    IIntegerConsumer errorCallback = new IIntegerConsumer.Stub() {
                        @Override
                        public void accept(int result) {
                            loge("Failed to Disable Satellite Mode, Error: " + result);
                        }
                    };
                    Phone phone = SatelliteServiceUtils.getPhone();
                    Consumer<Integer> result = FunctionalUtils
                            .ignoreRemoteException(errorCallback::accept);
                    RequestSatelliteEnabledArgument message =
                            new RequestSatelliteEnabledArgument(false, false, result);
                    request = new SatelliteControllerHandlerRequest(message, phone);
                    handleSatelliteEnabled(request);
                } else {
                    if (!mSatelliteModemInterface.isSatelliteServiceSupported()) {
                        synchronized (mIsSatelliteSupportedLock) {
                            if (mIsSatelliteSupported == null) {
                                ResultReceiver receiver = new ResultReceiver(this) {
                                    @Override
                                    protected void onReceiveResult(
                                            int resultCode, Bundle resultData) {
                                        logd("requestIsSatelliteSupported: resultCode="
                                                + resultCode);
                                    }
                                };
                                requestIsSatelliteSupported(
                                        SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, receiver);
                            }
                        }
                    } else {
                        logd("EVENT_RADIO_STATE_CHANGED: Satellite vendor service is supported."
                                + " Ignored the event");
                    }
                }
                break;
            }

            case CMD_IS_SATELLITE_PROVISIONED: {
                request = (SatelliteControllerHandlerRequest) msg.obj;
                onCompleted = obtainMessage(EVENT_IS_SATELLITE_PROVISIONED_DONE, request);
                if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
                    mSatelliteModemInterface.requestIsSatelliteProvisioned(onCompleted);
                    break;
                }
                Phone phone = request.phone;
                if (phone != null) {
                    phone.isSatelliteProvisioned(onCompleted);
                } else {
                    loge("isSatelliteProvisioned: No phone object");
                    ((ResultReceiver) request.argument).send(
                            SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
                }
                break;
            }

            case EVENT_IS_SATELLITE_PROVISIONED_DONE: {
                ar = (AsyncResult) msg.obj;
                request = (SatelliteControllerHandlerRequest) ar.userObj;
                int error =  SatelliteServiceUtils.getSatelliteError(ar,
                        "isSatelliteProvisioned");
                Bundle bundle = new Bundle();
                if (error == SatelliteManager.SATELLITE_ERROR_NONE) {
                    if (ar.result == null) {
                        loge("isSatelliteProvisioned: result is null");
                        error = SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
                    } else {
                        boolean provisioned = ((int[]) ar.result)[0] == 1;
                        if (DBG) logd("isSatelliteProvisioned: " + provisioned);
                        bundle.putBoolean(SatelliteManager.KEY_SATELLITE_PROVISIONED, provisioned);
                        synchronized (mIsSatelliteProvisionedLock) {
                            mIsSatelliteProvisioned = provisioned;
                        }
                    }
                }
                ((ResultReceiver) request.argument).send(error, bundle);
                break;
            }

            case EVENT_SATELLITE_PROVISION_STATE_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    loge("EVENT_SATELLITE_PROVISION_STATE_CHANGED: result is null");
                } else {
                    handleEventSatelliteProvisionStateChanged((boolean) ar.result);
                }
                break;

            case EVENT_PENDING_DATAGRAMS:
                logd("Received EVENT_PENDING_DATAGRAMS");
                IIntegerConsumer internalCallback = new IIntegerConsumer.Stub() {
                    @Override
                    public void accept(int result) {
                        logd("pollPendingSatelliteDatagram result: " + result);
                    }
                };
                pollPendingSatelliteDatagrams(
                        SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, internalCallback);
                break;

            case EVENT_SATELLITE_MODEM_STATE_CHANGED:
                ar = (AsyncResult) msg.obj;
                if (ar.result == null) {
                    loge("EVENT_SATELLITE_MODEM_STATE_CHANGED: result is null");
                } else {
                    handleEventSatelliteModemStateChanged((int) ar.result);
                }
                break;

            default:
                Log.w(TAG, "SatelliteControllerHandler: unexpected message code: " +
                        msg.what);
                break;
        }
    }

    private void notifyRequester(SatelliteControllerHandlerRequest request) {
        synchronized (request) {
            request.notifyAll();
        }
    }

    /**
     * Request to enable or disable the satellite modem and demo mode. If the satellite modem is
     * enabled, this will also disable the cellular modem, and if the satellite modem is disabled,
     * this will also re-enable the cellular modem.
     *
     * @param subId The subId of the subscription to set satellite enabled for.
     * @param enableSatellite {@code true} to enable the satellite modem and
     *                        {@code false} to disable.
     * @param enableDemoMode {@code true} to enable demo mode and {@code false} to disable.
     * @param callback The callback to get the error code of the request.
     */
    public void requestSatelliteEnabled(int subId, boolean enableSatellite, boolean enableDemoMode,
            @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteSupported) {
            result.accept(SatelliteManager.SATELLITE_NOT_SUPPORTED);
            return;
        }

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteProvisioned) {
            result.accept(SatelliteManager.SATELLITE_SERVICE_NOT_PROVISIONED);
            return;
        }

        sendRequestAsync(CMD_SET_SATELLITE_ENABLED,
                new RequestSatelliteEnabledArgument(enableSatellite, enableDemoMode, result),
                SatelliteServiceUtils.getPhone());
    }

    /**
     * Request to get whether the satellite modem is enabled.
     *
     * @param subId The subId of the subscription to check whether satellite is enabled for.
     * @param result The result receiver that returns whether the satellite modem is enabled
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsSatelliteEnabled(int subId, @NonNull ResultReceiver result) {
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteSupported) {
            result.send(SatelliteManager.SATELLITE_NOT_SUPPORTED, null);
            return;
        }

        synchronized (mIsSatelliteEnabledLock) {
            if (mIsSatelliteEnabled != null) {
                /* We have already successfully queried the satellite modem. */
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_ENABLED, mIsSatelliteEnabled);
                result.send(SatelliteManager.SATELLITE_ERROR_NONE, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_IS_SATELLITE_ENABLED, result, SatelliteServiceUtils.getPhone());
    }

    /**
     * Request to get whether the satellite service demo mode is enabled.
     *
     * @param subId The subId of the subscription to check whether the satellite demo mode
     *              is enabled for.
     * @param result The result receiver that returns whether the satellite demo mode is enabled
     *               if the request is successful or an error code if the request failed.
     */
    public void requestIsDemoModeEnabled(int subId, @NonNull ResultReceiver result) {
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteSupported) {
            result.send(SatelliteManager.SATELLITE_NOT_SUPPORTED, null);
            return;
        }

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteProvisioned) {
            result.send(SatelliteManager.SATELLITE_SERVICE_NOT_PROVISIONED, null);
            return;
        }

        final Bundle bundle = new Bundle();
        bundle.putBoolean(SatelliteManager.KEY_DEMO_MODE_ENABLED, mIsDemoModeEnabled);
        result.send(SatelliteManager.SATELLITE_ERROR_NONE, bundle);
    }

    /**
     * Request to get whether the satellite service is supported on the device.
     *
     * @param subId The subId of the subscription to check satellite service support for.
     * @param result The result receiver that returns whether the satellite service is supported on
     *               the device if the request is successful or an error code if the request failed.
     */
    public void requestIsSatelliteSupported(int subId, @NonNull ResultReceiver result) {
        synchronized (mIsSatelliteSupportedLock) {
            if (mIsSatelliteSupported != null) {
                /* We have already successfully queried the satellite modem. */
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_SUPPORTED, mIsSatelliteSupported);
                result.send(SatelliteManager.SATELLITE_ERROR_NONE, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_IS_SATELLITE_SUPPORTED, result, SatelliteServiceUtils.getPhone());
    }

    /**
     * Request to get the {@link SatelliteCapabilities} of the satellite service.
     *
     * @param subId The subId of the subscription to get the satellite capabilities for.
     * @param result The result receiver that returns the {@link SatelliteCapabilities}
     *               if the request is successful or an error code if the request failed.
     */
    public void requestSatelliteCapabilities(int subId, @NonNull ResultReceiver result) {
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteSupported) {
            result.send(SatelliteManager.SATELLITE_NOT_SUPPORTED, null);
            return;
        }

        synchronized (mSatelliteCapabilitiesLock) {
            if (mSatelliteCapabilities != null) {
                Bundle bundle = new Bundle();
                bundle.putParcelable(SatelliteManager.KEY_SATELLITE_CAPABILITIES,
                        mSatelliteCapabilities);
                result.send(SatelliteManager.SATELLITE_ERROR_NONE, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_GET_SATELLITE_CAPABILITIES, result, SatelliteServiceUtils.getPhone());
    }

    /**
     * Start receiving satellite transmission updates.
     * This can be called by the pointing UI when the user starts pointing to the satellite.
     * Modem should continue to report the pointing input as the device or satellite moves.
     *
     * @param subId The subId of the subscription to start satellite transmission updates for.
     * @param errorCallback The callback to get the error code of the request.
     * @param callback The callback to notify of satellite transmission updates.
     */
    public void startSatelliteTransmissionUpdates(int subId,
            @NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(errorCallback::accept);
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteSupported) {
            result.accept(SatelliteManager.SATELLITE_NOT_SUPPORTED);
            return;
        }

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteProvisioned) {
            result.accept(SatelliteManager.SATELLITE_SERVICE_NOT_PROVISIONED);
            return;
        }

        Phone phone = SatelliteServiceUtils.getPhone();
        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        mPointingAppController.registerForSatelliteTransmissionUpdates(validSubId, callback, phone);
        sendRequestAsync(CMD_START_SATELLITE_TRANSMISSION_UPDATES,
                new SatelliteTransmissionUpdateArgument(result, callback, validSubId), phone);
    }

    /**
     * Stop receiving satellite transmission updates.
     * This can be called by the pointing UI when the user stops pointing to the satellite.
     *
     * @param subId The subId of the subscription to stop satellite transmission updates for.
     * @param errorCallback The callback to get the error code of the request.
     * @param callback The callback that was passed to {@link #startSatelliteTransmissionUpdates(
     *                 int, IIntegerConsumer, ISatelliteTransmissionUpdateCallback)}.
     */
    public void stopSatelliteTransmissionUpdates(int subId, @NonNull IIntegerConsumer errorCallback,
            @NonNull ISatelliteTransmissionUpdateCallback callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(errorCallback::accept);
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteSupported) {
            result.accept(SatelliteManager.SATELLITE_NOT_SUPPORTED);
            return;
        }

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteProvisioned) {
            result.accept(SatelliteManager.SATELLITE_SERVICE_NOT_PROVISIONED);
            return;
        }

        Phone phone = SatelliteServiceUtils.getPhone();
        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        mPointingAppController.unregisterForSatelliteTransmissionUpdates(
                validSubId, result, callback, phone);

        // Even if handler is null - which means there are no listeners, the modem command to stop
        // satellite transmission updates might have failed. The callers might want to retry
        // sending the command. Thus, we always need to send this command to the modem.
        sendRequestAsync(CMD_STOP_SATELLITE_TRANSMISSION_UPDATES, result, phone);
    }

    /**
     * Register the subscription with a satellite provider.
     * This is needed to register the subscription if the provider allows dynamic registration.
     *
     * @param subId The subId of the subscription to be provisioned.
     * @param token The token to be used as a unique identifier for provisioning with satellite
     *              gateway.
     * @param provisionData Data from the provisioning app that can be used by provisioning server
     * @param callback The callback to get the error code of the request.
     *
     * @return The signal transport used by the caller to cancel the provision request,
     *         or {@code null} if the request failed.
     */
    @Nullable public ICancellationSignal provisionSatelliteService(int subId,
            @NonNull String token, @NonNull byte[] provisionData,
            @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return null;
        }
        if (!satelliteSupported) {
            result.accept(SatelliteManager.SATELLITE_NOT_SUPPORTED);
            return null;
        }

        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        if (mSatelliteProvisionCallbacks.containsKey(validSubId)) {
            result.accept(SatelliteManager.SATELLITE_SERVICE_PROVISION_IN_PROGRESS);
            return null;
        }

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned != null && satelliteProvisioned) {
            result.accept(SatelliteManager.SATELLITE_ERROR_NONE);
            return null;
        }

        Phone phone = SatelliteServiceUtils.getPhone();
        sendRequestAsync(CMD_PROVISION_SATELLITE_SERVICE,
                new ProvisionSatelliteServiceArgument(token, provisionData, result, validSubId),
                phone);

        ICancellationSignal cancelTransport = CancellationSignal.createTransport();
        CancellationSignal.fromTransport(cancelTransport).setOnCancelListener(() -> {
            sendRequestAsync(CMD_DEPROVISION_SATELLITE_SERVICE,
                    new ProvisionSatelliteServiceArgument(token, provisionData, null,
                            validSubId), phone);
            mProvisionMetricsStats.setIsCanceled(true);
        });
        return cancelTransport;
    }

    /**
     * Unregister the device/subscription with the satellite provider.
     * This is needed if the provider allows dynamic registration. Once deprovisioned,
     * {@link android.telephony.satellite.SatelliteProvisionStateCallback
     * #onSatelliteProvisionStateChanged(boolean)}
     * should report as deprovisioned.
     *
     * @param subId The subId of the subscription to be deprovisioned.
     * @param token The token of the device/subscription to be deprovisioned.
     * @param callback The callback to get the error code of the request.
     */
    public void deprovisionSatelliteService(int subId,
            @NonNull String token, @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteSupported) {
            result.accept(SatelliteManager.SATELLITE_NOT_SUPPORTED);
            return;
        }

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteProvisioned) {
            result.accept(SatelliteManager.SATELLITE_ERROR_NONE);
            return;
        }

        Phone phone = SatelliteServiceUtils.getPhone();
        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        sendRequestAsync(CMD_DEPROVISION_SATELLITE_SERVICE,
                new ProvisionSatelliteServiceArgument(token, null, result, validSubId),
                phone);
    }

    /**
     * Registers for the satellite provision state changed.
     *
     * @param subId The subId of the subscription to register for provision state changed.
     * @param callback The callback to handle the satellite provision state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteError} result of the operation.
     */
    @SatelliteManager.SatelliteError public int registerForSatelliteProvisionStateChanged(int subId,
            @NonNull ISatelliteProvisionStateCallback callback) {
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            return SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
        }
        if (!satelliteSupported) {
            return SatelliteManager.SATELLITE_NOT_SUPPORTED;
        }

        mSatelliteProvisionStateChangedListeners.put(callback.asBinder(), callback);
        return SatelliteManager.SATELLITE_ERROR_NONE;
    }

    /**
     * Unregisters for the satellite provision state changed.
     * If callback was not registered before, the request will be ignored.
     *
     * @param subId The subId of the subscription to unregister for provision state changed.
     * @param callback The callback that was passed to
     * {@link #registerForSatelliteProvisionStateChanged(int, ISatelliteProvisionStateCallback)}.
     */
    public void unregisterForSatelliteProvisionStateChanged(
            int subId, @NonNull ISatelliteProvisionStateCallback callback) {
        mSatelliteProvisionStateChangedListeners.remove(callback.asBinder());
    }

    /**
     * Request to get whether the device is provisioned with a satellite provider.
     *
     * @param subId The subId of the subscription to get whether the device is provisioned for.
     * @param result The result receiver that returns whether the device is provisioned with a
     *               satellite provider if the request is successful or an error code if the
     *               request failed.
     */
    public void requestIsSatelliteProvisioned(int subId, @NonNull ResultReceiver result) {
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteSupported) {
            result.send(SatelliteManager.SATELLITE_NOT_SUPPORTED, null);
            return;
        }

        synchronized (mIsSatelliteProvisionedLock) {
            if (mIsSatelliteProvisioned != null) {
                Bundle bundle = new Bundle();
                bundle.putBoolean(SatelliteManager.KEY_SATELLITE_PROVISIONED,
                        mIsSatelliteProvisioned);
                result.send(SatelliteManager.SATELLITE_ERROR_NONE, bundle);
                return;
            }
        }

        sendRequestAsync(CMD_IS_SATELLITE_PROVISIONED, result, SatelliteServiceUtils.getPhone());
    }

    /**
     * Registers for modem state changed from satellite modem.
     *
     * @param subId The subId of the subscription to register for satellite modem state changed.
     * @param callback The callback to handle the satellite modem state changed event.
     *
     * @return The {@link SatelliteManager.SatelliteError} result of the operation.
     */
    @SatelliteManager.SatelliteError public int registerForSatelliteModemStateChanged(int subId,
            @NonNull ISatelliteStateCallback callback) {
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.registerForSatelliteModemStateChanged(callback);
        } else {
            loge("registerForSatelliteModemStateChanged: mSatelliteSessionController"
                    + " is not initialized yet");
            return SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE;
        }
        return SatelliteManager.SATELLITE_ERROR_NONE;
    }

    /**
     * Unregisters for modem state changed from satellite modem.
     * If callback was not registered before, the request will be ignored.
     *
     * @param subId The subId of the subscription to unregister for satellite modem state changed.
     * @param callback The callback that was passed to
     *                 {@link #registerForSatelliteModemStateChanged(int, ISatelliteStateCallback)}.
     */
    public void unregisterForSatelliteModemStateChanged(int subId,
            @NonNull ISatelliteStateCallback callback) {
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.unregisterForSatelliteModemStateChanged(callback);
        } else {
            loge("registerForSatelliteModemStateChanged: mSatelliteSessionController"
                    + " is not initialized yet");
        }
    }

    /**
     * Register to receive incoming datagrams over satellite.
     *
     * @param subId The subId of the subscription to register for incoming satellite datagrams.
     * @param callback The callback to handle incoming datagrams over satellite.
     *
     * @return The {@link SatelliteManager.SatelliteError} result of the operation.
     */
    @SatelliteManager.SatelliteError public int registerForSatelliteDatagram(int subId,
            @NonNull ISatelliteDatagramCallback callback) {
        return mDatagramController.registerForSatelliteDatagram(subId, callback);
    }

    /**
     * Unregister to stop receiving incoming datagrams over satellite.
     * If callback was not registered before, the request will be ignored.
     *
     * @param subId The subId of the subscription to unregister for incoming satellite datagrams.
     * @param callback The callback that was passed to
     *                 {@link #registerForSatelliteDatagram(int, ISatelliteDatagramCallback)}.
     */
    public void unregisterForSatelliteDatagram(int subId,
            @NonNull ISatelliteDatagramCallback callback) {
        mDatagramController.unregisterForSatelliteDatagram(subId, callback);
    }

    /**
     * Poll pending satellite datagrams over satellite.
     *
     * This method requests modem to check if there are any pending datagrams to be received over
     * satellite. If there are any incoming datagrams, they will be received via
     * {@link android.telephony.satellite.SatelliteDatagramCallback
     * #onSatelliteDatagramReceived(long, SatelliteDatagram, int, ILongConsumer)}
     *
     * @param subId The subId of the subscription used for receiving datagrams.
     * @param callback The callback to get {@link SatelliteManager.SatelliteError} of the request.
     */
    public void pollPendingSatelliteDatagrams(int subId, @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteProvisioned) {
            result.accept(SatelliteManager.SATELLITE_SERVICE_NOT_PROVISIONED);
            return;
        }

        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        mDatagramController.pollPendingSatelliteDatagrams(validSubId, result);
    }

    /**
     * Send datagram over satellite.
     *
     * Gateway encodes SOS message or location sharing message into a datagram and passes it as
     * input to this method. Datagram received here will be passed down to modem without any
     * encoding or encryption.
     *
     * @param subId The subId of the subscription to send satellite datagrams for.
     * @param datagramType datagram type indicating whether the datagram is of type
     *                     SOS_SMS or LOCATION_SHARING.
     * @param datagram encoded gateway datagram which is encrypted by the caller.
     *                 Datagram will be passed down to modem without any encoding or encryption.
     * @param needFullScreenPointingUI this is used to indicate pointingUI app to open in
     *                                 full screen mode.
     * @param callback The callback to get {@link SatelliteManager.SatelliteError} of the request.
     */
    public void sendSatelliteDatagram(int subId, @SatelliteManager.DatagramType int datagramType,
            SatelliteDatagram datagram, boolean needFullScreenPointingUI,
            @NonNull IIntegerConsumer callback) {
        Consumer<Integer> result = FunctionalUtils.ignoreRemoteException(callback::accept);

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        if (!satelliteProvisioned) {
            result.accept(SatelliteManager.SATELLITE_SERVICE_NOT_PROVISIONED);
            return;
        }

        /**
         * TODO for NTN-based satellites: Check if satellite is acquired.
         */
        if (mNeedsSatellitePointing) {
            mPointingAppController.startPointingUI(needFullScreenPointingUI);
        }

        final int validSubId = SatelliteServiceUtils.getValidSatelliteSubId(subId, mContext);
        mDatagramController.sendSatelliteDatagram(validSubId, datagramType, datagram,
                needFullScreenPointingUI, result);
    }

    /**
     * Request to get whether satellite communication is allowed for the current location.
     *
     * @param subId The subId of the subscription to check whether satellite communication is
     *              allowed for the current location for.
     * @param result The result receiver that returns whether satellite communication is allowed
     *               for the current location if the request is successful or an error code
     *               if the request failed.
     */
    public void requestIsSatelliteCommunicationAllowedForCurrentLocation(int subId,
            @NonNull ResultReceiver result) {
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteSupported) {
            result.send(SatelliteManager.SATELLITE_NOT_SUPPORTED, null);
            return;
        }

        sendRequestAsync(
                CMD_IS_SATELLITE_COMMUNICATION_ALLOWED, result, SatelliteServiceUtils.getPhone());
    }

    /**
     * Request to get the time after which the satellite will be visible.
     *
     * @param subId The subId to get the time after which the satellite will be visible for.
     * @param result The result receiver that returns the time after which the satellite will
     *               be visible if the request is successful or an error code if the request failed.
     */
    public void requestTimeForNextSatelliteVisibility(int subId, @NonNull ResultReceiver result) {
        Boolean satelliteSupported = isSatelliteSupportedInternal();
        if (satelliteSupported == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteSupported) {
            result.send(SatelliteManager.SATELLITE_NOT_SUPPORTED, null);
            return;
        }

        Boolean satelliteProvisioned = isSatelliteProvisioned();
        if (satelliteProvisioned == null) {
            result.send(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE, null);
            return;
        }
        if (!satelliteProvisioned) {
            result.send(SatelliteManager.SATELLITE_SERVICE_NOT_PROVISIONED, null);
            return;
        }

        Phone phone = SatelliteServiceUtils.getPhone();
        sendRequestAsync(CMD_GET_TIME_SATELLITE_NEXT_VISIBLE, result, phone);
    }

    /**
     * This API can be used by only CTS to update satellite vendor service package name.
     *
     * @param servicePackageName The package name of the satellite vendor service.
     * @return {@code true} if the satellite vendor service is set successfully,
     * {@code false} otherwise.
     */
    public boolean setSatelliteServicePackageName(@Nullable String servicePackageName) {
        boolean result = mSatelliteModemInterface.setSatelliteServicePackageName(
                servicePackageName);
        if (result && (servicePackageName == null || servicePackageName.equals("null"))) {
            /**
             * mIsSatelliteSupported is set to true when running SatelliteManagerTestOnMockService.
             * We need to set it to the actual state of the device.
             */
            synchronized (mIsSatelliteSupportedLock) {
                mIsSatelliteSupported = null;
            }
            ResultReceiver receiver = new ResultReceiver(this) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    logd("requestIsSatelliteSupported: resultCode=" + resultCode);
                }
            };
            requestIsSatelliteSupported(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, receiver);
        }
        return result;
    }

    /**
     * This function is used by {@link SatelliteModemInterface} to notify
     * {@link SatelliteController} that the satellite vendor service was just connected.
     * <p>
     * {@link SatelliteController} will send requests to satellite modem to check whether it support
     * satellite, whether it is powered on, and whether it is provisioned.
     * {@link SatelliteController} will use these cached values to serve requests from its clients.
     */
    void onSatelliteServiceConnected() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            synchronized (mIsSatelliteSupportedLock) {
                if (mIsSatelliteSupported == null) {
                    ResultReceiver receiver = new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(
                                int resultCode, Bundle resultData) {
                            logd("requestIsSatelliteSupported: resultCode="
                                    + resultCode);
                        }
                    };
                    requestIsSatelliteSupported(
                            SubscriptionManager.DEFAULT_SUBSCRIPTION_ID, receiver);
                }
            }
        } else {
            logd("onSatelliteServiceConnected: Satellite vendor service is not supported."
                    + " Ignored the event");
        }
    }

    /**
     * @return {@code true} is satellite is supported on the device, {@code  false} otherwise.
     */
    public boolean isSatelliteSupported() {
        Boolean supported = isSatelliteSupportedInternal();
        return (supported != null ? supported : false);
    }

    /**
     * If we have not successfully queried the satellite modem for its satellite service support,
     * we will retry the query one more time. Otherwise, we will return the cached result.
     */
    private Boolean isSatelliteSupportedInternal() {
        synchronized (mIsSatelliteSupportedLock) {
            if (mIsSatelliteSupported != null) {
                /* We have already successfully queried the satellite modem. */
                return mIsSatelliteSupported;
            }
        }
        /**
         * We have not successfully checked whether the modem supports satellite service.
         * Thus, we need to retry it now.
         */
        requestIsSatelliteSupported(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                new ResultReceiver(this) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        logd("requestIsSatelliteSupported: resultCode=" + resultCode);
                    }
                });
        return null;
    }

    private void handleEventProvisionSatelliteServiceDone(
            @NonNull ProvisionSatelliteServiceArgument arg,
            @SatelliteManager.SatelliteError int result) {
        logd("handleEventProvisionSatelliteServiceDone: result="
                + result + ", subId=" + arg.subId);

        Consumer<Integer> callback = mSatelliteProvisionCallbacks.remove(arg.subId);
        if (callback == null) {
            loge("handleEventProvisionSatelliteServiceDone: callback is null for subId="
                    + arg.subId);
            mProvisionMetricsStats
                    .setResultCode(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE)
                    .setIsProvisionRequest(true)
                    .reportProvisionMetrics();
            mControllerMetricsStats.reportProvisionCount(
                    SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
            return;
        }
        callback.accept(result);
    }

    private void handleEventDeprovisionSatelliteServiceDone(
            @NonNull ProvisionSatelliteServiceArgument arg,
            @SatelliteManager.SatelliteError int result) {
        if (arg == null) {
            loge("handleEventDeprovisionSatelliteServiceDone: arg is null");
            return;
        }
        logd("handleEventDeprovisionSatelliteServiceDone: result="
                + result + ", subId=" + arg.subId);

        if (arg.callback != null) {
            arg.callback.accept(result);
            mProvisionMetricsStats.setResultCode(result)
                    .setIsProvisionRequest(false)
                    .reportProvisionMetrics();
            mControllerMetricsStats.reportDeprovisionCount(result);
        }
    }

    private void handleStartSatelliteTransmissionUpdatesDone(@NonNull AsyncResult ar) {
        SatelliteControllerHandlerRequest request = (SatelliteControllerHandlerRequest) ar.userObj;
        SatelliteTransmissionUpdateArgument arg =
                (SatelliteTransmissionUpdateArgument) request.argument;
        int errorCode =  SatelliteServiceUtils.getSatelliteError(ar,
                "handleStartSatelliteTransmissionUpdatesDone");
        arg.errorCallback.accept(errorCode);

        if (errorCode != SatelliteManager.SATELLITE_ERROR_NONE) {
            mPointingAppController.setStartedSatelliteTransmissionUpdates(false);
            // We need to remove the callback from our listener list since the caller might not call
            // stopSatelliteTransmissionUpdates to unregister the callback in case of failure.
            mPointingAppController.unregisterForSatelliteTransmissionUpdates(arg.subId,
                    arg.errorCallback, arg.callback, request.phone);
        } else {
            mPointingAppController.setStartedSatelliteTransmissionUpdates(true);
        }
    }

    /**
     * Posts the specified command to be executed on the main thread and returns immediately.
     *
     * @param command command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     * @param phone phone object used to perform the operation.
     */
    private void sendRequestAsync(int command, @NonNull Object argument, @Nullable Phone phone) {
        SatelliteControllerHandlerRequest request = new SatelliteControllerHandlerRequest(
                argument, phone);
        Message msg = this.obtainMessage(command, request);
        msg.sendToTarget();
    }

    /**
     * Posts the specified command to be executed on the main thread. As this is a synchronous
     * request, it waits until the request is complete and then return the result.
     *
     * @param command command to be executed on the main thread
     * @param argument additional parameters required to perform of the operation
     * @param phone phone object used to perform the operation.
     * @return result of the operation
     */
    private @Nullable Object sendRequest(int command, @NonNull Object argument,
            @Nullable Phone phone) {
        if (Looper.myLooper() == this.getLooper()) {
            throw new RuntimeException("This method will deadlock if called from the main thread");
        }

        SatelliteControllerHandlerRequest request = new SatelliteControllerHandlerRequest(
                argument, phone);
        Message msg = this.obtainMessage(command, request);
        msg.sendToTarget();

        synchronized (request) {
            while(request.result == null) {
                try {
                    request.wait();
                } catch (InterruptedException e) {
                    // Do nothing, go back and wait until the request is complete.
                }
            }
        }
        return request.result;
    }

    /**
     * Check if satellite is provisioned for a subscription on the device.
     * @return true if satellite is provisioned on the given subscription else return false.
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    protected Boolean isSatelliteProvisioned() {
        synchronized (mIsSatelliteProvisionedLock) {
            if (mIsSatelliteProvisioned != null) {
                return mIsSatelliteProvisioned;
            }
        }

        requestIsSatelliteProvisioned(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                new ResultReceiver(this) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        logd("requestIsSatelliteProvisioned: resultCode=" + resultCode);
                    }
                });
        return null;
    }

    private void handleSatelliteEnabled(SatelliteControllerHandlerRequest request) {
        RequestSatelliteEnabledArgument argument =
                (RequestSatelliteEnabledArgument) request.argument;
        Message onCompleted = obtainMessage(EVENT_SET_SATELLITE_ENABLED_DONE, request);
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            mSatelliteModemInterface.requestSatelliteEnabled(argument.enableSatellite,
                    argument.enableDemoMode, onCompleted);
            return;
        }
        Phone phone = request.phone;
        if (phone != null) {
            phone.setSatellitePower(onCompleted, argument.enableSatellite);
        } else {
            loge("requestSatelliteEnabled: No phone object");
            argument.callback.accept(SatelliteManager.SATELLITE_INVALID_TELEPHONY_STATE);
        }
    }

    private void updateSatelliteSupportedState(boolean supported) {
        synchronized (mIsSatelliteSupportedLock) {
            mIsSatelliteSupported = supported;
        }
        mSatelliteSessionController = SatelliteSessionController.make(
                mContext, getLooper(), supported);
        if (supported) {
            registerForSatelliteProvisionStateChanged();
            registerForPendingDatagramCount();
            registerForSatelliteModemStateChanged();

            requestIsSatelliteEnabled(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                    new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            logd("requestIsSatelliteEnabled: resultCode=" + resultCode);
                        }
                    });
            requestIsSatelliteProvisioned(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                    new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            logd("requestIsSatelliteProvisioned: resultCode=" + resultCode);
                        }
                    });
            requestSatelliteCapabilities(SubscriptionManager.DEFAULT_SUBSCRIPTION_ID,
                    new ResultReceiver(this) {
                        @Override
                        protected void onReceiveResult(int resultCode, Bundle resultData) {
                            logd("requestSatelliteCapabilities: resultCode=" + resultCode);
                        }
                    });
        }
    }

    private void updateSatelliteEnabledState(boolean enabled, String caller) {
        synchronized (mIsSatelliteEnabledLock) {
            mIsSatelliteEnabled = enabled;
        }
        if (mSatelliteSessionController != null) {
            mSatelliteSessionController.onSatelliteEnabledStateChanged(enabled);
        } else {
            loge(caller + ": mSatelliteSessionController is not initialized yet");
        }
    }

    private void disableBluetoothWifiState() {
        if (mBluetoothAdapter == null) {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }
        if (mWifiManager == null) {
            mWifiManager = mContext.getSystemService(WifiManager.class);
        }
        if (mBluetoothAdapter.isEnabled()) {
            if (DBG) logd("disabling Bluetooth");
            //Set the Flag to  indicate that Bluetooth is disabled by Satellite Controller
            mSharedPreferences.edit().putBoolean(KEY_BLUETOOTH_DISABLED_BY_SCO, true)
                        .apply();
            mBluetoothAdapter.disable();
        }
        if (mWifiManager.isWifiEnabled()) {
            if (DBG) logd("disabling Wifi");
            //Set the Flag to  indicate that Wifi is disabled by Satellite Controller
            mSharedPreferences.edit().putBoolean(KEY_WIFI_DISABLED_BY_SCO, true)
                        .apply();
            mWifiManager.setWifiEnabled(false);
        }
    }

    private void checkAndEnableBluetoothWifiState() {
        if (mBluetoothAdapter == null) {
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        }
        if (mWifiManager == null) {
            mWifiManager = mContext.getSystemService(WifiManager.class);
        }
        if (!mBluetoothAdapter.isEnabled() && mDisabledBTFlag) {
            if (DBG) logd("Enabling Bluetooth");
            mBluetoothAdapter.enable();
            mSharedPreferences.edit().putBoolean(KEY_BLUETOOTH_DISABLED_BY_SCO, false)
                    .apply();
        }
        if (!mWifiManager.isWifiEnabled() && mDisabledWifiFlag) {
            if (DBG) logd("Enabling Wifi");
            mWifiManager.setWifiEnabled(true);
            mSharedPreferences.edit().putBoolean(KEY_WIFI_DISABLED_BY_SCO, false)
                    .apply();
        }
    }

    private void registerForSatelliteProvisionStateChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForProvisionStateChangedWithSatelliteService.get()) {
                mSatelliteModemInterface.registerForSatelliteProvisionStateChanged(
                        this, EVENT_SATELLITE_PROVISION_STATE_CHANGED, null);
                mRegisteredForProvisionStateChangedWithSatelliteService.set(true);
            }
        } else {
            Phone phone = SatelliteServiceUtils.getPhone();
            if (phone == null) {
                loge("registerForSatelliteProvisionStateChanged: phone is null");
            } else if (!mRegisteredForProvisionStateChangedWithPhone.get()) {
                phone.registerForSatelliteProvisionStateChanged(
                        this, EVENT_SATELLITE_PROVISION_STATE_CHANGED, null);
                mRegisteredForProvisionStateChangedWithPhone.set(true);
            }
        }
    }

    private void registerForPendingDatagramCount() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForPendingDatagramCountWithSatelliteService.get()) {
                mSatelliteModemInterface.registerForPendingDatagrams(
                        this, EVENT_PENDING_DATAGRAMS, null);
                mRegisteredForPendingDatagramCountWithSatelliteService.set(true);
            }
        } else {
            Phone phone = SatelliteServiceUtils.getPhone();
            if (phone == null) {
                loge("registerForPendingDatagramCount: satellite phone is "
                        + "not initialized yet");
            } else if (!mRegisteredForPendingDatagramCountWithPhone.get()) {
                phone.registerForPendingDatagramCount(this, EVENT_PENDING_DATAGRAMS, null);
                mRegisteredForPendingDatagramCountWithPhone.set(true);
            }
        }
    }

    private void registerForSatelliteModemStateChanged() {
        if (mSatelliteModemInterface.isSatelliteServiceSupported()) {
            if (!mRegisteredForSatelliteModemStateChangedWithSatelliteService.get()) {
                mSatelliteModemInterface.registerForSatelliteModemStateChanged(
                        this, EVENT_SATELLITE_MODEM_STATE_CHANGED, null);
                mRegisteredForSatelliteModemStateChangedWithSatelliteService.set(true);
            }
        } else {
            Phone phone = SatelliteServiceUtils.getPhone();
            if (phone == null) {
                loge("registerForSatelliteModemStateChanged: satellite phone is "
                        + "not initialized yet");
            } else if (!mRegisteredForSatelliteModemStateChangedWithPhone.get()) {
                phone.registerForSatelliteModemStateChanged(
                        this, EVENT_SATELLITE_MODEM_STATE_CHANGED, null);
                mRegisteredForSatelliteModemStateChangedWithPhone.set(true);
            }
        }
    }

    private void handleEventSatelliteProvisionStateChanged(boolean provisioned) {
        logd("handleSatelliteProvisionStateChangedEvent: provisioned=" + provisioned);

        synchronized (mIsSatelliteProvisionedLock) {
            mIsSatelliteProvisioned = provisioned;
        }

        List<ISatelliteProvisionStateCallback> toBeRemoved = new ArrayList<>();
        mSatelliteProvisionStateChangedListeners.values().forEach(listener -> {
            try {
                listener.onSatelliteProvisionStateChanged(provisioned);
            } catch (RemoteException e) {
                logd("handleSatelliteProvisionStateChangedEvent RemoteException: " + e);
                toBeRemoved.add(listener);
            }
        });
        toBeRemoved.forEach(listener -> {
            mSatelliteProvisionStateChangedListeners.remove(listener.asBinder());
        });
    }

    private void handleEventSatelliteModemStateChanged(
            @SatelliteManager.SatelliteModemState int state) {
        logd("handleEventSatelliteModemStateChanged: state=" + state);
        if (state == SatelliteManager.SATELLITE_MODEM_STATE_OFF
                || state == SatelliteManager.SATELLITE_MODEM_STATE_UNAVAILABLE) {
            updateSatelliteEnabledState(
                    false, "handleEventSatelliteModemStateChanged");
        }
    }

    private static void logd(@NonNull String log) {
        Rlog.d(TAG, log);
    }

    private static void loge(@NonNull String log) {
        Rlog.e(TAG, log);
    }
}
