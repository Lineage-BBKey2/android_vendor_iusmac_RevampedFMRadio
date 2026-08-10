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

package com.android.fmradio;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import qcom.fmradio.FmConfig;
import qcom.fmradio.FmReceiver;
import qcom.fmradio.FmRxEvCallbacksAdaptor;
import qcom.fmradio.FmRxRdsData;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;

/**
 * Compatibility layer between RevampedFMRadio and Qualcomm's qcom.fmradio
 * framework used by FM2.
 *
 * The original Revamped backend directly opens /dev/radio0, which is not
 * present on the BlackBerry SDM660 devices. qcom.fmradio instead uses the
 * working Qualcomm FM backend already used by FM2.
 */
public class FmNative {
    private static final String TAG = "FmNativeQcom";

    static {
        Log.d(TAG, "Loading Qualcomm FM JNI");
        System.loadLibrary("qcomfm_jni");
    }

    /*
     * FM2 passes this same legacy path to the FmReceiver constructor.
     * qcom.fmradio does not depend on the node being directly opened by
     * RevampedFMRadio.
     */
    private static final String FM_DEVICE_PATH = "/dev/radio0";

    private static final Object EVENT_LOCK = new Object();

    private static Context sContext;
    private static FmReceiver sReceiver;
    private static FmConfig sFmConfig;

    private static boolean sEnableDone;
    private static boolean sDisableDone;
    private static boolean sSearchDone;
    private static boolean sAutoScanInProgress;
    private static boolean sAutoScanDone;

    /*
     * Qualcomm's seek/scan engine must begin on the channel raster selected
     * by FmConfig. Direct tuning can use intermediate frequencies, so an
     * off-raster tune may need to be moved temporarily before a search.
     */
    private static boolean sTuneWaitInProgress;
    private static boolean sTuneDone;

    private static int sSearchFrequencyKhz;
    private static int sCurrentFrequencyKhz;
    private static int sTuneTargetKhz;

    private static final long POWER_TIMEOUT_MS = 5000;
    private static final long TUNE_TIMEOUT_MS = 5000;
    private static final long SEARCH_TIMEOUT_MS = 15000;

    private static final LinkedHashSet<Integer> sAutoScanStationsKhz =
            new LinkedHashSet<>();

    private static final long AUTO_SCAN_TIMEOUT_MS = 300000;

    /*
     * RevampedFMRadio RDS event bits.
     * These values match its original Qualcomm backend.
     */
    private static final int RDS_EVT_PS_UPDATE = 0x0008;
    private static final int RDS_EVT_RT_UPDATE = 0x0040;

    private static int sRdsEvents;

    private static byte[] sPs = new byte[0];
    private static byte[] sRt = new byte[0];

    /*
     * Qualcomm can report an incomplete PS value shortly before a corrected
     * version of the same eight-character PS string. Hold each new PS briefly
     * so a completion such as "Sprock  " -> "Sprocket" can replace the
     * incomplete value before RevampedFMRadio sees it.
     *
     * Genuinely different PS strings are not combined.
     */
    private static final long PS_STABILIZE_MS = 2000;

    /*
     * RadioText is commonly delivered as a rapidly growing series of partial
     * strings. Publish only after the callback stream has been quiet briefly.
     */
    private static final long RT_STABILIZE_MS = 1000;

    private static final Handler RDS_HANDLER =
            new Handler(Looper.getMainLooper());

    private static String sPendingPs;
    private static String sPublishedPs;

    private static String sPendingRt;
    private static String sPublishedRt;

    private static final Runnable PS_PUBLISH_RUNNABLE = () -> {
        synchronized (EVENT_LOCK) {
            publishPendingPsLocked();
        }
    };

    private static final Runnable RT_PUBLISH_RUNNABLE = () -> {
        synchronized (EVENT_LOCK) {
            publishPendingRtLocked();
        }
    };

    /*
     * Treat only characters that have actually appeared as missing/corrupt
     * RDS data as holes which a later PS callback may fill.
     *
     * Do not treat every kind of whitespace as invalid: ordinary spaces are
     * legitimate characters in an eight-character Program Service string.
     * They are considered "missing" only while comparing two otherwise
     * compatible PS values.
     */
    private static boolean isMissingPsChar(char c) {
        return c == ' ' ||
                c == '\0' ||
                c == '\uFFFD';
    }

