/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telephony;

import static android.net.NetworkPolicyManager.OVERRIDE_CONGESTED;
import static android.net.NetworkPolicyManager.OVERRIDE_UNMETERED;

import android.Manifest;
import android.annotation.CallbackExecutor;
import android.annotation.DurationMillisLong;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressAutoDoc;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.UnsupportedAppUsage;
import android.app.BroadcastOptions;
import android.app.PendingIntent;
import android.app.job.JobService;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.net.INetworkPolicyManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.euicc.EuiccManager;
import android.telephony.ims.ImsMmTelManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;

import com.android.internal.telephony.IOnSubscriptionsChangedListener;
import com.android.internal.telephony.ISub;
import com.android.internal.telephony.ITelephonyRegistry;
import com.android.internal.telephony.PhoneConstants;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * SubscriptionManager is the application interface to SubscriptionController
 * and provides information about the current Telephony Subscriptions.
 */
@SystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
public class SubscriptionManager {
    private static final String LOG_TAG = "SubscriptionManager";
    private static final boolean DBG = false;
    private static final boolean VDBG = false;

    /** An invalid subscription identifier */
    public static final int INVALID_SUBSCRIPTION_ID = -1;

    /** Base value for Dummy SUBSCRIPTION_ID's. */
    /** FIXME: Remove DummySubId's, but for now have them map just below INVALID_SUBSCRIPTION_ID
     /** @hide */
    public static final int DUMMY_SUBSCRIPTION_ID_BASE = INVALID_SUBSCRIPTION_ID - 1;

    /** An invalid phone identifier */
    /** @hide */
    public static final int INVALID_PHONE_INDEX = -1;

    /** Indicates invalid sim slot. This can be returned by {@link #getSlotIndex(int)}. */
    public static final int INVALID_SIM_SLOT_INDEX = -1;

    /** Indicates the default subscription ID in Telephony. */
    public static final int DEFAULT_SUBSCRIPTION_ID = Integer.MAX_VALUE;

    /**
     * Indicates the caller wants the default phone id.
     * Used in SubscriptionController and Phone but do we really need it???
     * @hide
     */
    public static final int DEFAULT_PHONE_INDEX = Integer.MAX_VALUE;

    /** Indicates the caller wants the default slot id. NOT used remove? */
    /** @hide */
    public static final int DEFAULT_SIM_SLOT_INDEX = Integer.MAX_VALUE;

    /** Minimum possible subid that represents a subscription */
    /** @hide */
    public static final int MIN_SUBSCRIPTION_ID_VALUE = 0;

    /** Maximum possible subid that represents a subscription */
    /** @hide */
    public static final int MAX_SUBSCRIPTION_ID_VALUE = DEFAULT_SUBSCRIPTION_ID - 1;

    /** @hide */
    @UnsupportedAppUsage
    public static final Uri CONTENT_URI = Uri.parse("content://telephony/siminfo");

    /**
     * Generates a content {@link Uri} used to receive updates on simInfo change
     * on the given subscriptionId
     * @param subscriptionId the subscriptionId to receive updates on
     * @return the Uri used to observe carrier identity changes
     * @hide
     */
    public static Uri getUriForSubscriptionId(int subscriptionId) {
        return Uri.withAppendedPath(CONTENT_URI, String.valueOf(subscriptionId));
    }

    /**
     * A content {@link Uri} used to receive updates on wfc enabled user setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc enabled {@link ImsMmTelManager#isVoWiFiSettingEnabled()}
     * while your app is running. You can also use a {@link JobService} to ensure your app
     * is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link JobService} does not guarantee timely delivery of
     * updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @SystemApi
    public static final Uri WFC_ENABLED_CONTENT_URI = Uri.withAppendedPath(CONTENT_URI, "wfc");

    /**
     * A content {@link Uri} used to receive updates on advanced calling user setting
     * @see ImsMmTelManager#isAdvancedCallingSettingEnabled().
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription advanced calling enabled
     * {@link ImsMmTelManager#isAdvancedCallingSettingEnabled()} while your app is running.
     * You can also use a {@link JobService} to ensure your app is notified of changes to the
     * {@link Uri} even when it is not running.
     * Note, however, that using a {@link JobService} does not guarantee timely delivery of
     * updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @SystemApi
    public static final Uri ADVANCED_CALLING_ENABLED_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "advanced_calling");

    /**
     * A content {@link Uri} used to receive updates on wfc mode setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc mode {@link ImsMmTelManager#getVoWiFiModeSetting()}
     * while your app is running. You can also use a {@link JobService} to ensure your app
     * is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link JobService} does not guarantee timely delivery of
     * updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @SystemApi
    public static final Uri WFC_MODE_CONTENT_URI = Uri.withAppendedPath(CONTENT_URI, "wfc_mode");

    /**
     * A content {@link Uri} used to receive updates on wfc roaming mode setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc roaming mode {@link ImsMmTelManager#getVoWiFiRoamingModeSetting()}
     * while your app is running. You can also use a {@link JobService} to ensure your app
     * is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link JobService} does not guarantee timely delivery of
     * updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @SystemApi
    public static final Uri WFC_ROAMING_MODE_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "wfc_roaming_mode");

    /**
     * A content {@link Uri} used to receive updates on vt(video telephony over IMS) enabled
     * setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription vt enabled {@link ImsMmTelManager#isVtSettingEnabled()}
     * while your app is running. You can also use a {@link JobService} to ensure your app
     * is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link JobService} does not guarantee timely delivery of
     * updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @SystemApi
    public static final Uri VT_ENABLED_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "vt_enabled");

    /**
     * A content {@link Uri} used to receive updates on wfc roaming enabled setting.
     * <p>
     * Use this {@link Uri} with a {@link ContentObserver} to be notified of changes to the
     * subscription wfc roaming enabled {@link ImsMmTelManager#isVoWiFiRoamingSettingEnabled()}
     * while your app is running. You can also use a {@link JobService} to ensure your app
     * is notified of changes to the {@link Uri} even when it is not running.
     * Note, however, that using a {@link JobService} does not guarantee timely delivery of
     * updates to the {@link Uri}.
     * To be notified of changes to a specific subId, append subId to the URI
     * {@link Uri#withAppendedPath(Uri, String)}.
     * @hide
     */
    @SystemApi
    public static final Uri WFC_ROAMING_ENABLED_CONTENT_URI = Uri.withAppendedPath(
            CONTENT_URI, "wfc_roaming_enabled");

    /**
     * TelephonyProvider unique key column name is the subscription id.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String UNIQUE_KEY_SUBSCRIPTION_ID = "_id";

    /**
     * TelephonyProvider column name for a unique identifier for the subscription within the
     * specific subscription type. For example, it contains SIM ICC Identifier subscriptions
     * on Local SIMs. and Mac-address for Remote-SIM Subscriptions for Bluetooth devices.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String ICC_ID = "icc_id";

    /**
     * TelephonyProvider column name for user SIM_SlOT_INDEX
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String SIM_SLOT_INDEX = "sim_id";

    /** SIM is not inserted */
    /** @hide */
    public static final int SIM_NOT_INSERTED = -1;

    /**
     * The slot-index for Bluetooth Remote-SIM subscriptions
     * @hide
     */
    public static final int SLOT_INDEX_FOR_REMOTE_SIM_SUB = INVALID_SIM_SLOT_INDEX;

    /**
     * TelephonyProvider column name Subscription-type.
     * <P>Type: INTEGER (int)</P> {@link #SUBSCRIPTION_TYPE_LOCAL_SIM} for Local-SIM Subscriptions,
     * {@link #SUBSCRIPTION_TYPE_REMOTE_SIM} for Remote-SIM Subscriptions.
     * Default value is 0.
     */
    /** @hide */
    public static final String SUBSCRIPTION_TYPE = "subscription_type";

    /**
     * This constant is to designate a subscription as a Local-SIM Subscription.
     * <p> A Local-SIM can be a physical SIM inserted into a sim-slot in the device, or eSIM on the
     * device.
     * </p>
     */
    public static final int SUBSCRIPTION_TYPE_LOCAL_SIM = 0;

