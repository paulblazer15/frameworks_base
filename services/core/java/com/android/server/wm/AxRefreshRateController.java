/*
 * Copyright (C) 2025-2026 AxionOS
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
package com.android.server.wm;

import android.content.Context;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MotionEvent;

import com.android.internal.annotations.GuardedBy;
import com.android.server.DisplayThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class AxRefreshRateController {

    public interface RefreshRateUpdateCallback {
        void onRefreshRateChanged(float min, float peak, int displayId);
    }

    private enum RefreshRateMode {
        DYNAMIC,
        MINIMUM,
        MAXIMUM,
        FIXED
    }

    private enum Boost {
        TOUCH(1),
        FOCUS(1 << 1),
        FLING(1 << 2),
        ANIMATION(1 << 3);

        final int mask;

        Boost(int mask) {
            this.mask = mask;
        }
    }

    private enum Policy {
        KEYGUARD,
        APP,
        INTERACTIVE,
        MINIMUM,
        MAXIMUM,
        FIXED,
        DYNAMIC_CONTENT
    }

    private static final class ManagedDisplay {
        String focusedPackage = "";
        float appOverrideRate;
        boolean visible = true;
        float lastSyncedPeak = -1f;
        float lastSyncedMin = -1f;
    }

    private static final String TAG = "AxRefreshRateController";

    private static final String SETTINGS_REFRESH_RATE_MODE = "display_refresh_rate_mode";
    private static final String LOCKSCREEN_LIMIT_REFRESH_RATE = "lockscreen_limit_refresh_rate";
    private static final String PER_APP_REFRESH_RATE = "per_app_refresh_rate";

    private static final long DISPLAY_CHANGE_REQUERY_DELAY_MS = 500;
    private static final float RATE_EQUALITY_TOLERANCE_HZ = 0.01f;
    private static final float RATE_MATCH_TOLERANCE_HZ = 1.0f;
    private static final float KEYGUARD_REFRESH_RATE_HZ = 60f;

    private static final AxRefreshRateController sInstance = new AxRefreshRateController();

    private static final boolean sSupportsVrr = SystemProperties.getBoolean(
            "ro.surface_flinger.use_content_detection_for_refresh_rate", false);
    private static final boolean sNeedsHfrFlickerFix = SystemProperties.getBoolean(
            "persist.sys.vrr_needs_hfr_flicker_fix", false);

    private Context mContext;
    private Handler mHandler;
    private WindowManagerService mWmService;

    private volatile RefreshRateUpdateCallback mRateCallback;
    private volatile boolean mInitialized;

    private volatile float mMaxSupportedHz = 60f;
    private volatile float mDefaultMinHz = 60f;
    private volatile float mKeyguardRefreshRateHz = KEYGUARD_REFRESH_RATE_HZ;
    private volatile float[] mSupportedRefreshRates = {KEYGUARD_REFRESH_RATE_HZ};

    private float[] mConfiguredRefreshRates = new float[0];

    private volatile RefreshRateMode mRefreshRateMode = RefreshRateMode.MAXIMUM;
    private volatile int mRefreshRateSetting = 60;
    private volatile boolean mLockscreenLimitEnabled;
    private volatile boolean mKeyguardDone = true;
    private volatile boolean mNotificationShadeExpanded;

    private final AtomicInteger mActiveBoosts = new AtomicInteger();
    private long mIdleTimeoutMs;
    private long mFocusBoostTimeoutMs;
    private volatile long mLastActivityTime;

    @GuardedBy("mWmService.mGlobalLock")
    private boolean mCurrentVoteActive;
    @GuardedBy("mWmService.mGlobalLock")
    private float mCurrentVoteMin;
    @GuardedBy("mWmService.mGlobalLock")
    private float mCurrentVoteMax;

    private final Object mDisplayLock = new Object();
    @GuardedBy("mDisplayLock")
    private final SparseArray<ManagedDisplay> mManagedDisplays = new SparseArray<>();
    @GuardedBy("mDisplayLock")
    private int mActiveDisplayId = Display.DEFAULT_DISPLAY;
    @GuardedBy("mDisplayLock")
    private long mActiveDisplaySelectionUptimeNanos;

    @GuardedBy("mUserAppRefreshRates")
    private final ArrayMap<String, Float> mUserAppRefreshRates = new ArrayMap<>();

    private final Runnable mSyncAndTraversalRunnable = this::syncAndRequestTraversal;
    private final Runnable mIdleTimeoutRunnable = this::syncAndRequestTraversal;
    private final Runnable mForceSyncRunnable = () -> {
        invalidateLastSync();
        syncAndRequestTraversal();
    };
    private final Runnable mDisplayChangeRequeryRunnable = () -> {
        queryAndApplyDisplayModes();
        applyRefreshRateMode(mRefreshRateSetting);
        refreshFocusedAppOverrides();
        invalidateLastSync();
        syncAndRequestTraversal();
    };
    private final Runnable mFlingBoostTimeoutRunnable = () -> {
        if (clearBoost(Boost.FLING)) {
            syncAndRequestTraversal();
        }
    };
    private final Runnable mAnimationBoostTimeoutRunnable = () -> {
        if (clearBoost(Boost.ANIMATION)) {
            syncAndRequestTraversal();
        }
    };

    private AxRefreshRateController() {}

    public static AxRefreshRateController getInstance() {
        return sInstance;
    }

    public synchronized void init(Context context, WindowManagerService wms) {
        if (mInitialized) {
            return;
        }
        mContext = context;
        mWmService = wms;
        mHandler = DisplayThread.getHandler();
        mIdleTimeoutMs = SystemProperties.getLong("persist.sys.ax.idle_timeout_ms", 5000);
        mFocusBoostTimeoutMs = SystemProperties.getLong(
                "persist.sys.ax.focus_boost_timeout_ms", 5000);

        parseCustomRefreshRateList();
        queryAndApplyDisplayModes();
        loadRefreshRateSetting();
        loadPerAppRefreshRates();
        synchronized (mDisplayLock) {
            mManagedDisplays.put(Display.DEFAULT_DISPLAY, new ManagedDisplay());
        }

        mInitialized = true;
        new SettingsObserver();
        forceResync();
        mHandler.postDelayed(mDisplayChangeRequeryRunnable, DISPLAY_CHANGE_REQUERY_DELAY_MS);
    }

    public void setRefreshRateUpdateCallback(RefreshRateUpdateCallback callback) {
        mRateCallback = callback;
        forceResync();
    }

    public void onPointerEvent(int displayId, int action) {
        if (!mInitialized) {
            return;
        }
        final long eventTimeNanos = SystemClock.uptimeNanos();
        final boolean activeDisplayChanged;
        final boolean appOverrideActive;
        synchronized (mDisplayLock) {
            final ManagedDisplay display = mManagedDisplays.get(displayId);
            if (display == null || !display.visible) {
                return;
            }
            if (eventTimeNanos < mActiveDisplaySelectionUptimeNanos
                    && mActiveDisplayId != displayId) {
                return;
            }
            activeDisplayChanged = setActiveDisplayLocked(displayId, eventTimeNanos);
            appOverrideActive = display.appOverrideRate > 0f;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (clearInteractionBoosts() || activeDisplayChanged) {
                postSyncAtFront();
            }
            return;
        }
        if (!isDynamicMode() || (mLockscreenLimitEnabled && !mKeyguardDone)) {
            if (activeDisplayChanged) {
                postSyncAtFront();
            }
            return;
        }
        if (appOverrideActive) {
            if (activeDisplayChanged) {
                postSyncAtFront();
            }
            return;
        }
        clearBoost(Boost.FOCUS);
        if (setBoost(Boost.TOUCH) || activeDisplayChanged) {
            postSyncAtFront();
        }
    }

    public void setFlingBoost(int displayId, long durationMillis) {
        if (!mInitialized || !isDynamicMode()) {
            return;
        }
        postTimedBoostAtFront(
                Boost.FLING, displayId, SystemClock.uptimeNanos(), durationMillis);
    }

    public void setAnimationBoost(long durationMillis) {
        if (!mInitialized || !isDynamicMode()) {
            return;
        }
        postTimedBoostAtFront(
                Boost.ANIMATION, Display.INVALID_DISPLAY, 0L, durationMillis);
    }

    public void clearAnimationBoost() {
        if (!mInitialized || !isDynamicMode()) {
            return;
        }
        mHandler.post(() -> {
            mHandler.removeCallbacks(mAnimationBoostTimeoutRunnable);
            if (clearBoost(Boost.ANIMATION)) {
                syncAndRequestTraversal();
            }
        });
    }

    public void setNotificationShadeExpanded(boolean expanded) {
        if (mNotificationShadeExpanded == expanded) {
            return;
        }
        mNotificationShadeExpanded = expanded;
        if (mInitialized) {
            postSyncAtFront();
        }
    }

    public void setKeyguardDone(boolean done) {
        if (mKeyguardDone == done) {
            return;
        }
        mKeyguardDone = done;
        if (!mInitialized) {
            return;
        }
        if (!done) {
            clearAllBoosts();
            setActiveDisplay(Display.DEFAULT_DISPLAY, SystemClock.uptimeNanos());
        } else {
            boostActiveDisplayIfNeeded();
        }
        postSyncAtFront();
    }

    public void onUserSwitched() {
        if (!mInitialized) {
            return;
        }
        mHandler.post(() -> {
            loadRefreshRateSetting();
            loadPerAppRefreshRates();
            refreshFocusedAppOverrides();
            boostActiveDisplayIfNeeded();
            invalidateLastSync();
            syncAndRequestTraversal();
        });
    }

    public void onDisplayAdded(int displayId) {
        if (!mInitialized || displayId == Display.DEFAULT_DISPLAY) {
            return;
        }
        mHandler.post(() -> {
            if (!mWmService.mDisplayManagerInternal.isFreeformDisplayId(displayId)) {
                return;
            }
            synchronized (mDisplayLock) {
                if (mManagedDisplays.contains(displayId)) {
                    return;
                }
            }
            final String focusedPackage;
            final boolean visible;
            synchronized (mWmService.mGlobalLock) {
                final DisplayContent displayContent = mWmService.mRoot.getDisplayContent(displayId);
                if (displayContent == null) {
                    return;
                }
                focusedPackage = displayContent.mFocusedApp == null
                        || displayContent.mFocusedApp.packageName == null
                        ? "" : displayContent.mFocusedApp.packageName;
                visible = !Display.isOffState(displayContent.getDisplayInfo().state);
                synchronized (mDisplayLock) {
                    if (mManagedDisplays.contains(displayId)) {
                        return;
                    }
                    final ManagedDisplay display = new ManagedDisplay();
                    display.focusedPackage = focusedPackage;
                    display.visible = visible;
                    updateFocusedAppOverrideLocked(display);
                    mManagedDisplays.put(displayId, display);
                    if (visible && !focusedPackage.isEmpty()) {
                        setActiveDisplayLocked(displayId, SystemClock.uptimeNanos());
                    }
                    if (isDynamicMode() && mKeyguardDone
                            && mActiveDisplayId == displayId && display.appOverrideRate <= 0f) {
                        setBoost(Boost.FOCUS);
                    }
                }
            }
            syncAndRequestTraversal();
        });
    }

    public void onDisplayRemoved(int displayId) {
        if (!mInitialized || displayId == Display.DEFAULT_DISPLAY) {
            return;
        }
        final boolean activeDisplayChanged;
        synchronized (mDisplayLock) {
            mManagedDisplays.remove(displayId);
            activeDisplayChanged = mActiveDisplayId == displayId;
            if (activeDisplayChanged) {
                mActiveDisplayId = Display.DEFAULT_DISPLAY;
                mActiveDisplaySelectionUptimeNanos = SystemClock.uptimeNanos();
            }
        }
        if (activeDisplayChanged) {
            clearInteractionBoosts();
            mHandler.post(mSyncAndTraversalRunnable);
        }
    }

    public void onFreeformDisplayVisibilityChanged(int displayId, boolean visible) {
        if (!mInitialized) {
            return;
        }
        final boolean activeDisplayChanged;
        synchronized (mDisplayLock) {
            final ManagedDisplay display = mManagedDisplays.get(displayId);
            if (display == null) {
                return;
            }
            display.visible = visible;
            if (visible) {
                activeDisplayChanged = setActiveDisplayLocked(
                        displayId, SystemClock.uptimeNanos());
            } else if (mActiveDisplayId == displayId) {
                activeDisplayChanged = setActiveDisplayLocked(
                        Display.DEFAULT_DISPLAY, SystemClock.uptimeNanos());
            } else {
                return;
            }
        }
        if (activeDisplayChanged) {
            if (!visible) {
                clearInteractionBoosts();
            }
            postSyncAtFront();
        }
    }

    public void onAllFreeformDisplaysHidden() {
        if (!mInitialized) {
            return;
        }
        final boolean activeDisplayChanged;
        synchronized (mDisplayLock) {
            for (int i = 0; i < mManagedDisplays.size(); i++) {
                if (mManagedDisplays.keyAt(i) != Display.DEFAULT_DISPLAY) {
                    mManagedDisplays.valueAt(i).visible = false;
                }
            }
            activeDisplayChanged = setActiveDisplayLocked(
                    Display.DEFAULT_DISPLAY, SystemClock.uptimeNanos());
        }
        if (activeDisplayChanged) {
            clearInteractionBoosts();
            postSyncAtFront();
        }
    }

    public void updateFocusedApp(int displayId, ActivityRecord activityRecord) {
        if (!mInitialized) {
            return;
        }
        final String packageName = activityRecord == null || activityRecord.packageName == null
                ? "" : activityRecord.packageName;
        final long focusChangeTimeNanos = SystemClock.uptimeNanos();
        synchronized (mDisplayLock) {
            final ManagedDisplay display = mManagedDisplays.get(displayId);
            if (display == null) {
                return;
            }
            if (!packageName.isEmpty() && display.visible) {
                setActiveDisplayLocked(displayId, focusChangeTimeNanos);
            }
            display.focusedPackage = packageName;
            updateFocusedAppOverrideLocked(display);
            if (isDynamicMode() && !packageName.isEmpty() && mActiveDisplayId == displayId
                    && display.appOverrideRate <= 0f && mKeyguardDone) {
                setBoost(Boost.FOCUS);
            }
        }
        postSyncAtFront();
    }

    public void onDisplayChanged(int displayId) {
        if (!mInitialized || !isDisplayManaged(displayId)) {
            return;
        }
        if (displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        mHandler.post(() -> {
            queryAndApplyDisplayModes();
            applyRefreshRateMode(mRefreshRateSetting);
            refreshFocusedAppOverrides();
            invalidateLastSync();
            mHandler.removeCallbacks(mDisplayChangeRequeryRunnable);
            syncDisplaySettings();
            mHandler.postDelayed(mDisplayChangeRequeryRunnable,
                    DISPLAY_CHANGE_REQUERY_DELAY_MS);
        });
    }

    public void forceResync() {
        if (mInitialized) {
            mHandler.post(mForceSyncRunnable);
        }
    }

    @GuardedBy("mWmService.mGlobalLock")
    public void updateVoteResult(int displayId) {
        clearCurrentVote();
        if (!mInitialized || !isDisplayManaged(displayId)) {
            return;
        }
        final boolean boosted = isDynamicMode() && hasEffectiveBoost();
        synchronized (mDisplayLock) {
            final int policyDisplayId = getPolicyDisplayIdLocked(displayId);
            final ManagedDisplay policyDisplay = mManagedDisplays.get(policyDisplayId);
            if (policyDisplay == null) {
                return;
            }
            final Policy policy = resolvePolicyLocked(displayId, policyDisplay,
                    boosted && policyDisplayId == mActiveDisplayId);
            switch (policy) {
                case DYNAMIC_CONTENT:
                    return;
                case KEYGUARD:
                case APP:
                case INTERACTIVE:
                case MINIMUM:
                case MAXIMUM:
                case FIXED:
                    final float rate = resolvePolicyRateLocked(policy, policyDisplay);
                    setCurrentVote(rate, rate);
                    return;
                default:
                    throw new IllegalStateException("Unknown refresh rate policy " + policy);
            }
        }
    }

    private boolean isDisplayManaged(int displayId) {
        if (!mInitialized) {
            return false;
        }
        synchronized (mDisplayLock) {
            return mManagedDisplays.contains(displayId);
        }
    }

    @GuardedBy("mWmService.mGlobalLock")
    public boolean hasActiveVote() {
        return mCurrentVoteActive;
    }

    @GuardedBy("mWmService.mGlobalLock")
    public float getMaxPreferredRate() {
        return mCurrentVoteMax;
    }

    @GuardedBy("mWmService.mGlobalLock")
    public float getMinPreferredRate() {
        return mCurrentVoteMin;
    }

    private void postTimedBoostAtFront(Boost boost, int displayId, long selectionTimeNanos,
            long durationMillis) {
        if (Looper.myLooper() == mHandler.getLooper()) {
            setTimedBoost(boost, displayId, selectionTimeNanos, durationMillis);
            return;
        }
        final Message message = Message.obtain(mHandler,
                () -> setTimedBoost(boost, displayId, selectionTimeNanos, durationMillis));
        message.setAsynchronous(true);
        mHandler.sendMessageAtFrontOfQueue(message);
    }

    private void postSyncAtFront() {
        if (Looper.myLooper() == mHandler.getLooper()) {
            syncAndRequestTraversal();
            return;
        }
        final Message message = Message.obtain(mHandler, mSyncAndTraversalRunnable);
        message.setAsynchronous(true);
        mHandler.sendMessageAtFrontOfQueue(message);
    }

    private void setTimedBoost(Boost boost, int displayId, long selectionTimeNanos,
            long durationMillis) {
        if (!isDynamicMode()) {
            return;
        }
        final int targetDisplayId;
        boolean interactionBoostsCleared = false;
        final Runnable timeoutRunnable;
        switch (boost) {
            case FLING:
                if (!setActiveDisplay(displayId, selectionTimeNanos)) {
                    return;
                }
                targetDisplayId = displayId;
                timeoutRunnable = mFlingBoostTimeoutRunnable;
                interactionBoostsCleared = clearInteractionBoosts();
                break;
            case ANIMATION:
                synchronized (mDisplayLock) {
                    targetDisplayId = mActiveDisplayId;
                }
                timeoutRunnable = mAnimationBoostTimeoutRunnable;
                break;
            case TOUCH:
            case FOCUS:
            default:
                throw new IllegalArgumentException("Timed boost is not supported for " + boost);
        }
        mHandler.removeCallbacks(timeoutRunnable);
        if ((mLockscreenLimitEnabled && !mKeyguardDone) || durationMillis <= 0) {
            if (clearBoost(boost) || interactionBoostsCleared) {
                syncAndRequestTraversal();
            }
            return;
        }

        if (hasAppOverride(targetDisplayId)) {
            clearBoost(boost);
            syncAndRequestTraversal();
            return;
        }
        setBoost(boost);
        syncAndRequestTraversal();
        mHandler.postDelayed(timeoutRunnable, durationMillis);
    }

    private void boostActiveDisplayIfNeeded() {
        if (!isDynamicMode() || !mKeyguardDone) {
            return;
        }
        synchronized (mDisplayLock) {
            final ManagedDisplay display = mManagedDisplays.get(mActiveDisplayId);
            if (display == null || display.appOverrideRate > 0f) {
                return;
            }
            setBoost(Boost.FOCUS);
        }
    }

    private boolean setBoost(Boost boost) {
        switch (boost) {
            case TOUCH:
            case FOCUS:
                mLastActivityTime = SystemClock.uptimeMillis();
                break;
            case FLING:
            case ANIMATION:
                break;
            default:
                throw new IllegalStateException("Unknown refresh rate boost " + boost);
        }
        int activeBoosts;
        do {
            activeBoosts = mActiveBoosts.get();
            if ((activeBoosts & boost.mask) != 0) {
                return false;
            }
        } while (!mActiveBoosts.compareAndSet(activeBoosts, activeBoosts | boost.mask));
        return true;
    }

    private boolean clearBoost(Boost boost) {
        int activeBoosts;
        int newBoosts;
        do {
            activeBoosts = mActiveBoosts.get();
            if ((activeBoosts & boost.mask) == 0) {
                return false;
            }
            newBoosts = activeBoosts & ~boost.mask;
        } while (!mActiveBoosts.compareAndSet(activeBoosts, newBoosts));
        return true;
    }

    private boolean clearInteractionBoosts() {
        final boolean touchCleared = clearBoost(Boost.TOUCH);
        return clearBoost(Boost.FOCUS) || touchCleared;
    }

    private void clearAllBoosts() {
        if (mHandler != null) {
            mHandler.removeCallbacks(mFlingBoostTimeoutRunnable);
            mHandler.removeCallbacks(mAnimationBoostTimeoutRunnable);
            mHandler.removeCallbacks(mSyncAndTraversalRunnable);
            mHandler.removeCallbacks(mIdleTimeoutRunnable);
        }
        mActiveBoosts.set(0);
    }

    private boolean hasBoost(Boost boost) {
        return (mActiveBoosts.get() & boost.mask) != 0;
    }

    private boolean hasEffectiveBoost() {
        while (true) {
            final int activeBoosts = mActiveBoosts.get();
            if (activeBoosts == 0) {
                return false;
            }
            if ((activeBoosts & (Boost.FLING.mask | Boost.ANIMATION.mask)) != 0) {
                return true;
            }
            final long timeout = (activeBoosts & Boost.FOCUS.mask) != 0
                    ? mFocusBoostTimeoutMs : mIdleTimeoutMs;
            if (SystemClock.uptimeMillis() - mLastActivityTime < timeout) {
                return true;
            }
            final int remainingBoosts = activeBoosts
                    & ~(Boost.TOUCH.mask | Boost.FOCUS.mask);
            if (mActiveBoosts.compareAndSet(activeBoosts, remainingBoosts)) {
                return remainingBoosts != 0;
            }
        }
    }

    private float resolveUserRefreshRate(Float rate) {
        if (rate == null || !Float.isFinite(rate) || rate <= 0f) {
            return 0f;
        }
        return findSupportedRefreshRate(rate, RATE_MATCH_TOLERANCE_HZ);
    }

    private void invalidateLastSync() {
        synchronized (mDisplayLock) {
            for (int i = 0; i < mManagedDisplays.size(); i++) {
                invalidateLastSyncLocked(mManagedDisplays.valueAt(i));
            }
        }
    }

    @GuardedBy("mDisplayLock")
    private void invalidateLastSyncLocked(ManagedDisplay display) {
        display.lastSyncedPeak = -1f;
        display.lastSyncedMin = -1f;
    }

    @GuardedBy("mWmService.mGlobalLock")
    private void setCurrentVote(float min, float max) {
        mCurrentVoteActive = true;
        mCurrentVoteMin = min;
        mCurrentVoteMax = max;
    }

    @GuardedBy("mWmService.mGlobalLock")
    private void clearCurrentVote() {
        mCurrentVoteActive = false;
        mCurrentVoteMin = 0f;
        mCurrentVoteMax = 0f;
    }

    private void parseCustomRefreshRateList() {
        final String customList = SystemProperties.get(
                "persist.sys.display_refresh_rates_list", "");
        if (customList.isEmpty()) {
            return;
        }
        final List<Float> parsedRates = new ArrayList<>();
        for (String rateString : customList.split(",")) {
            try {
                final float rate = Float.parseFloat(rateString.trim());
                if (!Float.isFinite(rate) || rate <= 0f) {
                    continue;
                }
                addDistinctRate(parsedRates, rate);
            } catch (NumberFormatException e) {
                Slog.e(TAG, "Failed to parse refresh rate: " + rateString, e);
            }
        }
        Collections.sort(parsedRates);
        mConfiguredRefreshRates = toFloatArray(parsedRates);
    }

    private void queryAndApplyDisplayModes() {
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        if (displayManager == null) {
            return;
        }
        final Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return;
        }

        final DisplayInfo displayInfo = new DisplayInfo();
        if (!display.getDisplayInfo(displayInfo)) {
            return;
        }
        final Display.Mode currentMode = displayInfo.getMode();
        if (currentMode == null) {
            return;
        }
        final List<Float> availableRates = new ArrayList<>();
        for (float rate : displayInfo.getDefaultRefreshRates()) {
            if (Float.isFinite(rate) && rate > 0f) {
                addDistinctRate(availableRates, rate);
            }
        }
        for (Display.Mode mode : displayInfo.appsSupportedModes) {
            if (mode.getPhysicalWidth() != currentMode.getPhysicalWidth()
                    || mode.getPhysicalHeight() != currentMode.getPhysicalHeight()) {
                continue;
            }
            final float rate = mode.getRefreshRate();
            if (Float.isFinite(rate) && rate > 0f) {
                addDistinctRate(availableRates, rate);
            }
        }
        if (availableRates.isEmpty()) {
            return;
        }
        Collections.sort(availableRates);

        final float[] actualRates = toFloatArray(availableRates);
        final List<Float> supportedRates = new ArrayList<>();
        if (mConfiguredRefreshRates.length > 0) {
            for (float configuredRate : mConfiguredRefreshRates) {
                final float matchedRate = findClosestRefreshRate(
                        actualRates, configuredRate, RATE_MATCH_TOLERANCE_HZ);
                if (matchedRate > 0f) {
                    addDistinctRate(supportedRates, matchedRate);
                }
            }
        }
        if (supportedRates.isEmpty()) {
            supportedRates.addAll(availableRates);
        } else {
            Collections.sort(supportedRates);
        }

        final float[] rates = toFloatArray(supportedRates);
        final float keyguardRefreshRate = findKeyguardRefreshRate(actualRates);
        synchronized (mDisplayLock) {
            mSupportedRefreshRates = rates;
            mDefaultMinHz = rates[0];
            mMaxSupportedHz = rates[rates.length - 1];
            mKeyguardRefreshRateHz = keyguardRefreshRate;
        }
    }

    @GuardedBy("mDisplayLock")
    private Policy resolvePolicyLocked(int displayId, ManagedDisplay display, boolean boosted) {
        if (!mKeyguardDone && mLockscreenLimitEnabled) {
            return Policy.KEYGUARD;
        }
        if (!mKeyguardDone && sNeedsHfrFlickerFix && !(isDynamicMode() && boosted)) {
            return Policy.KEYGUARD;
        }
        if (displayId == Display.DEFAULT_DISPLAY && isDynamicMode()
                && mNotificationShadeExpanded) {
            return Policy.MAXIMUM;
        }
        if (!mKeyguardDone) {
            return resolveModePolicy(boosted);
        }
        if (display.appOverrideRate > 0f) {
            return Policy.APP;
        }
        return resolveModePolicy(boosted);
    }

    private Policy resolveModePolicy(boolean boosted) {
        switch (mRefreshRateMode) {
            case DYNAMIC:
                if (!boosted) {
                    return Policy.DYNAMIC_CONTENT;
                }
                return Policy.INTERACTIVE;
            case MINIMUM:
                return Policy.MINIMUM;
            case MAXIMUM:
                return Policy.MAXIMUM;
            case FIXED:
                return Policy.FIXED;
        }
        throw new IllegalStateException("Unknown refresh rate mode " + mRefreshRateMode);
    }

    @GuardedBy("mDisplayLock")
    private float resolvePolicyRateLocked(Policy policy, ManagedDisplay display) {
        switch (policy) {
            case KEYGUARD:
                return mKeyguardRefreshRateHz;
            case INTERACTIVE:
            case MAXIMUM:
                return mMaxSupportedHz;
            case APP:
                return display.appOverrideRate;
            case MINIMUM:
                return mDefaultMinHz;
            case FIXED:
                return resolveSelectedRefreshRate();
            case DYNAMIC_CONTENT:
                break;
        }
        throw new IllegalStateException("Policy does not lock a refresh rate: " + policy);
    }

    private void syncDisplaySettings() {
        final boolean boosted = isDynamicMode() && hasEffectiveBoost();
        final int[] displayIds;
        synchronized (mDisplayLock) {
            displayIds = new int[mManagedDisplays.size()];
            for (int i = 0; i < displayIds.length; i++) {
                displayIds[i] = mManagedDisplays.keyAt(i);
            }
        }

        boolean boostAffectsVote = false;
        for (int displayId : displayIds) {
            boostAffectsVote |= syncDisplaySetting(displayId, boosted);
        }
        if (boostAffectsVote && !hasBoost(Boost.FLING) && !hasBoost(Boost.ANIMATION)) {
            final long elapsed = SystemClock.uptimeMillis() - mLastActivityTime;
            final long timeout = hasBoost(Boost.FOCUS)
                    ? mFocusBoostTimeoutMs : mIdleTimeoutMs;
            mHandler.removeCallbacks(mIdleTimeoutRunnable);
            mHandler.postDelayed(mIdleTimeoutRunnable, Math.max(1, timeout - elapsed));
        } else {
            mHandler.removeCallbacks(mIdleTimeoutRunnable);
        }
    }

    private boolean syncDisplaySetting(int displayId, boolean boosted) {
        final RefreshRateUpdateCallback callback = mRateCallback;
        final float min;
        final float peak;
        final Policy policy;
        final boolean changed;
        synchronized (mDisplayLock) {
            final ManagedDisplay outputDisplay = mManagedDisplays.get(displayId);
            final int policyDisplayId = getPolicyDisplayIdLocked(displayId);
            final ManagedDisplay policyDisplay = mManagedDisplays.get(policyDisplayId);
            if (outputDisplay == null || policyDisplay == null) {
                return false;
            }
            policy = resolvePolicyLocked(displayId, policyDisplay,
                    boosted && policyDisplayId == mActiveDisplayId);
            if (policy == Policy.DYNAMIC_CONTENT) {
                min = 0f;
                peak = mMaxSupportedHz;
            } else {
                final float rate = resolvePolicyRateLocked(policy, policyDisplay);
                min = rate;
                peak = rate;
            }
            changed = peak != outputDisplay.lastSyncedPeak || min != outputDisplay.lastSyncedMin;
            if (callback != null && changed) {
                outputDisplay.lastSyncedPeak = peak;
                outputDisplay.lastSyncedMin = min;
            }
        }
        if (callback != null && changed) {
            callback.onRefreshRateChanged(min, peak, displayId);
        }
        return usesTimedBoost(policy);
    }

    private static boolean usesTimedBoost(Policy policy) {
        switch (policy) {
            case INTERACTIVE:
                return true;
            case KEYGUARD:
            case APP:
            case MINIMUM:
            case MAXIMUM:
            case FIXED:
            case DYNAMIC_CONTENT:
                return false;
        }
        throw new IllegalStateException("Unknown refresh rate policy " + policy);
    }

    private void syncAndRequestTraversal() {
        if (!isDynamicMode() && mActiveBoosts.get() != 0) {
            clearAllBoosts();
        }
        syncDisplaySettings();
        requestTraversal();
    }

    private void loadRefreshRateSetting() {
        final int value = Settings.Global.getInt(mContext.getContentResolver(),
                SETTINGS_REFRESH_RATE_MODE, sSupportsVrr ? 0 : Math.round(mMaxSupportedHz));
        applyRefreshRateMode(value);
        mLockscreenLimitEnabled = Settings.System.getIntForUser(mContext.getContentResolver(),
                LOCKSCREEN_LIMIT_REFRESH_RATE, 0, UserHandle.USER_CURRENT) != 0;
        if (mLockscreenLimitEnabled && !mKeyguardDone) {
            clearAllBoosts();
        }
    }

    private void applyRefreshRateMode(int value) {
        final RefreshRateMode oldMode = mRefreshRateMode;
        mRefreshRateSetting = value;
        if (value == 0 && sSupportsVrr) {
            mRefreshRateMode = RefreshRateMode.DYNAMIC;
        } else {
            final float rate = resolveSelectedRefreshRate();
            if (Math.abs(rate - mDefaultMinHz) <= RATE_EQUALITY_TOLERANCE_HZ) {
                mRefreshRateMode = RefreshRateMode.MINIMUM;
            } else if (Math.abs(rate - mMaxSupportedHz) <= RATE_EQUALITY_TOLERANCE_HZ) {
                mRefreshRateMode = RefreshRateMode.MAXIMUM;
            } else {
                mRefreshRateMode = RefreshRateMode.FIXED;
            }
        }
        if (oldMode == RefreshRateMode.DYNAMIC
                && mRefreshRateMode != RefreshRateMode.DYNAMIC) {
            clearAllBoosts();
        }
    }

    private boolean isDynamicMode() {
        return mRefreshRateMode == RefreshRateMode.DYNAMIC;
    }

    private float resolveSelectedRefreshRate() {
        if (mRefreshRateSetting <= 0) {
            return mMaxSupportedHz;
        }
        final float rate = findSupportedRefreshRate(
                mRefreshRateSetting, RATE_MATCH_TOLERANCE_HZ);
        return rate > 0f ? rate : mMaxSupportedHz;
    }

    private void loadPerAppRefreshRates() {
        final String config = Settings.System.getStringForUser(mContext.getContentResolver(),
                PER_APP_REFRESH_RATE, UserHandle.USER_CURRENT);
        final ArrayMap<String, Float> parsed = new ArrayMap<>();
        if (config != null && !config.isEmpty()) {
            for (String app : config.split(",")) {
                final String[] parts = app.split(":", 2);
                if (parts.length != 2 || parts[0].isEmpty()) {
                    continue;
                }
                try {
                    final float rate = Float.parseFloat(parts[1]);
                    if (Float.isFinite(rate) && rate > 0f) {
                        parsed.put(parts[0], rate);
                    }
                } catch (NumberFormatException e) {
                    Slog.e(TAG, "Failed to parse refresh rate for app: " + app, e);
                }
            }
        }
        synchronized (mUserAppRefreshRates) {
            mUserAppRefreshRates.clear();
            mUserAppRefreshRates.putAll(parsed);
        }
    }

    private void refreshFocusedAppOverrides() {
        synchronized (mDisplayLock) {
            for (int i = 0; i < mManagedDisplays.size(); i++) {
                updateFocusedAppOverrideLocked(mManagedDisplays.valueAt(i));
            }
        }
    }

    @GuardedBy("mDisplayLock")
    private void updateFocusedAppOverrideLocked(ManagedDisplay display) {
        if (display.focusedPackage.isEmpty()) {
            display.appOverrideRate = 0f;
            return;
        }
        final Float userRate;
        synchronized (mUserAppRefreshRates) {
            userRate = mUserAppRefreshRates.get(display.focusedPackage);
        }
        display.appOverrideRate = resolveUserRefreshRate(userRate);
    }

    private boolean hasAppOverride(int displayId) {
        synchronized (mDisplayLock) {
            final ManagedDisplay display = mManagedDisplays.get(displayId);
            return display != null && display.appOverrideRate > 0f;
        }
    }

    private boolean setActiveDisplay(int displayId, long selectionTimeNanos) {
        synchronized (mDisplayLock) {
            final ManagedDisplay display = mManagedDisplays.get(displayId);
            if (display == null || !display.visible) {
                return false;
            }
            if (selectionTimeNanos < mActiveDisplaySelectionUptimeNanos) {
                return mActiveDisplayId == displayId;
            }
            setActiveDisplayLocked(displayId, selectionTimeNanos);
            return true;
        }
    }

    @GuardedBy("mDisplayLock")
    private boolean setActiveDisplayLocked(int displayId, long selectionUptimeNanos) {
        if (selectionUptimeNanos < mActiveDisplaySelectionUptimeNanos) {
            return false;
        }
        mActiveDisplaySelectionUptimeNanos = selectionUptimeNanos;
        if (mActiveDisplayId == displayId) {
            return false;
        }
        mActiveDisplayId = displayId;
        return true;
    }

    @GuardedBy("mDisplayLock")
    private int getPolicyDisplayIdLocked(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY && mKeyguardDone
                && !(isDynamicMode() && mNotificationShadeExpanded)
                && mActiveDisplayId != Display.DEFAULT_DISPLAY
                && mManagedDisplays.contains(mActiveDisplayId)
                && mManagedDisplays.get(mActiveDisplayId).visible) {
            return mActiveDisplayId;
        }
        return displayId;
    }

    private float findSupportedRefreshRate(float requestedRate, float tolerance) {
        return findClosestRefreshRate(mSupportedRefreshRates, requestedRate, tolerance);
    }

    private static float findClosestRefreshRate(float[] rates, float requestedRate,
            float tolerance) {
        float closestRate = 0f;
        float closestDistance = Float.POSITIVE_INFINITY;
        for (float rate : rates) {
            final float distance = Math.abs(rate - requestedRate);
            if (distance < closestDistance) {
                closestRate = rate;
                closestDistance = distance;
            }
        }
        return closestDistance <= tolerance ? closestRate : 0f;
    }

    private static float findKeyguardRefreshRate(float[] rates) {
        final float matchedRate = findClosestRefreshRate(
                rates, KEYGUARD_REFRESH_RATE_HZ, RATE_MATCH_TOLERANCE_HZ);
        if (matchedRate > 0f) {
            return matchedRate;
        }
        for (float rate : rates) {
            if (rate >= KEYGUARD_REFRESH_RATE_HZ) {
                return rate;
            }
        }
        return rates[rates.length - 1];
    }

    private static void addDistinctRate(List<Float> rates, float rate) {
        for (float existingRate : rates) {
            if (Math.abs(existingRate - rate) <= RATE_EQUALITY_TOLERANCE_HZ) {
                return;
            }
        }
        rates.add(rate);
    }

    private static float[] toFloatArray(List<Float> rates) {
        final float[] result = new float[rates.size()];
        for (int i = 0; i < rates.size(); i++) {
            result[i] = rates.get(i);
        }
        return result;
    }

    private void requestTraversal() {
        synchronized (mWmService.mGlobalLock) {
            mWmService.requestTraversal();
        }
    }

    private final class SettingsObserver extends ContentObserver {
        private final Uri mRefreshRateModeUri =
                Settings.Global.getUriFor(SETTINGS_REFRESH_RATE_MODE);
        private final Uri mLockscreenLimitUri =
                Settings.System.getUriFor(LOCKSCREEN_LIMIT_REFRESH_RATE);
        private final Uri mPerAppRefreshRateUri = Settings.System.getUriFor(PER_APP_REFRESH_RATE);

        SettingsObserver() {
            super(mHandler);
            mContext.getContentResolver().registerContentObserver(
                    mRefreshRateModeUri, false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    mLockscreenLimitUri, false, this, UserHandle.USER_ALL);
            mContext.getContentResolver().registerContentObserver(
                    mPerAppRefreshRateUri, false, this, UserHandle.USER_ALL);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (mPerAppRefreshRateUri.equals(uri)) {
                loadPerAppRefreshRates();
                refreshFocusedAppOverrides();
                boostActiveDisplayIfNeeded();
                syncAndRequestTraversal();
                return;
            }
            final boolean wasDynamic = isDynamicMode();
            loadRefreshRateSetting();
            if (!wasDynamic) {
                boostActiveDisplayIfNeeded();
            }
            syncAndRequestTraversal();
        }
    }
}