    /*
     * Return true only when 'newer' is a more complete version of 'older'.
     *
     * All existing non-missing characters must remain identical. The only
     * permitted differences are positions where the older value had a
     * missing character and the newer value supplies a real character.
     */
    private static boolean isPsCompletion(String older, String newer) {
        if (older == null ||
                newer == null ||
                older.length() != newer.length()) {
            return false;
        }

        boolean filledCharacter = false;

        for (int i = 0; i < older.length(); i++) {
            char oldChar = older.charAt(i);
            char newChar = newer.charAt(i);

            if (oldChar == newChar) {
                continue;
            }

            /*
             * A correction may only fill a position which was previously
             * missing. It must never replace one real character with another
             * or remove a character which was already present.
             */
            if (isMissingPsChar(oldChar) &&
                    !isMissingPsChar(newChar)) {
                filledCharacter = true;
                continue;
            }

            return false;
        }

        return filledCharacter;
    }

    /*
     * Publish the pending PS to RevampedFMRadio.
     *
     * EVENT_LOCK must already be held.
     */
    private static void publishPendingPsLocked() {
        if (sPendingPs == null) {
            return;
        }

        /*
         * If this is being published early because a different PS frame
         * arrived, cancel the old delayed callback. This is harmless when
         * called by PS_PUBLISH_RUNNABLE itself.
         */
        RDS_HANDLER.removeCallbacks(PS_PUBLISH_RUNNABLE);

        String ps = sPendingPs;
        sPendingPs = null;

        /*
         * Do not wake Revamped's RDS polling thread for an exact duplicate
         * of the value it already consumed.
         */
        if (ps.equals(sPublishedPs)) {
            Log.d(TAG, "RDS PS duplicate suppressed: [" + ps + "]");
            return;
        }

        sPublishedPs = ps;
        sPs = ps.getBytes(StandardCharsets.UTF_8);
        sRdsEvents |= RDS_EVT_PS_UPDATE;

        Log.d(TAG, "RDS PS publish: [" + ps + "]");
    }

    /*
     * Queue a Qualcomm PS callback for stabilization.
     *
     * A new PS is held for at most PS_STABILIZE_MS. If a more complete
     * version arrives during that window, replace the pending value without
     * restarting the timer.
     *
     * If a genuinely different PS string arrives, publish the previous
     * value immediately and begin a new stabilization window for the new
     * frame.
     */
    private static void queuePsUpdate(String ps) {
        synchronized (EVENT_LOCK) {
            /*
             * If Qualcomm returns to the value which is already displayed
             * while another value is still pending, consider the pending
             * value transient and discard it.
             */
            if (ps.equals(sPublishedPs)) {
                if (sPendingPs != null &&
                        !sPendingPs.equals(ps)) {
                    Log.d(TAG, "RDS PS transient discarded: [" +
                            sPendingPs + "]");

                    RDS_HANDLER.removeCallbacks(PS_PUBLISH_RUNNABLE);
                    sPendingPs = null;
                }

                Log.d(TAG, "RDS PS duplicate suppressed: [" + ps + "]");
                return;
            }

            /*
             * First value in a new stabilization window.
             */
            if (sPendingPs == null) {
                sPendingPs = ps;

                RDS_HANDLER.postDelayed(
                        PS_PUBLISH_RUNNABLE,
                        PS_STABILIZE_MS);

                return;
            }

            /*
             * An identical callback adds no information. Keep the original
             * deadline instead of extending the stabilization window.
             */
            if (ps.equals(sPendingPs)) {
                return;
            }

            /*
             * The newer value filled one or more holes without changing any
             * existing real characters. Keep the corrected value, but retain
             * the original deadline.
             */
            if (isPsCompletion(sPendingPs, ps)) {
                Log.d(TAG, "RDS PS completion: [" +
                        sPendingPs + "] -> [" + ps + "]");

                sPendingPs = ps;
                return;
            }

            /*
             * If the new callback is merely a less-complete version of the
             * value we are already holding, ignore the regression.
             */
            if (isPsCompletion(ps, sPendingPs)) {
                Log.d(TAG, "RDS PS regression ignored: [" +
                        ps + "]");

                return;
            }

            /*
             * This is a genuinely different PS frame. Do not merge it with
             * the previous one. Publish the previous pending value now and
             * start a fresh stabilization window.
             */
            publishPendingPsLocked();

            sPendingPs = ps;

            RDS_HANDLER.postDelayed(
                    PS_PUBLISH_RUNNABLE,
                    PS_STABILIZE_MS);
        }
    }