    /**
     * This constant is to designate a subscription as a Remote-SIM Subscription.
     * <p>
     * A Remote-SIM subscription is for a SIM on a phone connected to this device via some
     * connectivity mechanism, for example bluetooth. Similar to Local SIM, this subscription can
     * be used for SMS, Voice and data by proxying data through the connected device.
     * Certain data of the SIM, such as IMEI, are not accessible for Remote SIMs.
     * </p>
     *
     * <p>
     * A Remote-SIM is available only as long the phone stays connected to this device.
     * When the phone disconnects, Remote-SIM subscription is removed from this device and is
     * no longer known. All data associated with the subscription, such as stored SMS, call logs,
     * contacts etc, are removed from this device.
     * </p>
     *
     * <p>
     * If the phone re-connects to this device, a new Remote-SIM subscription is created for
     * the phone. The Subscription Id associated with the new subscription is different from
     * the Subscription Id of the previous Remote-SIM subscription created (and removed) for the
     * phone; i.e., new Remote-SIM subscription treats the reconnected phone as a Remote-SIM that
     * was never seen before.
     * </p>
     */
    public static final int SUBSCRIPTION_TYPE_REMOTE_SIM = 1;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"SUBSCRIPTION_TYPE_"},
        value = {
            SUBSCRIPTION_TYPE_LOCAL_SIM,
            SUBSCRIPTION_TYPE_REMOTE_SIM})
    public @interface SubscriptionType {}

    /**
     * TelephonyProvider column name for user displayed name.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String DISPLAY_NAME = "display_name";

    /**
     * TelephonyProvider column name for the service provider name for the SIM.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String CARRIER_NAME = "carrier_name";

    /**
     * Default name resource
     * @hide
     */
    public static final int DEFAULT_NAME_RES = com.android.internal.R.string.unknownName;

    /**
     * TelephonyProvider column name for source of the user displayed name.
     * <P>Type: INT (int)</P> with one of the NAME_SOURCE_XXXX values below
     *
     * @hide
     */
    public static final String NAME_SOURCE = "name_source";

    /**
     * The name_source is undefined
     * @hide
     */
    public static final int NAME_SOURCE_UNDEFINDED = -1;

    /**
     * The name_source is the default
     * @hide
     */
    public static final int NAME_SOURCE_DEFAULT_SOURCE = 0;

    /**
     * The name_source is from the SIM
     * @hide
     */
    public static final int NAME_SOURCE_SIM_SOURCE = 1;

    /**
     * The name_source is from the user
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static final int NAME_SOURCE_USER_INPUT = 2;

    /**
     * TelephonyProvider column name for the color of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String COLOR = "color";

    /** @hide */
    public static final int COLOR_1 = 0;

    /** @hide */
    public static final int COLOR_2 = 1;

    /** @hide */
    public static final int COLOR_3 = 2;

    /** @hide */
    public static final int COLOR_4 = 3;

    /** @hide */
    public static final int COLOR_DEFAULT = COLOR_1;

    /**
     * TelephonyProvider column name for the phone number of a SIM.
     * <P>Type: TEXT (String)</P>
     */
    /** @hide */
    public static final String NUMBER = "number";

    /**
     * TelephonyProvider column name for the number display format of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String DISPLAY_NUMBER_FORMAT = "display_number_format";

    /** @hide */
    public static final int DISPLAY_NUMBER_NONE = 0;

    /** @hide */
    public static final int DISPLAY_NUMBER_FIRST = 1;

    /** @hide */
    public static final int DISPLAY_NUMBER_LAST = 2;

    /** @hide */
    public static final int DISPLAY_NUMBER_DEFAULT = DISPLAY_NUMBER_FIRST;

    /**
     * TelephonyProvider column name for permission for data roaming of a SIM.
     * <P>Type: INTEGER (int)</P>
     */
    /** @hide */
    public static final String DATA_ROAMING = "data_roaming";

    /** Indicates that data roaming is enabled for a subscription */
    public static final int DATA_ROAMING_ENABLE = 1;

    /** Indicates that data roaming is disabled for a subscription */
    public static final int DATA_ROAMING_DISABLE = 0;

    /** @hide */
    public static final int DATA_ROAMING_DEFAULT = DATA_ROAMING_DISABLE;

    /** @hide */
    public static final int SIM_PROVISIONED = 0;

    /**
     * TelephonyProvider column name for subscription carrier id.
     * @see TelephonyManager#getSimCarrierId()
     * <p>Type: INTEGER (int) </p>
     * @hide
     */
    public static final String CARRIER_ID = "carrier_id";

    /**
     * TelephonyProvider column name for the MCC associated with a SIM, stored as a string.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String MCC_STRING = "mcc_string";

    /**
     * TelephonyProvider column name for the MNC associated with a SIM, stored as a string.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String MNC_STRING = "mnc_string";

    /**
     * TelephonyProvider column name for the MCC associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String MCC = "mcc";

    /**
     * TelephonyProvider column name for the MNC associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String MNC = "mnc";

    /**
     * TelephonyProvider column name for the iso country code associated with a SIM.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String ISO_COUNTRY_CODE = "iso_country_code";

    /**
     * TelephonyProvider column name for the sim provisioning status associated with a SIM.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String SIM_PROVISIONING_STATUS = "sim_provisioning_status";

    /**
     * TelephonyProvider column name for whether a subscription is embedded (that is, present on an
     * eSIM).
     * <p>Type: INTEGER (int), 1 for embedded or 0 for non-embedded.
     * @hide
     */
    public static final String IS_EMBEDDED = "is_embedded";

    /**
     * TelephonyProvider column name for SIM card identifier. For UICC card it is the ICCID of the
     * current enabled profile on the card, while for eUICC card it is the EID of the card.
     * <P>Type: TEXT (String)</P>
     * @hide
     */
    public static final String CARD_ID = "card_id";

    /**
     * TelephonyProvider column name for the encoded {@link UiccAccessRule}s from
     * {@link UiccAccessRule#encodeRules}. Only present if {@link #IS_EMBEDDED} is 1.
     * <p>TYPE: BLOB
     * @hide
     */
    public static final String ACCESS_RULES = "access_rules";

    /**
     * TelephonyProvider column name identifying whether an embedded subscription is on a removable
     * card. Such subscriptions are marked inaccessible as soon as the current card is removed.
     * Otherwise, they will remain accessible unless explicitly deleted. Only present if
     * {@link #IS_EMBEDDED} is 1.
     * <p>TYPE: INTEGER (int), 1 for removable or 0 for non-removable.
     * @hide
     */
    public static final String IS_REMOVABLE = "is_removable";

    /**
     *  TelephonyProvider column name for extreme threat in CB settings
     * @hide
     */
    public static final String CB_EXTREME_THREAT_ALERT = "enable_cmas_extreme_threat_alerts";

    /**
     * TelephonyProvider column name for severe threat in CB settings
     *@hide
     */
    public static final String CB_SEVERE_THREAT_ALERT = "enable_cmas_severe_threat_alerts";

    /**
     * TelephonyProvider column name for amber alert in CB settings
     *@hide
     */
    public static final String CB_AMBER_ALERT = "enable_cmas_amber_alerts";

    /**
     * TelephonyProvider column name for emergency alert in CB settings
     *@hide
     */
    public static final String CB_EMERGENCY_ALERT = "enable_emergency_alerts";

    /**
     * TelephonyProvider column name for alert sound duration in CB settings
     *@hide
     */
    public static final String CB_ALERT_SOUND_DURATION = "alert_sound_duration";

    /**
     * TelephonyProvider column name for alert reminder interval in CB settings
     *@hide
     */
    public static final String CB_ALERT_REMINDER_INTERVAL = "alert_reminder_interval";

    /**
     * TelephonyProvider column name for enabling vibrate in CB settings
     *@hide
     */
    public static final String CB_ALERT_VIBRATE = "enable_alert_vibrate";

    /**
     * TelephonyProvider column name for enabling alert speech in CB settings
     *@hide
     */
    public static final String CB_ALERT_SPEECH = "enable_alert_speech";

    /**
     * TelephonyProvider column name for ETWS test alert in CB settings
     *@hide
     */
    public static final String CB_ETWS_TEST_ALERT = "enable_etws_test_alerts";

    /**
     * TelephonyProvider column name for enable channel50 alert in CB settings
     *@hide
     */
    public static final String CB_CHANNEL_50_ALERT = "enable_channel_50_alerts";

    /**
     * TelephonyProvider column name for CMAS test alert in CB settings
     *@hide
     */
    public static final String CB_CMAS_TEST_ALERT= "enable_cmas_test_alerts";

    /**
     * TelephonyProvider column name for Opt out dialog in CB settings
     *@hide
     */
    public static final String CB_OPT_OUT_DIALOG = "show_cmas_opt_out_dialog";

    /**
     * TelephonyProvider column name for enable Volte.
     *
     * If this setting is not initialized (set to -1)  then we use the Carrier Config value
     * {@link CarrierConfigManager#KEY_ENHANCED_4G_LTE_ON_BY_DEFAULT_BOOL}.
     *@hide
     */
    public static final String ENHANCED_4G_MODE_ENABLED = "volte_vt_enabled";

    /**
     * TelephonyProvider column name for enable VT (Video Telephony over IMS)
     *@hide
     */
    public static final String VT_IMS_ENABLED = "vt_ims_enabled";

    /**
     * TelephonyProvider column name for enable Wifi calling
     *@hide
     */
    public static final String WFC_IMS_ENABLED = "wfc_ims_enabled";

    /**
     * TelephonyProvider column name for Wifi calling mode
     *@hide
     */
    public static final String WFC_IMS_MODE = "wfc_ims_mode";

    /**
     * TelephonyProvider column name for Wifi calling mode in roaming
     *@hide
     */
    public static final String WFC_IMS_ROAMING_MODE = "wfc_ims_roaming_mode";

    /**
     * TelephonyProvider column name for enable Wifi calling in roaming
     *@hide
     */
    public static final String WFC_IMS_ROAMING_ENABLED = "wfc_ims_roaming_enabled";

    /**
     * TelephonyProvider column name for whether a subscription is opportunistic, that is,
     * whether the network it connects to is limited in functionality or coverage.
     * For example, CBRS.
     * <p>Type: INTEGER (int), 1 for opportunistic or 0 for non-opportunistic.
     * @hide
     */
    public static final String IS_OPPORTUNISTIC = "is_opportunistic";

    /**
     * TelephonyProvider column name for group ID. Subscriptions with same group ID
     * are considered bundled together, and should behave as a single subscription at
     * certain scenarios.
     *
     * @hide
     */
    public static final String GROUP_UUID = "group_uuid";

    /**
     * TelephonyProvider column name for whether a subscription is metered or not, that is, whether
     * the network it connects to charges for subscription or not. For example, paid CBRS or unpaid.
     * @hide
     */
    public static final String IS_METERED = "is_metered";

    /**
     * TelephonyProvider column name for the profile class of a subscription
     * Only present if {@link #IS_EMBEDDED} is 1.
     * <P>Type: INTEGER (int)</P>
     * @hide
     */
    public static final String PROFILE_CLASS = "profile_class";

    /**
     * Profile class of the subscription
     * @hide
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = { "PROFILE_CLASS_" }, value = {
            PROFILE_CLASS_TESTING,
            PROFILE_CLASS_PROVISIONING,
            PROFILE_CLASS_OPERATIONAL,
            PROFILE_CLASS_UNSET,
            PROFILE_CLASS_DEFAULT
    })
    public @interface ProfileClass {}

    /**
     * A testing profile can be pre-loaded or downloaded onto
     * the eUICC and provides connectivity to test equipment
     * for the purpose of testing the device and the eUICC. It
     * is not intended to store any operator credentials.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_TESTING = 0;

    /**
     * A provisioning profile is pre-loaded onto the eUICC and
     * provides connectivity to a mobile network solely for the
     * purpose of provisioning profiles.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_PROVISIONING = 1;

    /**
     * An operational profile can be pre-loaded or downloaded
     * onto the eUICC and provides services provided by the
     * operator.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_OPERATIONAL = 2;

    /**
     * The profile class is unset. This occurs when profile class
     * info is not available. The subscription either has no profile
     * metadata or the profile metadata did not encode profile class.
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_UNSET = -1;

    /**
     * Default profile class
     * @hide
     */
    @SystemApi
    public static final int PROFILE_CLASS_DEFAULT = PROFILE_CLASS_UNSET;

    /**
     * Broadcast Action: The user has changed one of the default subs related to
     * data, phone calls, or sms</p>
     *
     * TODO: Change to a listener
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String SUB_DEFAULT_CHANGED_ACTION =
            "android.intent.action.SUB_DEFAULT_CHANGED";

    /**
     * Broadcast Action: The default subscription has changed.  This has the following
     * extra values:</p>
     * The {@link #EXTRA_SUBSCRIPTION_INDEX} extra indicates the current default subscription index
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEFAULT_SUBSCRIPTION_CHANGED
            = "android.telephony.action.DEFAULT_SUBSCRIPTION_CHANGED";

    /**
     * Broadcast Action: The default sms subscription has changed.  This has the following
     * extra values:</p>
     * {@link #EXTRA_SUBSCRIPTION_INDEX} extra indicates the current default sms
     * subscription index
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED
            = "android.telephony.action.DEFAULT_SMS_SUBSCRIPTION_CHANGED";

    /**
     * Activity Action: Display UI for managing the billing relationship plans
     * between a carrier and a specific subscriber.
     * <p>
     * Carrier apps are encouraged to implement this activity, and the OS will
     * provide an affordance to quickly enter this activity, typically via
     * Settings. This affordance will only be shown when the carrier app is
     * actively providing subscription plan information via
     * {@link #setSubscriptionPlans(int, List)}.
     * <p>
     * Contains {@link #EXTRA_SUBSCRIPTION_INDEX} to indicate which subscription
     * the user is interested in.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_MANAGE_SUBSCRIPTION_PLANS
            = "android.telephony.action.MANAGE_SUBSCRIPTION_PLANS";

    /**
     * Broadcast Action: Request a refresh of the billing relationship plans
     * between a carrier and a specific subscriber.
     * <p>
     * Carrier apps are encouraged to implement this receiver, and the OS will
     * provide an affordance to request a refresh. This affordance will only be
     * shown when the carrier app is actively providing subscription plan
     * information via {@link #setSubscriptionPlans(int, List)}.
     * <p>
     * Contains {@link #EXTRA_SUBSCRIPTION_INDEX} to indicate which subscription
     * the user is interested in.
     * <p>
     * Receivers should protect themselves by checking that the sender holds the
     * {@code android.permission.MANAGE_SUBSCRIPTION_PLANS} permission.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_REFRESH_SUBSCRIPTION_PLANS
            = "android.telephony.action.REFRESH_SUBSCRIPTION_PLANS";

    /**
     * Broadcast Action: The billing relationship plans between a carrier and a
     * specific subscriber has changed.
     * <p>
     * Contains {@link #EXTRA_SUBSCRIPTION_INDEX} to indicate which subscription
     * changed.
     *
     * @hide
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    @RequiresPermission(android.Manifest.permission.MANAGE_SUBSCRIPTION_PLANS)
    public static final String ACTION_SUBSCRIPTION_PLANS_CHANGED
            = "android.telephony.action.SUBSCRIPTION_PLANS_CHANGED";

    /**
     * Integer extra used with {@link #ACTION_DEFAULT_SUBSCRIPTION_CHANGED} and
     * {@link #ACTION_DEFAULT_SMS_SUBSCRIPTION_CHANGED} to indicate the subscription
     * which has changed.
     */
    public static final String EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX";

    private final Context mContext;
    private volatile INetworkPolicyManager mNetworkPolicy;

    /**
     * A listener class for monitoring changes to {@link SubscriptionInfo} records.
     * <p>
     * Override the onSubscriptionsChanged method in the object that extends this
     * class and pass it to {@link #addOnSubscriptionsChangedListener(OnSubscriptionsChangedListener)}
     * to register your listener and to unregister invoke
     * {@link #removeOnSubscriptionsChangedListener(OnSubscriptionsChangedListener)}
     * <p>
     * Permissions android.Manifest.permission.READ_PHONE_STATE is required
     * for #onSubscriptionsChanged to be invoked.
     */
    public static class OnSubscriptionsChangedListener {
        private class OnSubscriptionsChangedListenerHandler extends Handler {
            OnSubscriptionsChangedListenerHandler() {
                super();
            }

            OnSubscriptionsChangedListenerHandler(Looper looper) {
                super(looper);
            }

            @Override
            public void handleMessage(Message msg) {
                if (DBG) {
                    log("handleMessage: invoke the overriden onSubscriptionsChanged()");
                }
                OnSubscriptionsChangedListener.this.onSubscriptionsChanged();
            }
        }

        private final Handler mHandler;

        public OnSubscriptionsChangedListener() {
            mHandler = new OnSubscriptionsChangedListenerHandler();
        }

        /**
         * Allow a listener to be created with a custom looper
         * @param looper the looper that the underlining handler should run on
         * @hide
         */
        public OnSubscriptionsChangedListener(Looper looper) {
            mHandler = new OnSubscriptionsChangedListenerHandler(looper);
        }

        /**
         * Callback invoked when there is any change to any SubscriptionInfo, as well as once on
         * registering for changes with {@link #addOnSubscriptionsChangedListener}. Typically
         * this method would invoke {@link #getActiveSubscriptionInfoList}
         */
        public void onSubscriptionsChanged() {
            if (DBG) log("onSubscriptionsChanged: NOT OVERRIDDEN");
        }

        /**
         * The callback methods need to be called on the handler thread where
         * this object was created.  If the binder did that for us it'd be nice.
         */
        IOnSubscriptionsChangedListener callback = new IOnSubscriptionsChangedListener.Stub() {
            @Override
            public void onSubscriptionsChanged() {
                if (DBG) log("callback: received, sendEmptyMessage(0) to handler");
                mHandler.sendEmptyMessage(0);
            }
        };

        private void log(String s) {
            Rlog.d(LOG_TAG, s);
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public SubscriptionManager(Context context) {
        if (DBG) logd("SubscriptionManager created");
        mContext = context;
    }

    /**
     * @deprecated developers should always obtain references directly from
     *             {@link Context#getSystemService(Class)}.
     */
    @Deprecated
    public static SubscriptionManager from(Context context) {
        return (SubscriptionManager) context
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
    }

    private final INetworkPolicyManager getNetworkPolicy() {
        if (mNetworkPolicy == null) {
            mNetworkPolicy = INetworkPolicyManager.Stub
                    .asInterface(ServiceManager.getService(Context.NETWORK_POLICY_SERVICE));
        }
        return mNetworkPolicy;
    }

    /**
     * Register for changes to the list of active {@link SubscriptionInfo} records or to the
     * individual records themselves. When a change occurs the onSubscriptionsChanged method of
     * the listener will be invoked immediately if there has been a notification. The
     * onSubscriptionChanged method will also be triggered once initially when calling this
     * function.
     *
     * @param listener an instance of {@link OnSubscriptionsChangedListener} with
     *                 onSubscriptionsChanged overridden.
     */
    public void addOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        String pkgName = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("register OnSubscriptionsChangedListener pkgName=" + pkgName
                    + " listener=" + listener);
        }
        try {
            // We use the TelephonyRegistry as it runs in the system and thus is always
            // available. Where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.addOnSubscriptionsChangedListener(pkgName, listener.callback);
            }
        } catch (RemoteException ex) {
            Log.e(LOG_TAG, "Remote exception ITelephonyRegistry " + ex);
        }
    }

    /**
     * Unregister the {@link OnSubscriptionsChangedListener}. This is not strictly necessary
     * as the listener will automatically be unregistered if an attempt to invoke the listener
     * fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnSubscriptionsChangedListener(OnSubscriptionsChangedListener listener) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("unregister OnSubscriptionsChangedListener pkgForDebug=" + pkgForDebug
                    + " listener=" + listener);
        }
        try {
            // We use the TelephonyRegistry as it runs in the system and thus is always
            // available where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.removeOnSubscriptionsChangedListener(pkgForDebug, listener.callback);
            }
        } catch (RemoteException ex) {
            Log.e(LOG_TAG, "Remote exception ITelephonyRegistry " + ex);
        }
    }

    /**
     * A listener class for monitoring changes to {@link SubscriptionInfo} records of opportunistic
     * subscriptions.
     * <p>
     * Override the onOpportunisticSubscriptionsChanged method in the object that extends this
     * or {@link #addOnOpportunisticSubscriptionsChangedListener(
     * Executor, OnOpportunisticSubscriptionsChangedListener)}
     * to register your listener and to unregister invoke
     * {@link #removeOnOpportunisticSubscriptionsChangedListener(
     * OnOpportunisticSubscriptionsChangedListener)}
     * <p>
     * Permissions android.Manifest.permission.READ_PHONE_STATE is required
     * for #onOpportunisticSubscriptionsChanged to be invoked.
     */
    public static class OnOpportunisticSubscriptionsChangedListener {
        private Executor mExecutor;
        /**
         * Callback invoked when there is any change to any SubscriptionInfo. Typically
         * this method would invoke {@link #getActiveSubscriptionInfoList}
         */
        public void onOpportunisticSubscriptionsChanged() {
            if (DBG) log("onOpportunisticSubscriptionsChanged: NOT OVERRIDDEN");
        }

        private void setExecutor(Executor executor) {
            mExecutor = executor;
        }

        /**
         * The callback methods need to be called on the handler thread where
         * this object was created.  If the binder did that for us it'd be nice.
         */
        IOnSubscriptionsChangedListener callback = new IOnSubscriptionsChangedListener.Stub() {
            @Override
            public void onSubscriptionsChanged() {
                final long identity = Binder.clearCallingIdentity();
                try {
                    if (DBG) log("onOpportunisticSubscriptionsChanged callback received.");
                    mExecutor.execute(() -> onOpportunisticSubscriptionsChanged());
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };

        private void log(String s) {
            Rlog.d(LOG_TAG, s);
        }
    }

    /**
     * Register for changes to the list of opportunistic subscription records or to the
     * individual records themselves. When a change occurs the onOpportunisticSubscriptionsChanged
     * method of the listener will be invoked immediately if there has been a notification.
     *
     * @param listener an instance of {@link OnOpportunisticSubscriptionsChangedListener} with
     *                 onOpportunisticSubscriptionsChanged overridden.
     */
    public void addOnOpportunisticSubscriptionsChangedListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnOpportunisticSubscriptionsChangedListener listener) {
        if (executor == null || listener == null) {
            return;
        }

        String pkgName = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("register addOnOpportunisticSubscriptionsChangedListener pkgName=" + pkgName
                    + " listener=" + listener);
        }

        listener.setExecutor(executor);

        try {
            // We use the TelephonyRegistry as it runs in the system and thus is always
            // available. Where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.addOnOpportunisticSubscriptionsChangedListener(pkgName, listener.callback);
            }
        } catch (RemoteException ex) {
            Log.e(LOG_TAG, "Remote exception ITelephonyRegistry " + ex);
        }
    }

    /**
     * Unregister the {@link OnOpportunisticSubscriptionsChangedListener} that is currently
     * listening opportunistic subscriptions change. This is not strictly necessary
     * as the listener will automatically be unregistered if an attempt to invoke the listener
     * fails.
     *
     * @param listener that is to be unregistered.
     */
    public void removeOnOpportunisticSubscriptionsChangedListener(
            OnOpportunisticSubscriptionsChangedListener listener) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (DBG) {
            logd("unregister OnOpportunisticSubscriptionsChangedListener pkgForDebug="
                    + pkgForDebug + " listener=" + listener);
        }
        try {
            // We use the TelephonyRegistry as it runs in the system and thus is always
            // available where as SubscriptionController could crash and not be available
            ITelephonyRegistry tr = ITelephonyRegistry.Stub.asInterface(ServiceManager.getService(
                    "telephony.registry"));
            if (tr != null) {
                tr.removeOnSubscriptionsChangedListener(pkgForDebug, listener.callback);
            }
        } catch (RemoteException ex) {
            Log.e(LOG_TAG, "Remote exception ITelephonyRegistry " + ex);
        }
    }

    /**
     * Get the active SubscriptionInfo with the input subId.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @param subId The unique SubscriptionInfo key in database.
     * @return SubscriptionInfo, maybe null if its not active.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public SubscriptionInfo getActiveSubscriptionInfo(int subId) {
        if (VDBG) logd("[getActiveSubscriptionInfo]+ subId=" + subId);
        if (!isValidSubscriptionId(subId)) {
            if (DBG) {
                logd("[getActiveSubscriptionInfo]- invalid subId");
            }
            return null;
        }

        SubscriptionInfo subInfo = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subInfo = iSub.getActiveSubscriptionInfo(subId, mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subInfo;

    }

    /**
     * Get the active SubscriptionInfo associated with the iccId
     * @param iccId the IccId of SIM card
     * @return SubscriptionInfo, maybe null if its not active
     * @hide
     */
    public SubscriptionInfo getActiveSubscriptionInfoForIccIndex(String iccId) {
        if (VDBG) logd("[getActiveSubscriptionInfoForIccIndex]+ iccId=" + iccId);
        if (iccId == null) {
            logd("[getActiveSubscriptionInfoForIccIndex]- null iccid");
            return null;
        }

        SubscriptionInfo result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubscriptionInfoForIccId(iccId, mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Get the active SubscriptionInfo associated with the slotIndex
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @param slotIndex the slot which the subscription is inserted
     * @return SubscriptionInfo, maybe null if its not active
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public SubscriptionInfo getActiveSubscriptionInfoForSimSlotIndex(int slotIndex) {
        if (VDBG) logd("[getActiveSubscriptionInfoForSimSlotIndex]+ slotIndex=" + slotIndex);
        if (!isValidSlotIndex(slotIndex)) {
            logd("[getActiveSubscriptionInfoForSimSlotIndex]- invalid slotIndex");
            return null;
        }

        SubscriptionInfo result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubscriptionInfoForSimSlotIndex(slotIndex,
                        mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * @return List of all SubscriptionInfo records in database,
     * include those that were inserted before, maybe empty but not null.
     * @hide
     */
    @UnsupportedAppUsage
    public List<SubscriptionInfo> getAllSubscriptionInfoList() {
        if (VDBG) logd("[getAllSubscriptionInfoList]+");

        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoList(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (result == null) {
            result = new ArrayList<>();
        }
        return result;
    }

    /**
     * Get the SubscriptionInfo(s) of the currently active SIM(s). The records will be sorted
     * by {@link SubscriptionInfo#getSimSlotIndex} then by {@link SubscriptionInfo#getSubscriptionId}.
     *
     * <p>Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}). In the latter case, only records accessible
     * to the calling app are returned.
     *
     * @return Sorted list of the currently {@link SubscriptionInfo} records available on the device.
     * <ul>
     * <li>
     * If null is returned the current state is unknown but if a {@link OnSubscriptionsChangedListener}
     * has been registered {@link OnSubscriptionsChangedListener#onSubscriptionsChanged} will be
     * invoked in the future.
     * </li>
     * <li>
     * If the list is empty then there are no {@link SubscriptionInfo} records currently available.
     * </li>
     * <li>
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </li>
     * </ul>
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public List<SubscriptionInfo> getActiveSubscriptionInfoList() {
        return getActiveSubscriptionInfoList(false);
    }

    /**
     * This is similar to {@link #getActiveSubscriptionInfoList()}, but if userVisibleOnly
     * is true, it will filter out the hidden subscriptions.
     *
     * @hide
     */
    public List<SubscriptionInfo> getActiveSubscriptionInfoList(boolean userVisibleOnly) {
        List<SubscriptionInfo> activeList = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                activeList = iSub.getActiveSubscriptionInfoList(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (!userVisibleOnly || activeList == null) {
            return activeList;
        } else {
            return activeList.stream().filter(subInfo -> !shouldHideSubscription(subInfo))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gets the SubscriptionInfo(s) of all available subscriptions, if any.
     *
     * <p>Available subscriptions include active ones (those with a non-negative
     * {@link SubscriptionInfo#getSimSlotIndex()}) as well as inactive but installed embedded
     * subscriptions.
     *
     * <p>The records will be sorted by {@link SubscriptionInfo#getSimSlotIndex} then by
     * {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @return Sorted list of the current {@link SubscriptionInfo} records available on the
     * device.
     * <ul>
     * <li>
     * If null is returned the current state is unknown but if a
     * {@link OnSubscriptionsChangedListener} has been registered
     * {@link OnSubscriptionsChangedListener#onSubscriptionsChanged} will be invoked in the future.
     * <li>
     * If the list is empty then there are no {@link SubscriptionInfo} records currently available.
     * <li>
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </ul>
     *
     * <p>
     * Permissions android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE is required
     * for #getAvailableSubscriptionInfoList to be invoked.
     * @hide
     */
    @SystemApi
    public List<SubscriptionInfo> getAvailableSubscriptionInfoList() {
        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAvailableSubscriptionInfoList(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return result;
    }

    /**
     * Gets the SubscriptionInfo(s) of all embedded subscriptions accessible to the calling app, if
     * any.
     *
     * <p>Only those subscriptions for which the calling app has carrier privileges per the
     * subscription metadata, if any, will be included in the returned list.
     *
     * <p>The records will be sorted by {@link SubscriptionInfo#getSimSlotIndex} then by
     * {@link SubscriptionInfo#getSubscriptionId}.
     *
     * @return Sorted list of the current embedded {@link SubscriptionInfo} records available on the
     * device which are accessible to the caller.
     * <ul>
     * <li>
     * If null is returned the current state is unknown but if a
     * {@link OnSubscriptionsChangedListener} has been registered
     * {@link OnSubscriptionsChangedListener#onSubscriptionsChanged} will be invoked in the future.
     * <li>
     * If the list is empty then there are no {@link SubscriptionInfo} records currently available.
     * <li>
     * if the list is non-empty the list is sorted by {@link SubscriptionInfo#getSimSlotIndex}
     * then by {@link SubscriptionInfo#getSubscriptionId}.
     * </ul>
     */
    public List<SubscriptionInfo> getAccessibleSubscriptionInfoList() {
        List<SubscriptionInfo> result = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAccessibleSubscriptionInfoList(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return result;
    }

    /**
     * Request a refresh of the platform cache of profile information for the eUICC which
     * corresponds to the card ID returned by {@link TelephonyManager#getCardIdForDefaultEuicc()}.
     *
     * <p>Should be called by the EuiccService implementation whenever this information changes due
     * to an operation done outside the scope of a request initiated by the platform to the
     * EuiccService. There is no need to refresh for downloads, deletes, or other operations that
     * were made through the EuiccService.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @see {@link TelephonyManager#getCardIdForDefaultEuicc()} for more information on the card ID.
     *
     * @hide
     */
    @SystemApi
    public void requestEmbeddedSubscriptionInfoListRefresh() {
        int cardId = TelephonyManager.from(mContext).getCardIdForDefaultEuicc();
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.requestEmbeddedSubscriptionInfoListRefresh(cardId);
            }
        } catch (RemoteException ex) {
            logd("requestEmbeddedSubscriptionInfoListFresh for card = " + cardId + " failed.");
        }
    }

    /**
     * Request a refresh of the platform cache of profile information for the eUICC with the given
     * {@code cardId}.
     *
     * <p>Should be called by the EuiccService implementation whenever this information changes due
     * to an operation done outside the scope of a request initiated by the platform to the
     * EuiccService. There is no need to refresh for downloads, deletes, or other operations that
     * were made through the EuiccService.
     *
     * <p>Requires the {@link android.Manifest.permission#WRITE_EMBEDDED_SUBSCRIPTIONS} permission.
     *
     * @param cardId the card ID of the eUICC.
     *
     * @see {@link TelephonyManager#getCardIdForDefaultEuicc()} for more information on the card ID.
     *
     * @hide
     */
    @SystemApi
    public void requestEmbeddedSubscriptionInfoListRefresh(int cardId) {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.requestEmbeddedSubscriptionInfoListRefresh(cardId);
            }
        } catch (RemoteException ex) {
            logd("requestEmbeddedSubscriptionInfoListFresh for card = " + cardId + " failed.");
        }
    }

    /**
     * @return the count of all subscriptions in the database, this includes
     * all subscriptions that have been seen.
     * @hide
     */
    @UnsupportedAppUsage
    public int getAllSubscriptionInfoCount() {
        if (VDBG) logd("[getAllSubscriptionInfoCount]+");

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getAllSubInfoCount(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     *
     * Requires Permission: {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}). In the latter case, the count will include
     * only those subscriptions accessible to the caller.
     *
     * @return the current number of active subscriptions. There is no guarantee the value
     * returned by this method will be the same as the length of the list returned by
     * {@link #getActiveSubscriptionInfoList}.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public int getActiveSubscriptionInfoCount() {
        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubInfoCount(mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * @return the maximum number of active subscriptions that will be returned by
     * {@link #getActiveSubscriptionInfoList} and the value returned by
     * {@link #getActiveSubscriptionInfoCount}.
     */
    public int getActiveSubscriptionInfoCountMax() {
        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getActiveSubInfoCountMax();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Add a new SubscriptionInfo to SubscriptionInfo database if needed
     * @param iccId the IccId of the SIM card
     * @param slotIndex the slot which the SIM is inserted
     * @return the URL of the newly created row or the updated row
     * @hide
     */
    public Uri addSubscriptionInfoRecord(String iccId, int slotIndex) {
        if (VDBG) logd("[addSubscriptionInfoRecord]+ iccId:" + iccId + " slotIndex:" + slotIndex);
        if (iccId == null) {
            logd("[addSubscriptionInfoRecord]- null iccId");
        }
        if (!isValidSlotIndex(slotIndex)) {
            logd("[addSubscriptionInfoRecord]- invalid slotIndex");
        }

        addSubscriptionInfoRecord(iccId, null, slotIndex, SUBSCRIPTION_TYPE_LOCAL_SIM);

        // FIXME: Always returns null?
        return null;

    }

    /**
     * Add a new SubscriptionInfo to SubscriptionInfo database if needed
     * @param uniqueId This is the unique identifier for the subscription within the
     *                 specific subscription type.
     * @param displayName human-readable name of the device the subscription corresponds to.
     * @param slotIndex the slot assigned to this subscription. It is ignored for subscriptionType
     *                  of {@link #SUBSCRIPTION_TYPE_REMOTE_SIM}.
     * @param subscriptionType the {@link #SUBSCRIPTION_TYPE}
     * @hide
     */
    public void addSubscriptionInfoRecord(String uniqueId, String displayName, int slotIndex,
            int subscriptionType) {
        if (VDBG) {
            logd("[addSubscriptionInfoRecord]+ uniqueId:" + uniqueId
                    + ", displayName:" + displayName + ", slotIndex:" + slotIndex
                    + ", subscriptionType: " + subscriptionType);
        }
        if (uniqueId == null) {
            Log.e(LOG_TAG, "[addSubscriptionInfoRecord]- uniqueId is null");
            return;
        }

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub == null) {
                Log.e(LOG_TAG, "[addSubscriptionInfoRecord]- ISub service is null");
                return;
            }
            int result = iSub.addSubInfo(uniqueId, displayName, slotIndex, subscriptionType);
            if (result < 0) {
                Log.e(LOG_TAG, "Adding of subscription didn't succeed: error = " + result);
            } else {
                logd("successfully added new subscription");
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Remove SubscriptionInfo record from the SubscriptionInfo database
     * @param uniqueId This is the unique identifier for the subscription within the specific
     *                 subscription type.
     * @param subscriptionType the {@link #SUBSCRIPTION_TYPE}
     * @hide
     */
    public void removeSubscriptionInfoRecord(String uniqueId, int subscriptionType) {
        if (VDBG) {
            logd("[removeSubscriptionInfoRecord]+ uniqueId:" + uniqueId
                    + ", subscriptionType: " + subscriptionType);
        }
        if (uniqueId == null) {
            Log.e(LOG_TAG, "[addSubscriptionInfoRecord]- uniqueId is null");
            return;
        }

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub == null) {
                Log.e(LOG_TAG, "[removeSubscriptionInfoRecord]- ISub service is null");
                return;
            }
            int result = iSub.removeSubInfo(uniqueId, subscriptionType);
            if (result < 0) {
                Log.e(LOG_TAG, "Removal of subscription didn't succeed: error = " + result);
            } else {
                logd("successfully removed subscription");
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Set SIM icon tint color by simInfo index
     * @param tint the RGB value of icon tint color of the SIM
     * @param subId the unique SubInfoRecord index in database
     * @return the number of records updated
     * @hide
     */
    @UnsupportedAppUsage
    public int setIconTint(int tint, int subId) {
        if (VDBG) logd("[setIconTint]+ tint:" + tint + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setIconTint",
                (iSub)-> iSub.setIconTint(tint, subId)
        );
    }

    /**
     * Set display name by simInfo index
     * @param displayName the display name of SIM card
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    public int setDisplayName(String displayName, int subId) {
        return setDisplayName(displayName, subId, NAME_SOURCE_UNDEFINDED);
    }

    /**
     * Set display name by simInfo index with name source
     * @param displayName the display name of SIM card
     * @param subId the unique SubscriptionInfo index in database
     * @param nameSource 0: NAME_SOURCE_DEFAULT_SOURCE, 1: NAME_SOURCE_SIM_SOURCE,
     *                   2: NAME_SOURCE_USER_INPUT, -1 NAME_SOURCE_UNDEFINED
     * @return the number of records updated or < 0 if invalid subId
     * @hide
     */
    @UnsupportedAppUsage
    public int setDisplayName(String displayName, int subId, long nameSource) {
        if (VDBG) {
            logd("[setDisplayName]+  displayName:" + displayName + " subId:" + subId
                    + " nameSource:" + nameSource);
        }
        return setSubscriptionPropertyHelper(subId, "setDisplayName",
                (iSub)-> iSub.setDisplayNameUsingSrc(displayName, subId, nameSource)
        );
    }

    /**
     * Set phone number by subId
     * @param number the phone number of the SIM
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    @UnsupportedAppUsage
    public int setDisplayNumber(String number, int subId) {
        if (number == null) {
            logd("[setDisplayNumber]- fail");
            return -1;
        }
        return setSubscriptionPropertyHelper(subId, "setDisplayNumber",
                (iSub)-> iSub.setDisplayNumber(number, subId)
        );
    }

    /**
     * Set data roaming by simInfo index
     * @param roaming 0:Don't allow data when roaming, 1:Allow data when roaming
     * @param subId the unique SubscriptionInfo index in database
     * @return the number of records updated
     * @hide
     */
    @UnsupportedAppUsage
    public int setDataRoaming(int roaming, int subId) {
        if (VDBG) logd("[setDataRoaming]+ roaming:" + roaming + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setDataRoaming",
                (iSub)->iSub.setDataRoaming(roaming, subId)
        );
    }

    /**
     * Get slotIndex associated with the subscription.
     *
     * @param subscriptionId the unique SubscriptionInfo index in database
     * @return slotIndex as a positive integer or {@link #INVALID_SIM_SLOT_INDEX} if the supplied
     * subscriptionId doesn't have an associated slot index.
     */
    public static int getSlotIndex(int subscriptionId) {
        if (!isValidSubscriptionId(subscriptionId)) {
            if (DBG) {
                logd("[getSlotIndex]- supplied subscriptionId is invalid.");
            }
        }

        int result = INVALID_SIM_SLOT_INDEX;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSlotIndex(subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;

    }

    /**
     * Get an array of Subscription Ids for specified slot Index.
     * @param slotIndex the slot index.
     * @return subscription Ids or null if the given slot Index is not valid or there are no active
     * subscriptions in the slot.
     */
    @Nullable
    public int[] getSubscriptionIds(int slotIndex) {
        return getSubId(slotIndex);
    }

    /** @hide */
    @UnsupportedAppUsage
    public static int[] getSubId(int slotIndex) {
        if (!isValidSlotIndex(slotIndex)) {
            logd("[getSubId]- fail");
            return null;
        }

        int[] subId = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getSubId(slotIndex);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return subId;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static int getPhoneId(int subId) {
        if (!isValidSubscriptionId(subId)) {
            if (DBG) {
                logd("[getPhoneId]- fail");
            }
            return INVALID_PHONE_INDEX;
        }

        int result = INVALID_PHONE_INDEX;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getPhoneId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("[getPhoneId]- phoneId=" + result);
        return result;

    }

    private static void logd(String msg) {
        Rlog.d(LOG_TAG, msg);
    }

    /**
     * Returns the system's default subscription id.
     *
     * For a voice capable device, it will return getDefaultVoiceSubscriptionId.
     * For a data only device, it will return the getDefaultDataSubscriptionId.
     * May return an INVALID_SUBSCRIPTION_ID on error.
     *
     * @return the "system" default subscription id.
     */
    public static int getDefaultSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultSubId=" + subId);
        return subId;
    }

    /**
     * Returns the system's default voice subscription id.
     *
     * On a data only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default voice subscription Id.
     */
    public static int getDefaultVoiceSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultVoiceSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultVoiceSubscriptionId, sub id = " + subId);
        return subId;
    }

    /** @hide */
    public void setDefaultVoiceSubId(int subId) {
        if (VDBG) logd("setDefaultVoiceSubId sub id = " + subId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultVoiceSubId(subId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Return the SubscriptionInfo for default voice subscription.
     *
     * Will return null on data only devices, or on error.
     *
     * @return the SubscriptionInfo for the default voice subscription.
     * @hide
     */
    @UnsupportedAppUsage
    public SubscriptionInfo getDefaultVoiceSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultVoiceSubscriptionId());
    }

    /** @hide */
    @UnsupportedAppUsage
    public static int getDefaultVoicePhoneId() {
        return getPhoneId(getDefaultVoiceSubscriptionId());
    }

    /**
     * Returns the system's default SMS subscription id.
     *
     * On a data only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default SMS subscription Id.
     */
    public static int getDefaultSmsSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultSmsSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultSmsSubscriptionId, sub id = " + subId);
        return subId;
    }

    /**
     * Set the subscription which will be used by default for SMS, with the subscription which
     * the supplied subscription ID corresponds to; or throw a RuntimeException if the supplied
     * subscription ID is not usable (check with {@link #isUsableSubscriptionId(int)}).
     *
     * @param subscriptionId the supplied subscription ID
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultSmsSubId(int subscriptionId) {
        if (VDBG) logd("setDefaultSmsSubId sub id = " + subscriptionId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultSmsSubId(subscriptionId);
            }
        } catch (RemoteException ex) {
            ex.rethrowFromSystemServer();
        }
    }

    /**
     * Return the SubscriptionInfo for default voice subscription.
     *
     * Will return null on data only devices, or on error.
     *
     * @return the SubscriptionInfo for the default SMS subscription.
     * @hide
     */
    public SubscriptionInfo getDefaultSmsSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultSmsSubscriptionId());
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getDefaultSmsPhoneId() {
        return getPhoneId(getDefaultSmsSubscriptionId());
    }

    /**
     * Returns the system's default data subscription id.
     *
     * On a voice only device or on error, will return INVALID_SUBSCRIPTION_ID.
     *
     * @return the default data subscription Id.
     */
    public static int getDefaultDataSubscriptionId() {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getDefaultDataSubId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getDefaultDataSubscriptionId, sub id = " + subId);
        return subId;
    }

    /**
     * Set the subscription which will be used by default for data, with the subscription which
     * the supplied subscription ID corresponds to; or throw a RuntimeException if the supplied
     * subscription ID is not usable (check with {@link #isUsableSubscriptionId(int)}).
     *
     * @param subscriptionId the supplied subscription ID
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setDefaultDataSubId(int subscriptionId) {
        if (VDBG) logd("setDataSubscription sub id = " + subscriptionId);
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setDefaultDataSubId(subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Return the SubscriptionInfo for default data subscription.
     *
     * Will return null on voice only devices, or on error.
     *
     * @return the SubscriptionInfo for the default data subscription.
     * @hide
     */
    @UnsupportedAppUsage
    public SubscriptionInfo getDefaultDataSubscriptionInfo() {
        return getActiveSubscriptionInfo(getDefaultDataSubscriptionId());
    }

    /** @hide */
    @UnsupportedAppUsage
    public int getDefaultDataPhoneId() {
        return getPhoneId(getDefaultDataSubscriptionId());
    }

    /** @hide */
    public void clearSubscriptionInfo() {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.clearSubInfo();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return;
    }

    //FIXME this is vulnerable to race conditions
    /** @hide */
    public boolean allDefaultsSelected() {
        if (!isValidSubscriptionId(getDefaultDataSubscriptionId())) {
            return false;
        }
        if (!isValidSubscriptionId(getDefaultSmsSubscriptionId())) {
            return false;
        }
        if (!isValidSubscriptionId(getDefaultVoiceSubscriptionId())) {
            return false;
        }
        return true;
    }

    /**
     * If a default is set to subscription which is not active, this will reset that default back to
     * an invalid subscription id, i.e. < 0.
     * @hide
     */
    @UnsupportedAppUsage
    public void clearDefaultsForInactiveSubIds() {
        if (VDBG) logd("clearDefaultsForInactiveSubIds");
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.clearDefaultsForInactiveSubIds();
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Checks if the supplied subscription ID is valid.
     * Note: a valid subscription ID does not necessarily correspond to an active subscription.
     *
     * @param subscriptionId The subscription ID.
     * @return true if the supplied subscriptionId is valid; false otherwise.
     */
    public static boolean isValidSubscriptionId(int subscriptionId) {
        return subscriptionId > INVALID_SUBSCRIPTION_ID;
    }

    /**
     * Check if the subscription ID is usable.
     *
     * A usable subscription ID has a valid value except some special values such as
     * {@link #DEFAULT_SUBSCRIPTION_ID}. It can be used for subscription functions.
     *
     * @param subscriptionId the subscription ID
     * @return {@code true} if the subscription ID is usable; {@code false} otherwise.
     */
    public static boolean isUsableSubscriptionId(int subscriptionId) {
        return isUsableSubIdValue(subscriptionId);
    }

    /**
     * @return true if subId is an usable subId value else false. A
     * usable subId means its neither a INVALID_SUBSCRIPTION_ID nor a DEFAULT_SUB_ID.
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static boolean isUsableSubIdValue(int subId) {
        return subId >= MIN_SUBSCRIPTION_ID_VALUE && subId <= MAX_SUBSCRIPTION_ID_VALUE;
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    public static boolean isValidSlotIndex(int slotIndex) {
        return slotIndex >= 0 && slotIndex < TelephonyManager.getDefault().getSimCount();
    }

    /** @hide */
    @UnsupportedAppUsage
    public static boolean isValidPhoneId(int phoneId) {
        return phoneId >= 0 && phoneId < TelephonyManager.getDefault().getPhoneCount();
    }

    /** @hide */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P)
    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId) {
        int[] subIds = SubscriptionManager.getSubId(phoneId);
        if (subIds != null && subIds.length > 0) {
            putPhoneIdAndSubIdExtra(intent, phoneId, subIds[0]);
        } else {
            logd("putPhoneIdAndSubIdExtra: no valid subs");
            intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
            intent.putExtra(PhoneConstants.SLOT_KEY, phoneId);
        }
    }

    /** @hide */
    @UnsupportedAppUsage
    public static void putPhoneIdAndSubIdExtra(Intent intent, int phoneId, int subId) {
        if (VDBG) logd("putPhoneIdAndSubIdExtra: phoneId=" + phoneId + " subId=" + subId);
        intent.putExtra(PhoneConstants.SUBSCRIPTION_KEY, subId);
        intent.putExtra(EXTRA_SUBSCRIPTION_INDEX, subId);
        intent.putExtra(PhoneConstants.PHONE_KEY, phoneId);
        //FIXME this is using phoneId and slotIndex interchangeably
        //Eventually, this should be removed as it is not the slot id
        intent.putExtra(PhoneConstants.SLOT_KEY, phoneId);
    }

    /**
     * @return the list of subId's that are active,
     *         is never null but the length maybe 0.
     * @hide
     */
    @UnsupportedAppUsage
    public @NonNull int[] getActiveSubscriptionIdList() {
        int[] subId = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getActiveSubIdList();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (subId == null) {
            subId = new int[0];
        }

        return subId;

    }

    /**
     * Returns true if the device is considered roaming on the current
     * network for a subscription.
     * <p>
     * Availability: Only when user registered to a network.
     *
     * @param subId The subscription ID
     * @return true if the network for the subscription is roaming, false otherwise
     */
    public boolean isNetworkRoaming(int subId) {
        final int phoneId = getPhoneId(subId);
        if (phoneId < 0) {
            // What else can we do?
            return false;
        }
        return TelephonyManager.getDefault().isNetworkRoaming(subId);
    }

    /**
     * Returns a constant indicating the state of sim for the slot index.
     *
     * @param slotIndex
     *
     * {@See TelephonyManager#SIM_STATE_UNKNOWN}
     * {@See TelephonyManager#SIM_STATE_ABSENT}
     * {@See TelephonyManager#SIM_STATE_PIN_REQUIRED}
     * {@See TelephonyManager#SIM_STATE_PUK_REQUIRED}
     * {@See TelephonyManager#SIM_STATE_NETWORK_LOCKED}
     * {@See TelephonyManager#SIM_STATE_READY}
     * {@See TelephonyManager#SIM_STATE_NOT_READY}
     * {@See TelephonyManager#SIM_STATE_PERM_DISABLED}
     * {@See TelephonyManager#SIM_STATE_CARD_IO_ERROR}
     *
     * {@hide}
     */
    public static int getSimStateForSlotIndex(int slotIndex) {
        int simState = TelephonyManager.SIM_STATE_UNKNOWN;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                simState = iSub.getSimStateForSlotIndex(slotIndex);
            }
        } catch (RemoteException ex) {
        }

        return simState;
    }

    /**
     * Store properties associated with SubscriptionInfo in database
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in database associated with SubscriptionInfo
     * @param propValue Value to store in DB for particular subId & column name
     * @hide
     */
    public static void setSubscriptionProperty(int subId, String propKey, String propValue) {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                iSub.setSubscriptionProperty(subId, propKey, propValue);
            }
        } catch (RemoteException ex) {
            // ignore it
        }
    }

    /**
     * Store properties associated with SubscriptionInfo in database
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @return Value associated with subId and propKey column in database
     * @hide
     */
    private static String getSubscriptionProperty(int subId, String propKey,
            Context context) {
        String resultValue = null;
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                resultValue = iSub.getSubscriptionProperty(subId, propKey,
                        context.getOpPackageName());
            }
        } catch (RemoteException ex) {
            // ignore it
        }
        return resultValue;
    }

    /**
     * Returns boolean value corresponding to query result.
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @param defValue Default boolean value to be returned
     * @return boolean result value to be returned
     * @hide
     */
    public static boolean getBooleanSubscriptionProperty(int subId, String propKey,
            boolean defValue, Context context) {
        String result = getSubscriptionProperty(subId, propKey, context);
        if (result != null) {
            try {
                return Integer.parseInt(result) == 1;
            } catch (NumberFormatException err) {
                logd("getBooleanSubscriptionProperty NumberFormat exception");
            }
        }
        return defValue;
    }

    /**
     * Returns integer value corresponding to query result.
     * @param subId Subscription Id of Subscription
     * @param propKey Column name in SubscriptionInfo database
     * @param defValue Default integer value to be returned
     * @return integer result value to be returned
     * @hide
     */
    public static int getIntegerSubscriptionProperty(int subId, String propKey, int defValue,
            Context context) {
        String result = getSubscriptionProperty(subId, propKey, context);
        if (result != null) {
            try {
                return Integer.parseInt(result);
            } catch (NumberFormatException err) {
                logd("getBooleanSubscriptionProperty NumberFormat exception");
            }
        }
        return defValue;
    }

    /**
     * Returns the resources associated with Subscription.
     * @param context Context object
     * @param subId Subscription Id of Subscription who's resources are required
     * @return Resources associated with Subscription.
     * @hide
     */
    @UnsupportedAppUsage
    public static Resources getResourcesForSubId(Context context, int subId) {
        return getResourcesForSubId(context, subId, false);
    }

    /**
     * Returns the resources associated with Subscription.
     * @param context Context object
     * @param subId Subscription Id of Subscription who's resources are required
     * @param useRootLocale if root locale should be used. Localized locale is used if false.
     * @return Resources associated with Subscription.
     * @hide
     */
    public static Resources getResourcesForSubId(Context context, int subId,
            boolean useRootLocale) {
        final SubscriptionInfo subInfo =
                SubscriptionManager.from(context).getActiveSubscriptionInfo(subId);

        Configuration config = context.getResources().getConfiguration();
        Configuration newConfig = new Configuration();
        newConfig.setTo(config);
        if (subInfo != null) {
            newConfig.mcc = subInfo.getMcc();
            newConfig.mnc = subInfo.getMnc();
            if (newConfig.mnc == 0) newConfig.mnc = Configuration.MNC_ZERO;
        }

        if (useRootLocale) {
            newConfig.setLocale(Locale.ROOT);
        }

        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        DisplayMetrics newMetrics = new DisplayMetrics();
        newMetrics.setTo(metrics);
        return new Resources(context.getResources().getAssets(), newMetrics, newConfig);
    }

    /**
     * Checks if the supplied subscription ID corresponds to an active subscription.
     *
     * @param subscriptionId the subscription ID.
     * @return {@code true} if the supplied subscription ID corresponds to an active subscription;
     * {@code false} if it does not correspond to an active subscription; or throw a
     * SecurityException if the caller hasn't got the right permission.
     */
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public boolean isActiveSubscriptionId(int subscriptionId) {
        return isActiveSubId(subscriptionId);
    }

    /**
     * @return true if the sub ID is active. i.e. The sub ID corresponds to a known subscription
     * and the SIM providing the subscription is present in a slot and in "LOADED" state.
     * @hide
     */
    @UnsupportedAppUsage
    public boolean isActiveSubId(int subId) {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                return iSub.isActiveSubId(subId, mContext.getOpPackageName());
            }
        } catch (RemoteException ex) {
        }
        return false;
    }

    /**
     * Get the description of the billing relationship plan between a carrier
     * and a specific subscriber.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this relationship applies to
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     */
    public @NonNull List<SubscriptionPlan> getSubscriptionPlans(int subId) {
        try {
            SubscriptionPlan[] subscriptionPlans =
                    getNetworkPolicy().getSubscriptionPlans(subId, mContext.getOpPackageName());
            return subscriptionPlans == null
                    ? Collections.emptyList() : Arrays.asList(subscriptionPlans);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the description of the billing relationship plan between a carrier
     * and a specific subscriber.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this relationship applies to. An empty list
     *            may be sent to clear any existing plans.
     * @param plans the list of plans. The first plan is always the primary and
     *            most important plan. Any additional plans are secondary and
     *            may not be displayed or used by decision making logic.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     */
    public void setSubscriptionPlans(int subId, @NonNull List<SubscriptionPlan> plans) {
        try {
            getNetworkPolicy().setSubscriptionPlans(subId,
                    plans.toArray(new SubscriptionPlan[plans.size()]), mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /** @hide */
    private String getSubscriptionPlansOwner(int subId) {
        try {
            return getNetworkPolicy().getSubscriptionPlansOwner(subId);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Temporarily override the billing relationship plan between a carrier and
     * a specific subscriber to be considered unmetered. This will be reflected
     * to apps via {@link NetworkCapabilities#NET_CAPABILITY_NOT_METERED}.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this override applies to.
     * @param overrideUnmetered set if the billing relationship should be
     *            considered unmetered.
     * @param timeoutMillis the timeout after which the requested override will
     *            be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     */
    public void setSubscriptionOverrideUnmetered(int subId, boolean overrideUnmetered,
            @DurationMillisLong long timeoutMillis) {
        try {
            final int overrideValue = overrideUnmetered ? OVERRIDE_UNMETERED : 0;
            getNetworkPolicy().setSubscriptionOverride(subId, OVERRIDE_UNMETERED, overrideValue,
                    timeoutMillis, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Temporarily override the billing relationship plan between a carrier and
     * a specific subscriber to be considered congested. This will cause the
     * device to delay certain network requests when possible, such as developer
     * jobs that are willing to run in a flexible time window.
     * <p>
     * This method is only accessible to the following narrow set of apps:
     * <ul>
     * <li>The carrier app for this subscriberId, as determined by
     * {@link TelephonyManager#hasCarrierPrivileges()}.
     * <li>The carrier app explicitly delegated access through
     * {@link CarrierConfigManager#KEY_CONFIG_PLANS_PACKAGE_OVERRIDE_STRING}.
     * </ul>
     *
     * @param subId the subscriber this override applies to.
     * @param overrideCongested set if the subscription should be considered
     *            congested.
     * @param timeoutMillis the timeout after which the requested override will
     *            be automatically cleared, or {@code 0} to leave in the
     *            requested state until explicitly cleared, or the next reboot,
     *            whichever happens first.
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     */
    public void setSubscriptionOverrideCongested(int subId, boolean overrideCongested,
            @DurationMillisLong long timeoutMillis) {
        try {
            final int overrideValue = overrideCongested ? OVERRIDE_CONGESTED : 0;
            getNetworkPolicy().setSubscriptionOverride(subId, OVERRIDE_CONGESTED, overrideValue,
                    timeoutMillis, mContext.getOpPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Create an {@link Intent} that can be launched towards the carrier app
     * that is currently defining the billing relationship plan through
     * {@link #setSubscriptionPlans(int, List)}.
     *
     * @return ready to launch Intent targeted towards the carrier app, or
     *         {@code null} if no carrier app is defined, or if the defined
     *         carrier app provides no management activity.
     * @hide
     */
    public @Nullable Intent createManageSubscriptionIntent(int subId) {
        // Bail if no owner
        final String owner = getSubscriptionPlansOwner(subId);
        if (owner == null) return null;

        // Bail if no plans
        final List<SubscriptionPlan> plans = getSubscriptionPlans(subId);
        if (plans.isEmpty()) return null;

        final Intent intent = new Intent(ACTION_MANAGE_SUBSCRIPTION_PLANS);
        intent.setPackage(owner);
        intent.putExtra(EXTRA_SUBSCRIPTION_INDEX, subId);

        // Bail if not implemented
        if (mContext.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY).isEmpty()) {
            return null;
        }

        return intent;
    }

    /** @hide */
    private @Nullable Intent createRefreshSubscriptionIntent(int subId) {
        // Bail if no owner
        final String owner = getSubscriptionPlansOwner(subId);
        if (owner == null) return null;

        // Bail if no plans
        final List<SubscriptionPlan> plans = getSubscriptionPlans(subId);
        if (plans.isEmpty()) return null;

        final Intent intent = new Intent(ACTION_REFRESH_SUBSCRIPTION_PLANS);
        intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.setPackage(owner);
        intent.putExtra(EXTRA_SUBSCRIPTION_INDEX, subId);

        // Bail if not implemented
        if (mContext.getPackageManager().queryBroadcastReceivers(intent, 0).isEmpty()) {
            return null;
        }

        return intent;
    }

    /**
     * Check if there is a carrier app that is currently defining the billing
     * relationship plan through {@link #setSubscriptionPlans(int, List)} that
     * supports refreshing of subscription plans.
     *
     * @hide
     */
    public boolean isSubscriptionPlansRefreshSupported(int subId) {
        return createRefreshSubscriptionIntent(subId) != null;
    }

    /**
     * Request that the carrier app that is currently defining the billing
     * relationship plan through {@link #setSubscriptionPlans(int, List)}
     * refresh its subscription plans.
     * <p>
     * If the app is able to successfully update the plans, you'll expect to
     * receive the {@link #ACTION_SUBSCRIPTION_PLANS_CHANGED} broadcast.
     *
     * @hide
     */
    public void requestSubscriptionPlansRefresh(int subId) {
        final Intent intent = createRefreshSubscriptionIntent(subId);
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setTemporaryAppWhitelistDuration(TimeUnit.MINUTES.toMillis(1));
        mContext.sendBroadcast(intent, null, options.toBundle());
    }

    /**
     * Checks whether the app with the given context is authorized to manage the given subscription
     * according to its metadata. Only supported for embedded subscriptions (if
     * {@code SubscriptionInfo#isEmbedded} returns true).
     *
     * @param info The subscription to check.
     * @return whether the app is authorized to manage this subscription per its metadata.
     * @throws IllegalArgumentException if this subscription is not embedded.
     */
    public boolean canManageSubscription(SubscriptionInfo info) {
        return canManageSubscription(info, mContext.getPackageName());
    }

    /**
     * Checks whether the given app is authorized to manage the given subscription. An app can only
     * be authorized if it is included in the {@link android.telephony.UiccAccessRule} of the
     * {@link android.telephony.SubscriptionInfo} with the access status.
     * Only supported for embedded subscriptions (if {@link SubscriptionInfo#isEmbedded}
     * returns true).
     *
     * @param info The subscription to check.
     * @param packageName Package name of the app to check.
     * @return whether the app is authorized to manage this subscription per its access rules.
     * @throws IllegalArgumentException if this subscription is not embedded.
     * @hide
     */
    public boolean canManageSubscription(SubscriptionInfo info, String packageName) {
        if (!info.isEmbedded()) {
            throw new IllegalArgumentException("Not an embedded subscription");
        }
        if (info.getAccessRules() == null) {
            return false;
        }
        PackageManager packageManager = mContext.getPackageManager();
        PackageInfo packageInfo;
        try {
            packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalArgumentException("Unknown package: " + packageName, e);
        }
        for (UiccAccessRule rule : info.getAccessRules()) {
            if (rule.getCarrierPrivilegeStatus(packageInfo)
                    == TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return true;
            }
        }
        return false;
    }

    /**
     * Set which subscription is preferred for cellular data.
     * It's also usually the subscription we set up internet connection on.
     *
     * PreferredData overwrites user setting of default data subscription. And it's used
     * by AlternativeNetworkService or carrier apps to switch primary and CBRS
     * subscription dynamically in multi-SIM devices.
     *
     * @param subId which subscription is preferred to for cellular data. If it's
     *              {@link SubscriptionManager#DEFAULT_SUBSCRIPTION_ID}, it means
     *              it's unset and {@link SubscriptionManager#getDefaultDataSubscriptionId()}
     *              is used to determine which modem is preferred.
     * @hide
     *
     */
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public void setPreferredDataSubscriptionId(int subId) {
        if (VDBG) logd("[setPreferredDataSubscriptionId]+ subId:" + subId);
        setSubscriptionPropertyHelper(DEFAULT_SUBSCRIPTION_ID, "setPreferredDataSubscriptionId",
                (iSub)-> iSub.setPreferredDataSubscriptionId(subId));
    }

    /**
     * Get which subscription is preferred for cellular data.
     * It's also usually the subscription we set up internet connection on.
     *
     * PreferredData overwrites user setting of default data subscription. And it's used
     * by AlternativeNetworkService or carrier apps to switch primary and CBRS
     * subscription dynamically in multi-SIM devices.
     *
     * @return preferred subscription id for cellular data. {@link DEFAULT_SUBSCRIPTION_ID} if
     * there's no prefered subscription.
     *
     * @hide
     *
     */
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getPreferredDataSubscriptionId() {
        int preferredSubId = SubscriptionManager.DEFAULT_SUBSCRIPTION_ID;
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                preferredSubId = iSub.getPreferredDataSubscriptionId();
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return preferredSubId;
    }

    /**
     * Return opportunistic subscriptions that can be visible to the caller.
     * Opportunistic subscriptions are for opportunistic networks, which are cellular
     * networks with limited capabilities and coverage, for example, CBRS.
     *
     * <p>Requires Permission:
     * {@link android.Manifest.permission#READ_PHONE_STATE READ_PHONE_STATE}
     * or that the calling app has carrier privileges (see
     * {@link TelephonyManager#hasCarrierPrivileges}).
     *
     * @return the list of opportunistic subscription info. If none exists, an empty list.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.READ_PHONE_STATE)
    public @NonNull List<SubscriptionInfo> getOpportunisticSubscriptions() {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        List<SubscriptionInfo> subInfoList = null;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subInfoList = iSub.getOpportunisticSubscriptions(pkgForDebug);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (subInfoList == null) {
            subInfoList = new ArrayList<>();
        }

        return subInfoList;
    }

    /**
     * Switch to a certain subscription
     *
     *  @param subId sub id
     *  @param callbackIntent pending intent that will be sent after operation is done.
     */
    @RequiresPermission(android.Manifest.permission.WRITE_EMBEDDED_SUBSCRIPTIONS)
    public void switchToSubscription(int subId, PendingIntent callbackIntent) {
        EuiccManager euiccManager = new EuiccManager(mContext);
        euiccManager.switchToSubscription(subId, callbackIntent);
    }

    /**
     * Set whether a subscription is opportunistic, that is, whether the network it connects
     * to has limited coverage. For example, CBRS. Setting a subscription opportunistic has
     * following impacts:
     *  1) Even if it's active, it will be dormant most of the time. The modem will not try
     *     to scan or camp until it knows an available network is nearby to save power.
     *  2) Telephony relies on system app or carrier input to notify nearby available networks.
     *     See {@link TelephonyManager#updateAvailableNetworks(List)} for more information.
     *  3) In multi-SIM devices, when the network is nearby and camped, system may automatically
     *     switch internet data between it and default data subscription, based on carrier
     *     recommendation and its signal strength and metered-ness, etc.
     *
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE} or carrier
     * privilege permission of the subscription.
     *
     * @param opportunistic whether it’s opportunistic subscription.
     * @param subId the unique SubscriptionInfo index in database
     * @return {@code true} if the operation is succeed, {@code false} otherwise.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setOpportunistic(boolean opportunistic, int subId) {
        if (VDBG) logd("[setOpportunistic]+ opportunistic:" + opportunistic + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setOpportunistic",
                (iSub)-> iSub.setOpportunistic(
                        opportunistic, subId, mContext.getOpPackageName())) == 1;
    }

    /**
     * Inform SubscriptionManager that subscriptions in the list are bundled
     * as a group. Typically it's a primary subscription and an opportunistic
     * subscription. It should only affect multi-SIM scenarios where primary
     * and opportunistic subscriptions can be activated together.
     * Being in the same group means they might be activated or deactivated
     * together, some of them may be invisible to the users, etc.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions:
     * {@link TelephonyManager#hasCarrierPrivileges()} or
     * {@link #canManageSubscription(SubscriptionInfo)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     *
     * @param subIdList list of subId that will be in the same group
     * @return groupUUID a UUID assigned to the subscription group. It returns
     * null if fails.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public @Nullable String setSubscriptionGroup(@NonNull int[] subIdList) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (VDBG) {
            logd("[setSubscriptionGroup]+ subIdList:" + Arrays.toString(subIdList));
        }

        String groupUUID = null;
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                groupUUID = iSub.setSubscriptionGroup(subIdList, pkgForDebug);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return groupUUID;
    }

    /**
     * Remove a list of subscriptions from their subscription group.
     * See {@link #setSubscriptionGroup(int[])} for more details.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE}
     * permission or had carrier privilege permission on the subscriptions:
     * {@link TelephonyManager#hasCarrierPrivileges()} or
     * {@link #canManageSubscription(SubscriptionInfo)}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     *
     * @param subIdList list of subId that need removing from their groups.
     * @return whether the operation succeeds.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.MODIFY_PHONE_STATE)
    public boolean removeSubscriptionsFromGroup(@NonNull int[] subIdList) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (VDBG) {
            logd("[removeSubscriptionsFromGroup]+ subIdList:" + Arrays.toString(subIdList));
        }

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                return iSub.removeSubscriptionsFromGroup(subIdList, pkgForDebug);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return false;
    }

    /**
     * Get subscriptionInfo list of subscriptions that are in the same group of given subId.
     * See {@link #setSubscriptionGroup(int[])} for more details.
     *
     * Caller will either have {@link android.Manifest.permission#READ_PHONE_STATE}
     * permission or had carrier privilege permission on the subscription.
     * {@link TelephonyManager#hasCarrierPrivileges()}
     *
     * @throws SecurityException if the caller doesn't meet the requirements
     *             outlined above.
     *
     * @param subId of which list of subInfo from the same group will be returned.
     * @return list of subscriptionInfo that belong to the same group, including the given
     * subscription itself. It will return null if the subscription doesn't exist or it
     * doesn't belong to any group.
     *
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    public @Nullable List<SubscriptionInfo> getSubscriptionsInGroup(int subId) {
        String pkgForDebug = mContext != null ? mContext.getOpPackageName() : "<unknown>";
        if (VDBG) {
            logd("[getSubscriptionsInGroup]+ subId:" + subId);
        }

        List<SubscriptionInfo> result = null;
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = iSub.getSubscriptionsInGroup(subId, pkgForDebug);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }

    /**
     * Set if a subscription is metered or not. Similar to Wi-Fi, metered means
     * user may be charged more if more data is used.
     *
     * By default all Cellular networks are considered metered. System or carrier privileged apps
     * can set a subscription un-metered which will be considered when system switches data between
     * primary subscription and opportunistic subscription.
     *
     * Caller will either have {@link android.Manifest.permission#MODIFY_PHONE_STATE} or carrier
     * privilege permission of the subscription.
     *
     * @param isMetered whether it’s a metered subscription.
     * @param subId the unique SubscriptionInfo index in database
     * @return {@code true} if the operation is succeed, {@code false} otherwise.
     */
    @SuppressAutoDoc // Blocked by b/72967236 - no support for carrier privileges
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setMetered(boolean isMetered, int subId) {
        if (VDBG) logd("[setIsMetered]+ isMetered:" + isMetered + " subId:" + subId);
        return setSubscriptionPropertyHelper(subId, "setIsMetered",
                (iSub)-> iSub.setMetered(isMetered, subId, mContext.getOpPackageName())) == 1;
    }

    /**
     * Whether system UI should hide a subscription. If it's a bundled opportunistic
     * subscription, it shouldn't show up in anywhere in Settings app, dialer app,
     * or status bar.
     *
     * @param info the subscriptionInfo to check against.
     * @return true if this subscription should be hidden.
     *
     * @hide
     */
    public static boolean shouldHideSubscription(SubscriptionInfo info) {
        return (info != null && !TextUtils.isEmpty(info.getGroupUuid()) && info.isOpportunistic());
    }

    /**
     * Return a list of subscriptions that are available and visible to the user.
     * Used by Settings app to show a list of subscriptions for user to pick.
     *
     * <p>
     * Permissions android.Manifest.permission.READ_PRIVILEGED_PHONE_STATE is required
     * for getSelectableSubscriptionInfoList to be invoked.
     * @return list of user selectable subscriptions.
     *
     * @hide
     */
    public @Nullable List<SubscriptionInfo> getSelectableSubscriptionInfoList() {
        List<SubscriptionInfo> availableList = getAvailableSubscriptionInfoList();
        if (availableList == null) {
            return null;
        } else {
            return availableList.stream().filter(subInfo -> !shouldHideSubscription(subInfo))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Enabled or disable a subscription. This is currently used in the settings page.
     *
     * <p>
     * Permissions android.Manifest.permission.MODIFY_PHONE_STATE is required
     *
     * @param enable whether user is turning it on or off.
     * @param subscriptionId Subscription to be enabled or disabled.
     *                       It could be a eSIM or pSIM subscription.
     *
     * @return whether the operation is successful.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.MODIFY_PHONE_STATE)
    public boolean setSubscriptionEnabled(int subscriptionId, boolean enable) {
        if (VDBG) {
            logd("setSubscriptionActivated subId= " + subscriptionId + " enable " + enable);
        }
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                return iSub.setSubscriptionEnabled(enable, subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return false;
    }

    /**
     * Returns whether the subscription is enabled or not. This is different from activated
     * or deactivated for two aspects. 1) For when user disables a physical subscription, we
     * actually disable the modem because we can't switch off the subscription. 2) For eSIM,
     * user may enable one subscription but the system may activate another temporarily. In this
     * case, user enabled one is different from current active one.

     * @param subscriptionId The subscription it asks about.
     * @return whether it's enabled or not. {@code true} if user set this subscription enabled
     * earlier, or user never set subscription enable / disable on this slot explicitly, and
     * this subscription is currently active. Otherwise, it returns {@code false}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public boolean isSubscriptionEnabled(int subscriptionId) {
        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                return iSub.isSubscriptionEnabled(subscriptionId);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return false;
    }

    /**
     * Get which subscription is enabled on this slot. See {@link #isSubscriptionEnabled(int)}
     * for more details.
     *
     * @param slotIndex which slot it asks about.
     * @return which subscription is enabled on this slot. If there's no enabled subscription
     *         in this slot, it will return {@link SubscriptionManager#INVALID_SUBSCRIPTION_ID}.
     *
     * @hide
     */
    @SystemApi
    @RequiresPermission(Manifest.permission.READ_PRIVILEGED_PHONE_STATE)
    public int getEnabledSubscriptionId(int slotIndex) {
        int subId = INVALID_SUBSCRIPTION_ID;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                subId = iSub.getEnabledSubscriptionId(slotIndex);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        if (VDBG) logd("getEnabledSubscriptionId, subId = " + subId);
        return subId;
    }

    private interface CallISubMethodHelper {
        int callMethod(ISub iSub) throws RemoteException;
    }

    private int setSubscriptionPropertyHelper(int subId, String methodName,
            CallISubMethodHelper helper) {
        if (!isValidSubscriptionId(subId)) {
            logd("[" + methodName + "]" + "- fail");
            return -1;
        }

        int result = 0;

        try {
            ISub iSub = ISub.Stub.asInterface(ServiceManager.getService("isub"));
            if (iSub != null) {
                result = helper.callMethod(iSub);
            }
        } catch (RemoteException ex) {
            // ignore it
        }

        return result;
    }
}