    /*
     * Clear both pending and already-published PS state.
     *
     * EVENT_LOCK must already be held.
     */
    private static void resetPsStabilizerLocked() {
        RDS_HANDLER.removeCallbacks(PS_PUBLISH_RUNNABLE);

        sPendingPs = null;
        sPublishedPs = null;
    }

    /*
     * Publish the pending RadioText to RevampedFMRadio.
     *
     * EVENT_LOCK must already be held.
     */
    private static void publishPendingRtLocked() {
        if (sPendingRt == null) {
            return;
        }

        RDS_HANDLER.removeCallbacks(RT_PUBLISH_RUNNABLE);

        String rt = sPendingRt;
        sPendingRt = null;

        /*
         * Do not wake Revamped's RDS polling thread for an exact duplicate
         * of the RadioText it has already received.
         */
        if (rt.equals(sPublishedRt)) {
            Log.d(TAG, "RDS RT duplicate suppressed: [" + rt + "]");
            return;
        }

        sPublishedRt = rt;
        sRt = rt.getBytes(StandardCharsets.UTF_8);
        sRdsEvents |= RDS_EVT_RT_UPDATE;

        Log.d(TAG, "RDS RT publish: [" + rt + "]");
    }

    /*
     * Queue a Qualcomm RadioText callback for quiet-period stabilization.
     *
     * Unlike PS, RadioText does not need character-by-character correction.
     * Each genuinely new value replaces the pending value and restarts the
     * timer. The latest value is published once the callback stream has
     * remained quiet for RT_STABILIZE_MS.
     */
    private static void queueRtUpdate(String rt) {
        synchronized (EVENT_LOCK) {
            /*
             * An identical pending callback adds no new information and
             * should not extend the quiet-period timer.
             */
            if (rt.equals(sPendingRt)) {
                return;
            }

            /*
             * If there is no pending update and this is identical to the
             * value already published, suppress it immediately.
             */
            if (sPendingRt == null &&
                    rt.equals(sPublishedRt)) {
                Log.d(TAG, "RDS RT duplicate suppressed: [" + rt + "]");
                return;
            }

            sPendingRt = rt;

            /*
             * A genuinely new RadioText value restarts the quiet-period
             * timer. Rapid progressive updates therefore collapse into the
             * final value in the sequence.
             */
            RDS_HANDLER.removeCallbacks(RT_PUBLISH_RUNNABLE);
            RDS_HANDLER.postDelayed(
                    RT_PUBLISH_RUNNABLE,
                    RT_STABILIZE_MS);
        }
    }

    /*
     * Clear pending and published RadioText stabilization state.
     *
     * EVENT_LOCK must already be held.
     */
    private static void resetRtStabilizerLocked() {
        RDS_HANDLER.removeCallbacks(RT_PUBLISH_RUNNABLE);

        sPendingRt = null;
        sPublishedRt = null;
    }

    private static final FmRxEvCallbacksAdaptor sCallbacks =
            new FmRxEvCallbacksAdaptor() {
        public void FmRxEvEnableReceiver() {
            Log.d(TAG, "FmRxEvEnableReceiver");

            synchronized (EVENT_LOCK) {
                sEnableDone = true;
                EVENT_LOCK.notifyAll();
            }
        }

        public void FmRxEvDisableReceiver() {
            Log.d(TAG, "FmRxEvDisableReceiver");

            synchronized (EVENT_LOCK) {
                sDisableDone = true;
                EVENT_LOCK.notifyAll();
            }
        }

        public void FmRxEvRadioTuneStatus(int frequency) {
            Log.d(TAG, "FmRxEvRadioTuneStatus: " + frequency);

            synchronized (EVENT_LOCK) {
                sCurrentFrequencyKhz = frequency;

                /*
                 * An off-raster seek/scan first performs a temporary tune to a
                 * valid search-raster frequency. Do not start the search until
                 * Qualcomm confirms that tune has completed.
                 */
                if (sTuneWaitInProgress && frequency == sTuneTargetKhz) {
                    Log.d(TAG, "Search-raster tune complete: " +
                            frequency + " kHz");

                    sTuneDone = true;
                    sTuneWaitInProgress = false;
                    EVENT_LOCK.notifyAll();
                }

                if (sAutoScanInProgress &&
                        isFrequencyInConfiguredBand(frequency)) {
                    if (sAutoScanStationsKhz.add(frequency)) {
                        Log.d(TAG, "autoScan station: " +
                                frequency + " kHz");
                    }
                }
            }
        }

        public void FmRxEvSearchComplete(int frequency) {
            Log.d(TAG, "FmRxEvSearchComplete: " + frequency);

            synchronized (EVENT_LOCK) {
                if (sAutoScanInProgress) {
                    Log.d(TAG, "autoScan complete");

                    sAutoScanInProgress = false;
                    sAutoScanDone = true;
                    EVENT_LOCK.notifyAll();
                    return;
                }

                sSearchFrequencyKhz = frequency;
                sCurrentFrequencyKhz = frequency;
                sSearchDone = true;
                EVENT_LOCK.notifyAll();
            }
        }

        public void FmRxEvSearchCancelled() {
            Log.d(TAG, "FmRxEvSearchCancelled");

            synchronized (EVENT_LOCK) {
                if (sAutoScanInProgress) {
                    sAutoScanInProgress = false;
                    sAutoScanDone = true;
                } else {
                    sSearchDone = true;
                }

                EVENT_LOCK.notifyAll();
            }
        }

        public void FmRxEvRdsPsInfo() {
            Log.d(TAG, "FmRxEvRdsPsInfo");

            final FmReceiver receiver = sReceiver;
            if (receiver == null) {
                return;
            }

            FmRxRdsData data = receiver.getPSInfo();
            if (data == null) {
                return;
            }

            String ps = data.getPrgmServices();
            if (ps == null) {
                ps = "";
            }

            /*
             * Log the exact value returned by Qualcomm before stabilization so
             * runtime testing can distinguish backend corruption from the value
             * eventually exposed to RevampedFMRadio.
             */
            Log.d(TAG, "RDS PS raw: [" + ps + "]");

            queuePsUpdate(ps);
        }

            public void FmRxEvRdsRtInfo() {
                Log.d(TAG, "FmRxEvRdsRtInfo");

                final FmReceiver receiver = sReceiver;
                if (receiver == null) {
                    return;
                }

                FmRxRdsData data = receiver.getRTInfo();
                if (data == null) {
                    return;
                }

                String rt = data.getRadioText();
                if (rt == null) {
                    rt = "";
                }

                /*
                 * Log the exact RadioText returned by Qualcomm before stabilization.
                 */
                Log.d(TAG, "RDS RT raw: [" + rt + "]");

                queueRtUpdate(rt);
            }

            public void FmRxEvRdsAfInfo() {
                Log.d(TAG, "FmRxEvRdsAfInfo");

                /*
                 * This callback reports an AF list update. It is not the same
                 * thing as Revamped's RDS_EVT_AF_JUMP, so don't expose it as
                 * an AF-jump event yet.
                 */
                if (sReceiver != null) {
                    sReceiver.getAFInfo();
                }
            }
    };

    private static boolean waitForAutoScan() {
        final long deadline =
                SystemClock.elapsedRealtime() + AUTO_SCAN_TIMEOUT_MS;

        synchronized (EVENT_LOCK) {
            while (!sAutoScanDone) {
                long remaining =
                        deadline - SystemClock.elapsedRealtime();

                if (remaining <= 0) {
                    Log.e(TAG, "Timed out waiting for auto scan");
                    return false;
                }

                try {
                    EVENT_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean waitForEnable() {
        final long deadline =
                SystemClock.elapsedRealtime() + POWER_TIMEOUT_MS;

        synchronized (EVENT_LOCK) {
            while (!sEnableDone) {
                long remaining =
                        deadline - SystemClock.elapsedRealtime();

                if (remaining <= 0) {
                    Log.e(TAG, "Timed out waiting for FM enable");
                    return false;
                }

                try {
                    EVENT_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean waitForDisable() {
        final long deadline =
                SystemClock.elapsedRealtime() + POWER_TIMEOUT_MS;

        synchronized (EVENT_LOCK) {
            while (!sDisableDone) {
                long remaining =
                        deadline - SystemClock.elapsedRealtime();

                if (remaining <= 0) {
                    Log.e(TAG, "Timed out waiting for FM disable");
                    return false;
                }

                try {
                    EVENT_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean waitForSearch() {
        final long deadline =
                SystemClock.elapsedRealtime() + SEARCH_TIMEOUT_MS;

        synchronized (EVENT_LOCK) {
            while (!sSearchDone) {
                long remaining =
                        deadline - SystemClock.elapsedRealtime();

                if (remaining <= 0) {
                    Log.e(TAG, "Timed out waiting for FM search");
                    return false;
                }

                try {
                    EVENT_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return true;
    }

    private static int getSearchSpacingKhz() {
        if (sFmConfig == null) {
            Log.e(TAG, "No active FM configuration");
            return 0;
        }

        switch (sFmConfig.getChSpacing()) {
            case FmReceiver.FM_CHSPACE_200_KHZ:
                return 200;

            case FmReceiver.FM_CHSPACE_100_KHZ:
                return 100;

            case FmReceiver.FM_CHSPACE_50_KHZ:
                return 50;

            default:
                Log.e(TAG, "Unknown FM channel spacing: " +
                        sFmConfig.getChSpacing());
                return 0;
        }
    }

    private static boolean isFrequencyInConfiguredBand(int frequencyKhz) {
        return sFmConfig != null &&
                frequencyKhz >= sFmConfig.getLowerLimit() &&
                frequencyKhz <= sFmConfig.getUpperLimit();
    }

    /*
     * Return a valid starting frequency for Qualcomm's seek/scan engine.
     *
     * The raster is anchored at the configured lower band limit and uses
     * the channel spacing selected in FmConfig.
     *
     * When an off-raster frequency must be normalized, choose the valid
     * raster frequency opposite the requested search direction:
     *
     *   seek up:   100.0 -> 99.9 with the US 200 kHz raster
     *   seek down: 100.0 -> 100.1 with the US 200 kHz raster
     *
     * This prevents the first valid channel in the requested direction
     * from being skipped.
     */
    private static int getSearchRasterFrequency(
            int frequencyKhz, boolean searchUp) {
        if (sFmConfig == null) {
            return frequencyKhz;
        }

        final int lowerKhz = sFmConfig.getLowerLimit();
        final int upperKhz = sFmConfig.getUpperLimit();
        final int spacingKhz = getSearchSpacingKhz();

        if (spacingKhz <= 0) {
            return frequencyKhz;
        }

        int frequency = Math.max(lowerKhz,
                Math.min(upperKhz, frequencyKhz));

        int offset = frequency - lowerKhz;
        int remainder = offset % spacingKhz;

        /*
         * Already on the configured search raster.
         */
        if (remainder == 0) {
            return frequency;
        }

        int rasterFrequency;

        if (searchUp) {
            /*
             * Start immediately below an off-raster frequency so the first
             * valid station above the user's frequency remains searchable.
             */
            rasterFrequency = frequency - remainder;
        } else {
            /*
             * Start immediately above an off-raster frequency so the first
             * valid station below the user's frequency remains searchable.
             */
            rasterFrequency =
                    frequency + (spacingKhz - remainder);
        }

        return Math.max(lowerKhz,
                Math.min(upperKhz, rasterFrequency));
    }

    private static boolean waitForTune() {
        final long deadline =
                SystemClock.elapsedRealtime() + TUNE_TIMEOUT_MS;

        synchronized (EVENT_LOCK) {
            while (!sTuneDone) {
                long remaining =
                        deadline - SystemClock.elapsedRealtime();

                if (remaining <= 0) {
                    Log.e(TAG, "Timed out waiting for FM tune");
                    return false;
                }

                try {
                    EVENT_LOCK.wait(remaining);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean tuneAndWait(int frequencyKhz) {
        if (sReceiver == null) {
            return false;
        }

        synchronized (EVENT_LOCK) {
            sTuneTargetKhz = frequencyKhz;
            sTuneDone = false;
            sTuneWaitInProgress = true;
        }

        Log.d(TAG, "Temporary search-raster tune: " +
                frequencyKhz + " kHz");

        if (!sReceiver.setStation(frequencyKhz)) {
            Log.e(TAG, "Unable to start search-raster tune");

            synchronized (EVENT_LOCK) {
                sTuneWaitInProgress = false;
                sTuneTargetKhz = 0;
            }

            return false;
        }

        boolean completed = waitForTune();

        synchronized (EVENT_LOCK) {
            sTuneWaitInProgress = false;
            sTuneTargetKhz = 0;
        }

        if (!completed) {
            Log.e(TAG, "Search-raster tune did not complete");
        }

        return completed;
    }

    private static boolean createReceiverIfNeeded() {
        if (sReceiver != null) {
            return true;
        }

        if (sContext == null) {
            Log.e(TAG, "No context available to create FmReceiver");
            return false;
        }

        try {
            sReceiver = new FmReceiver(FM_DEVICE_PATH, sCallbacks);
            Log.d(TAG, "Created qcom.fmradio FmReceiver");
            return true;
        } catch (InstantiationException e) {
            Log.e(TAG, "Unable to create qcom.fmradio FmReceiver", e);
            sReceiver = null;
            return false;
        }
    }

    static boolean openDev(Context context) {
        sContext = context.getApplicationContext();

        if (sContext == null) {
            sContext = context;
        }

        return createReceiverIfNeeded();
    }

    static boolean closeDev() {
        Log.d(TAG, "closeDev");

        sReceiver = null;
        sFmConfig = null;
        sCurrentFrequencyKhz = 0;
        sContext = null;

        return true;
    }

    private static FmConfig createConfig() {
        FmConfig config = new FmConfig();

        int region = FmUtils.getFmRegion(sContext);

        if (region == FmUtils.FM_REGION_WORLD_EUROPE) {
            /*
             * General international configuration supported by Revamped's
             * existing 100 kHz station representation.
             *
             * This deliberately does not claim support for regional profiles
             * requiring 50 kHz precision or band limits below 87.5 MHz.
             */
            config.setRadioBand(
                    FmReceiver.FM_USER_DEFINED_BAND);
            config.setEmphasis(
                    FmReceiver.FM_DE_EMP50);
            config.setChSpacing(
                    FmReceiver.FM_CHSPACE_100_KHZ);
            config.setRdsStd(
                    FmReceiver.FM_RDS_STD_RDS);
            config.setLowerLimit(87500);
            config.setUpperLimit(108000);

            Log.i(TAG, "FM region: Europe / International; " +
                    "87500-108000 kHz, 100 kHz, 50 us, RDS");
        } else {
            /*
             * North American configuration.
             */
            config.setRadioBand(
                    FmReceiver.FM_US_BAND);
            config.setEmphasis(
                    FmReceiver.FM_DE_EMP75);
            config.setChSpacing(
                    FmReceiver.FM_CHSPACE_200_KHZ);
            config.setRdsStd(
                    FmReceiver.FM_RDS_STD_RBDS);
            config.setLowerLimit(87500);
            config.setUpperLimit(107900);

            Log.i(TAG, "FM region: North America; " +
                    "87500-107900 kHz, 200 kHz, 75 us, RBDS");
        }

        return config;
    }

    static boolean powerUp(float frequency) {
        if (sContext == null) {
            Log.e(TAG, "powerUp called without context");
            return false;
        }

        if (!createReceiverIfNeeded()) {
            Log.e(TAG, "powerUp: unable to create receiver");
            return false;
        }

        Log.d(TAG, "powerUp: " + frequency);

        synchronized (EVENT_LOCK) {
            sEnableDone = false;
        }

        sFmConfig = createConfig();

        if (!sReceiver.enable(sFmConfig, sContext)) {
            Log.e(TAG, "FmReceiver.enable returned false");
            sFmConfig = null;
            return false;
        }

        if (!waitForEnable()) {
            return false;
        }

        boolean rawRdsMask = sReceiver.setRawRdsGrpMask();
        Log.d(TAG, "setRawRdsGrpMask: " + rawRdsMask);

        /*
         * FM2 places the receiver into normal power mode after enable.
         */
        sReceiver.setPowerMode(FmReceiver.FM_RX_NORMAL_POWER_MODE);

        /*
         * false = external/long antenna. The wired headset is the antenna
         * on Athena.
         */
        sReceiver.setInternalAntenna(false);

        return tune(frequency);
    }

    static boolean powerDown(int type) {
        if (sReceiver == null) {
            return true;
        }

        Log.d(TAG, "powerDown");

        synchronized (EVENT_LOCK) {
            sDisableDone = false;
        }

        if (!sReceiver.disable(sContext)) {
            Log.e(TAG, "FmReceiver.disable returned false");
            return false;
        }

        boolean disabled = waitForDisable();

        if (disabled) {
            /*
             * Qualcomm's Helium backend tears down FM HCI/HAL during disable.
             * The FmReceiver must therefore be reconstructed before the next
             * enable.
             */
            Log.d(TAG, "FM disabled; discarding FmReceiver for re-init");

            sReceiver = null;
            sFmConfig = null;
            sCurrentFrequencyKhz = 0;
        }

        return disabled;
    }

    static boolean tune(float frequency) {
        if (sReceiver == null) {
            return false;
        }

        int frequencyKhz = Math.round(frequency * 1000.0f);

        Log.d(TAG, "tune: " + frequencyKhz + " kHz");

        boolean result = sReceiver.setStation(frequencyKhz);

        if (result) {
            sCurrentFrequencyKhz = frequencyKhz;
        }

        return result;
    }

    static float seek(float frequency, boolean isUp) {
        if (sReceiver == null) {
            return 0.0f;
        }

        int startFrequencyKhz =
                Math.round(frequency * 1000.0f);

        int searchFrequencyKhz =
                getSearchRasterFrequency(startFrequencyKhz, isUp);

        /*
         * Direct tuning can place Qualcomm on a frequency that isn't part of
         * the configured search raster. Move temporarily onto the raster
         * before issuing SEEK.
         */
        if (searchFrequencyKhz != startFrequencyKhz) {
            Log.d(TAG, "seek: normalizing off-raster start " +
                    startFrequencyKhz + " -> " +
                    searchFrequencyKhz + " kHz");

            if (!tuneAndWait(searchFrequencyKhz)) {
                Log.e(TAG, "Unable to prepare tuner for seek");
                return 0.0f;
            }
        }

        /*
         * Reset search state after the optional raster tune so its tune
         * callback remains separate from the actual seek operation.
         */
        synchronized (EVENT_LOCK) {
            sSearchDone = false;
            sSearchFrequencyKhz = 0;
        }

        int direction = isUp
                ? FmReceiver.FM_RX_SEARCHDIR_UP
                : FmReceiver.FM_RX_SEARCHDIR_DOWN;

        Log.d(TAG, "seek: " +
                (isUp ? "up" : "down") +
                " from " + searchFrequencyKhz + " kHz");

        boolean started = sReceiver.searchStations(
                FmReceiver.FM_RX_SRCH_MODE_SEEK,
                FmReceiver.FM_RX_DWELL_PERIOD_1S,
                direction);

        if (!started) {
            Log.e(TAG, "Unable to start seek");
            return 0.0f;
        }

        if (!waitForSearch()) {
            sReceiver.cancelSearch();
            return 0.0f;
        }

        if (sSearchFrequencyKhz <= 0) {
            return 0.0f;
        }

        return sSearchFrequencyKhz / 1000.0f;
    }

    /*
     * Bridge Qualcomm's asynchronous scan callbacks to RevampedFMRadio's
     * synchronous short[] autoScan() interface.
     */
    static short[] autoScan() {
        if (sReceiver == null) {
            Log.e(TAG, "autoScan called without receiver");
            return null;
        }

        int currentFrequencyKhz = sCurrentFrequencyKhz;

        /*
         * This should normally already contain the current tuner frequency.
         * Fall back to the configured lower band limit if it doesn't.
         */
        if (currentFrequencyKhz <= 0) {
            if (sFmConfig == null) {
                Log.e(TAG, "autoScan: no active FM configuration");
                return null;
            }

            currentFrequencyKhz = sFmConfig.getLowerLimit();
        }

        /*
         * Qualcomm SCAN runs upward, so normalize an off-raster starting
         * frequency to the valid raster frequency immediately below it.
         */
        int scanStartFrequencyKhz =
                getSearchRasterFrequency(currentFrequencyKhz, true);

        if (scanStartFrequencyKhz != currentFrequencyKhz) {
            Log.d(TAG, "autoScan: normalizing off-raster start " +
                    currentFrequencyKhz + " -> " +
                    scanStartFrequencyKhz + " kHz");

            if (!tuneAndWait(scanStartFrequencyKhz)) {
                Log.e(TAG, "Unable to prepare tuner for auto scan");
                return null;
            }
        }

        /*
         * Do not mark the scan active until the temporary raster tune is
         * complete. Otherwise FmRxEvRadioTuneStatus for the temporary tune
         * would be mistaken for a discovered station.
         */
        synchronized (EVENT_LOCK) {
            sAutoScanStationsKhz.clear();
            sAutoScanDone = false;
            sAutoScanInProgress = true;
        }

        Log.d(TAG, "autoScan: starting Qualcomm scan from " +
                scanStartFrequencyKhz + " kHz");

        boolean started = sReceiver.searchStations(
                FmReceiver.FM_RX_SRCH_MODE_SCAN,
                FmReceiver.FM_RX_DWELL_PERIOD_2S,
                FmReceiver.FM_RX_SEARCHDIR_UP);

        if (!started) {
            synchronized (EVENT_LOCK) {
                sAutoScanInProgress = false;
                sAutoScanDone = true;
            }

            Log.e(TAG, "Unable to start auto scan");
            return null;
        }

        if (!waitForAutoScan()) {
            sReceiver.cancelSearch();

            synchronized (EVENT_LOCK) {
                sAutoScanInProgress = false;
            }

            return null;
        }

        ArrayList<Integer> stations;

        synchronized (EVENT_LOCK) {
            if (sAutoScanStationsKhz.isEmpty()) {
                Log.d(TAG, "autoScan: no stations found");
                return null;
            }

            stations = new ArrayList<>(sAutoScanStationsKhz);
        }

        Collections.sort(stations);

        short[] result = new short[stations.size()];

        for (int i = 0; i < stations.size(); i++) {
            /*
             * Revamped expects frequencies in 100-kHz units:
             * 88100 kHz -> 881 -> 88.1 MHz.
             */
            result[i] = (short) (stations.get(i) / 100);
        }

        Log.d(TAG, "autoScan: found " + result.length + " stations");

        return result;
    }

    static boolean stopScan() {
        if (sReceiver == null) {
            return false;
        }

        return sReceiver.cancelSearch();
    }

    /*
     * Bridge RevampedFMRadio's polling RDS interface to Qualcomm's
     * callback-driven qcom.fmradio implementation.
     */
    static int setRds(boolean rdson) {
        if (sReceiver == null) {
            return -1;
        }

        if (!rdson) {
            /*
             * Revamped may disable RDS without powering the FM receiver down,
             * such as around a seek or tune. Stop Qualcomm's RDS processing so
             * stale decoder state is not carried across the transition.
             */
            boolean result = sReceiver.unregisterRdsGroupProcessing();

            synchronized (EVENT_LOCK) {
                resetPsStabilizerLocked();
                resetRtStabilizerLocked();

                sRdsEvents = 0;
                sPs = new byte[0];
                sRt = new byte[0];
            }

            Log.d(TAG, "setRds(false): " + result);
            return result ? 0 : -1;
        }

        final int groups =
                FmReceiver.FM_RX_RDS_GRP_RT_EBL |
                FmReceiver.FM_RX_RDS_GRP_PS_EBL |
                FmReceiver.FM_RX_RDS_GRP_AF_EBL |
                FmReceiver.FM_RX_RDS_GRP_PS_SIMPLE_EBL |
                FmReceiver.FM_RX_RDS_GRP_ECC_EBL |
                FmReceiver.FM_RX_RDS_GRP_PTYN_EBL |
                FmReceiver.FM_RX_RDS_GRP_RT_PLUS_EBL;

        boolean result = sReceiver.registerRdsGroupProcessing(groups);

        Log.d(TAG, "setRds(true): " + result);

        return result ? 0 : -1;
    }

    static short readRds() {
        synchronized (EVENT_LOCK) {
            return (short) sRdsEvents;
        }
    }

    static byte[] getPs() {
        synchronized (EVENT_LOCK) {
            byte[] result = sPs.clone();

            /*
             * Match Revamped's original backend: consuming the PS
             * data clears the pending PS event.
             */
            sRdsEvents &= ~RDS_EVT_PS_UPDATE;

            return result;
        }
    }

    static byte[] getLrText() {
        synchronized (EVENT_LOCK) {
            byte[] result = sRt.clone();

            /*
             * Match Revamped's original backend: consuming RadioText
             * clears the pending RT event.
             */
            sRdsEvents &= ~RDS_EVT_RT_UPDATE;

            return result;
        }
    }

    static short activeAf() {
        /*
         * AF-jump bridging is not implemented yet.
         */
        return 0;
    }

    static int setMute(boolean mute) {
        if (sReceiver == null) {
            return -1;
        }

        boolean result = sReceiver.setMuteMode(
                mute
                        ? FmReceiver.FM_RX_MUTE
                        : FmReceiver.FM_RX_UNMUTE);

        return result ? 0 : -1;
    }

    static int isRdsSupport() {
        return 1;
    }

    static int switchAntenna(int antenna) {
        if (sReceiver == null) {
            return 1;
        }

        /*
         * Revamped:
         *   0 = long/external antenna
         *   1 = short/internal antenna
         */
        boolean result = sReceiver.setInternalAntenna(antenna == 1);

        return result ? 0 : 1;
    }

    static boolean setLowPowerMode() {
        return sReceiver != null &&
                sReceiver.setPowerMode(FmReceiver.FM_RX_LOW_POWER_MODE);
    }

    static boolean setNormalPowerMode() {
        return sReceiver != null &&
                sReceiver.setPowerMode(FmReceiver.FM_RX_NORMAL_POWER_MODE);
    }
}
