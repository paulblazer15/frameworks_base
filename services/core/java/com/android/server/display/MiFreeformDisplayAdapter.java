/*
 * Copyright (C) 2025 AxionOS
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
package com.android.server.display;

import static com.android.server.display.DisplayModeFactory.createMode;

import android.app.IFreeformDisplayCallback;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.mode.DisplayModeDirector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MiFreeformDisplayAdapter extends DisplayAdapter {
    private static final String TAG = "MiFreeformDisplayAdapter";

    public static final String UNIQUE_ID_PREFIX = "axion-freeform:";

    private final ArrayMap<IBinder, FreeformDisplayDevice> mFreeformDisplayDevices = new ArrayMap<>();
    private final LogicalDisplayMapper mLogicalDisplayMapper;
    private float[] mDefaultDisplayRefreshRates = new float[0];

    public MiFreeformDisplayAdapter(DisplayManagerService.SyncRoot syncRoot,
            Context context, Handler handler, Listener listener, 
            LogicalDisplayMapper logicalDisplayMapper, DisplayManagerFlags flags) {
        super(syncRoot, context, handler, listener, TAG, flags);
        mLogicalDisplayMapper = logicalDisplayMapper;
    }
    
    public void createFreeformLocked(String name, IFreeformDisplayCallback callback,
            int width, int height, int densityDpi, boolean secure,
            boolean ownContentOnly, boolean shouldShowSystemDecorations, Surface surface,
            float refreshRate, long presentationDeadlineNanos) {
        
        IBinder appToken = callback.asBinder();
        Slog.i(TAG, "createFreeformLocked: token=" + appToken + 
               ", ownContentOnly=" + ownContentOnly +
               ", shouldShowSystemDecorations=" + shouldShowSystemDecorations);
        
        if (mFreeformDisplayDevices.containsKey(appToken)) {
            Slog.w(TAG, "Display already exists for this token");
            return;
        }
        
        final String uniqueId = UNIQUE_ID_PREFIX + name;
        IBinder displayToken = DisplayControl.createVirtualDisplay(name, true);
        FreeformDisplayDevice device = new FreeformDisplayDevice(
                displayToken, uniqueId, width, height, densityDpi, refreshRate,
                getDefaultDisplayRefreshRatesLocked(refreshRate), presentationDeadlineNanos,
                new FreeformFlags(true, true /* ownContentOnly */, false /* shouldShowSystemDecorations */),
                surface, new Callback(callback, getHandler()), appToken);

        mFreeformDisplayDevices.put(appToken, device);
        
        sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_ADDED);
        
        try {
            appToken.linkToDeath(device, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Failed to link death recipient", e);
            mFreeformDisplayDevices.remove(appToken);
            device.destroyLocked(false);
            return;
        }
        
        getHandler().postDelayed(() -> {
            synchronized (getSyncRoot()) {
                LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
                Slog.i(TAG, "findLogicalDisplayForDevice: " + display);
                try {
                    if (display != null) {
                        device.notifyDisplayReady(display.getDisplayIdLocked());
                    } else {
                        Slog.e(TAG, "Failed to find logical display for device");
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Error notifying display added", e);
                }
            }
        }, 500);
    }
    
    public void resizeFreeform(IBinder appToken, int width, int height, int densityDpi) {
        synchronized (getSyncRoot()) {
            FreeformDisplayDevice device = mFreeformDisplayDevices.get(appToken);
            if (device != null) {
                device.resizeLocked(width, height, densityDpi);
                Slog.i(TAG, "Resized freeform display: " + width + "x" + height + " @ " + densityDpi + "dpi");
            } else {
                Slog.w(TAG, "resizeFreeform: Device not found for token " + appToken);
            }
        }
    }
    
    public void releaseFreeform(IBinder appToken) {
        synchronized (getSyncRoot()) {
            Slog.i(TAG, "releaseFreeform: token=" + appToken + 
                   ", devices in map: " + mFreeformDisplayDevices.size());
            FreeformDisplayDevice device = mFreeformDisplayDevices.remove(appToken);
            if (device != null) {
                device.destroyLocked(true);
                appToken.unlinkToDeath(device, 0);
                sendDisplayDeviceEventLocked(device, DISPLAY_DEVICE_EVENT_REMOVED);
                Slog.i(TAG, "Released freeform display for token " + appToken);
            } else {
                Slog.w(TAG, "releaseFreeform: Device not found for token " + appToken +
                       ". Available tokens: " + mFreeformDisplayDevices.keySet());
            }
        }
    }
    
    private void handleBinderDiedLocked(IBinder appToken) {
        FreeformDisplayDevice device = mFreeformDisplayDevices.remove(appToken);
        if (device != null) {
            Slog.w(TAG, "Client died, auto-releasing freeform display");
        }
    }
    
    public List<Integer> getAllFreeformDisplayIdsLocked() {
        List<Integer> displayIds = new ArrayList<>();
        for (FreeformDisplayDevice device : mFreeformDisplayDevices.values()) {
            LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
            if (display != null) {
                displayIds.add(display.getDisplayIdLocked());
            }
        }
        return displayIds;
    }
    
    public int getDisplayIdForToken(IBinder appToken) {
        synchronized (getSyncRoot()) {
            FreeformDisplayDevice device = mFreeformDisplayDevices.get(appToken);
            if (device != null) {
                LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
                if (display != null) {
                    return display.getDisplayIdLocked();
                }
            }
            return -1;
        }
    }
    
    public boolean isFreeformDisplayIdLocked(int displayId) {
        for (FreeformDisplayDevice device : mFreeformDisplayDevices.values()) {
            LogicalDisplay display = mLogicalDisplayMapper.getDisplayLocked(device);
            if (display != null && display.getDisplayIdLocked() == displayId) {
                return true;
            }
        }
        return false;
    }

    private float[] getDefaultDisplayRefreshRatesLocked(float fallbackRefreshRate) {
        if (mDefaultDisplayRefreshRates.length == 0) {
            final float[] refreshRates = readDefaultDisplayRefreshRatesLocked();
            if (refreshRates.length > 0) {
                mDefaultDisplayRefreshRates = Arrays.copyOf(
                        refreshRates, refreshRates.length);
            }
        }
        return mDefaultDisplayRefreshRates.length > 0
                ? mDefaultDisplayRefreshRates : new float[] {fallbackRefreshRate};
    }

    void updateDefaultDisplayRefreshRatesLocked() {
        final float[] refreshRates = readDefaultDisplayRefreshRatesLocked();
        if (refreshRates.length == 0
                || Arrays.equals(refreshRates, mDefaultDisplayRefreshRates)) {
            return;
        }
        mDefaultDisplayRefreshRates = Arrays.copyOf(refreshRates, refreshRates.length);
        for (FreeformDisplayDevice device : mFreeformDisplayDevices.values()) {
            device.updateSupportedRefreshRatesLocked(mDefaultDisplayRefreshRates);
        }
    }

    private float[] readDefaultDisplayRefreshRatesLocked() {
        final LogicalDisplay defaultDisplay =
                mLogicalDisplayMapper.getDisplayLocked(Display.DEFAULT_DISPLAY);
        if (defaultDisplay == null) {
            return new float[0];
        }
        final DisplayInfo displayInfo = defaultDisplay.getDisplayInfoLocked();
        return displayInfo.supportedRefreshRates.length > 0
                ? displayInfo.supportedRefreshRates : displayInfo.getDefaultRefreshRatesLegacy();
    }

    private class FreeformDisplayDevice extends DisplayDevice implements IBinder.DeathRecipient {
        private static final int PENDING_SURFACE_CHANGE = 0x01;
        private static final int PENDING_RESIZE = 0x02;
        
        private final String mName;
        private float mDefaultRefreshRate;
        private float[] mSupportedRefreshRates;
        private final long mDisplayPresentationDeadlineNanos;
        private FreeformFlags mFlags;
        private Surface mSurface;
        private int mWidth;
        private int mHeight;
        private int mDensityDpi;
        private Display.Mode mMode;
        private Display.Mode[] mSupportedModes;
        private final Callback mCallback;
        private final IBinder mAppToken;
        private final DisplayModeDirector.DesiredDisplayModeSpecs mDisplayModeSpecs =
                new DisplayModeDirector.DesiredDisplayModeSpecs();
        private DisplayDeviceInfo mInfo;
        private int mPendingChanges;

        public FreeformDisplayDevice(IBinder displayToken, String uniqueId,
                int width, int height, int density,
                float refreshRate, float[] supportedRefreshRates,
                long presentationDeadlineNanos,
                FreeformFlags flags,
                Surface surface, Callback callback, IBinder appToken) {
            super(MiFreeformDisplayAdapter.this, displayToken, uniqueId, getContext());
            mName = uniqueId;
            mDefaultRefreshRate = refreshRate;
            mSupportedRefreshRates = Arrays.copyOf(
                    supportedRefreshRates, supportedRefreshRates.length);
            Arrays.sort(mSupportedRefreshRates);
            mDisplayPresentationDeadlineNanos = presentationDeadlineNanos;
            mFlags = flags;
            mSurface = surface;
            mWidth = width;
            mHeight = height;
            mDensityDpi = density;
            updateSupportedModes();
            mCallback = callback;
            mAppToken = appToken;
            mPendingChanges |= PENDING_SURFACE_CHANGE;
        }
        
        public void resizeLocked(int width, int height, int densityDpi) {
            if (mWidth != width || mHeight != height || mDensityDpi != densityDpi) {
                mWidth = width;
                mHeight = height;
                mDensityDpi = densityDpi;
                updateSupportedModes();
                mInfo = null;
                mPendingChanges |= PENDING_RESIZE;
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
                sendTraversalRequestLocked();
            }
        }

        private void updateSupportedRefreshRatesLocked(float[] supportedRefreshRates) {
            final float[] refreshRates = Arrays.copyOf(
                    supportedRefreshRates, supportedRefreshRates.length);
            Arrays.sort(refreshRates);
            if (Arrays.equals(refreshRates, mSupportedRefreshRates)) {
                return;
            }
            mSupportedRefreshRates = refreshRates;
            mDefaultRefreshRate = refreshRates[refreshRates.length - 1];
            updateSupportedModes();
            mInfo = null;
            sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
            sendTraversalRequestLocked();
        }
        
        public void destroyLocked(boolean binderAlive) {
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
            DisplayControl.destroyVirtualDisplay(getDisplayTokenLocked());
            if (binderAlive) {
                mCallback.dispatchDisplayStopped();
            }
        }
        
        @Override
        public void binderDied() {
            synchronized (getSyncRoot()) {
                handleBinderDiedLocked(mAppToken);
                Slog.w(TAG, "Freeform display client died: " + mAppToken);
                destroyLocked(false);
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_REMOVED);
            }
        }

        @Override
        public boolean hasStableUniqueId() {
            return false;
        }

        @Override
        public void configureSurfaceLocked(SurfaceControl.Transaction t) {
            if ((mPendingChanges & PENDING_SURFACE_CHANGE) != 0) {
                setSurfaceLocked(t, mSurface);
                mPendingChanges &= ~PENDING_SURFACE_CHANGE;
            }
        }

        @Override
        public void configureDisplaySizeLocked(SurfaceControl.Transaction t) {
            if ((mPendingChanges & PENDING_RESIZE) != 0) {
                setDisplaySizeLocked(t, mWidth, mHeight);
                mPendingChanges &= ~PENDING_RESIZE;
            }
        }

        @Override
        public void setDesiredDisplayModeSpecsLocked(
                DisplayModeDirector.DesiredDisplayModeSpecs displayModeSpecs) {
            if (displayModeSpecs.equals(mDisplayModeSpecs)) {
                return;
            }
            mDisplayModeSpecs.copyFrom(displayModeSpecs);
            final Display.Mode requestedMode = findMode(displayModeSpecs.baseModeId);
            if (requestedMode != null && requestedMode.getModeId() != mMode.getModeId()) {
                mMode = requestedMode;
                mInfo = null;
                sendDisplayDeviceEventLocked(this, DISPLAY_DEVICE_EVENT_CHANGED);
            }
            final IBinder displayToken = getDisplayTokenLocked();
            final Surface surface = mSurface;
            final SurfaceControl.DesiredDisplayModeSpecs surfaceControlSpecs =
                    new SurfaceControl.DesiredDisplayModeSpecs(
                            0, mDisplayModeSpecs.allowGroupSwitching,
                            mDisplayModeSpecs.primary, mDisplayModeSpecs.appRequest,
                            mDisplayModeSpecs.mIdleScreenRefreshRateConfig);
            final float refreshRate = getExactRenderRate(mDisplayModeSpecs.primary.render);
            getHandler().post(() -> applyDisplayModeSpecs(
                    displayToken, surface, surfaceControlSpecs, refreshRate));
        }

        private static void applyDisplayModeSpecs(IBinder displayToken, Surface surface,
                SurfaceControl.DesiredDisplayModeSpecs displayModeSpecs, float refreshRate) {
            if (surface != null) {
                try {
                    surface.setFrameRate(refreshRate, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                            Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
                } catch (IllegalStateException e) {
                    Slog.d(TAG, "Display surface released before refresh-rate update");
                }
            }
            SurfaceControl.setDesiredDisplayModeSpecs(displayToken, displayModeSpecs);
        }

        private static float getExactRenderRate(SurfaceControl.RefreshRateRange range) {
            return range.min > 0f && Math.abs(range.max - range.min)
                    <= SurfaceControl.RefreshRateRange.FLOAT_TOLERANCE ? range.min : 0f;
        }

        private void updateSupportedModes() {
            final float activeRefreshRate = mMode == null
                    ? mDefaultRefreshRate : mMode.getRefreshRate();
            mSupportedModes = new Display.Mode[mSupportedRefreshRates.length];
            for (int i = 0; i < mSupportedRefreshRates.length; i++) {
                mSupportedModes[i] = createMode(
                        mWidth, mHeight, mSupportedRefreshRates[i]);
            }
            mMode = findClosestMode(activeRefreshRate);
        }

        private Display.Mode findMode(int modeId) {
            for (Display.Mode mode : mSupportedModes) {
                if (mode.getModeId() == modeId) {
                    return mode;
                }
            }
            return null;
        }

        private Display.Mode findClosestMode(float refreshRate) {
            Display.Mode closestMode = null;
            float closestDistance = Float.POSITIVE_INFINITY;
            for (Display.Mode mode : mSupportedModes) {
                final float distance = Math.abs(mode.getRefreshRate() - refreshRate);
                if (distance < closestDistance) {
                    closestMode = mode;
                    closestDistance = distance;
                }
            }
            return closestMode;
        }
         
        public void notifyDisplayReady(int displayId) {
            mCallback.obtainMessage(Callback.MSG_ON_DISPLAY_ADD, displayId, 0).sendToTarget();
        }

        @Override
        public DisplayDeviceInfo getDisplayDeviceInfoLocked() {
            if (mInfo == null) {
                mInfo = new DisplayDeviceInfo();
                mInfo.name = mName;
                mInfo.uniqueId = getUniqueId();
                mInfo.width = mWidth;
                mInfo.height = mHeight;
                mInfo.modeId = mMode.getModeId();
                mInfo.defaultModeId = findClosestMode(
                        mDefaultRefreshRate).getModeId();
                mInfo.supportedModes = mSupportedModes;
                mInfo.supportedRefreshRates = mSupportedRefreshRates;
                mInfo.densityDpi = mDensityDpi;
                mInfo.xDpi = mDensityDpi;
                mInfo.yDpi = mDensityDpi;
                mInfo.presentationDeadlineNanos = mDisplayPresentationDeadlineNanos +
                        1000000000L / Math.max(1, Math.round(mMode.getRefreshRate()));
                mInfo.type = Display.TYPE_VIRTUAL;
                mInfo.touch = DisplayDeviceInfo.TOUCH_VIRTUAL;
                mInfo.flags = 0;
                if (mFlags.mSecure) {
                    mInfo.flags |= DisplayDeviceInfo.FLAG_SECURE;
                }
                mInfo.flags |= DisplayDeviceInfo.FLAG_OWN_CONTENT_ONLY;
                mInfo.flags |= DisplayDeviceInfo.FLAG_ALWAYS_UNLOCKED;
                mInfo.flags |= DisplayDeviceInfo.FLAG_TRUSTED;
            }
            return mInfo;
        }
    }

    private static final class FreeformFlags {
        final boolean mSecure;
        final boolean mOwnContentOnly;
        final boolean mShouldShowSystemDecorations = false;

        FreeformFlags(boolean secure, boolean ownContentOnly, boolean shouldShowSystemDecorations) {
            mSecure = secure;
            mOwnContentOnly = ownContentOnly;
        }
    }

    private static class Callback extends Handler {
        private static final int MSG_ON_DISPLAY_ADD = 3;
        private static final int MSG_ON_DISPLAY_PAUSED = 0;
        private static final int MSG_ON_DISPLAY_RESUMED = 1;
        private static final int MSG_ON_DISPLAY_STOPPED = 2;

        private final IFreeformDisplayCallback mCallback;

        public Callback(IFreeformDisplayCallback callback, Handler handler) {
            super(handler.getLooper());
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            try {
                switch (msg.what) {
                    case MSG_ON_DISPLAY_ADD:
                        mCallback.onDisplayAdd(msg.arg1);
                        break;
                    case MSG_ON_DISPLAY_PAUSED:
                        mCallback.onDisplayPaused();
                        break;
                    case MSG_ON_DISPLAY_RESUMED:
                        mCallback.onDisplayResumed();
                        break;
                    case MSG_ON_DISPLAY_STOPPED:
                        mCallback.onDisplayStopped();
                        break;
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Failed to notify callback", e);
            }
        }
        
        public void dispatchDisplayPaused() {
            sendEmptyMessage(MSG_ON_DISPLAY_PAUSED);
        }
        
        public void dispatchDisplayResumed() {
            sendEmptyMessage(MSG_ON_DISPLAY_RESUMED);
        }
        
        public void dispatchDisplayStopped() {
            sendEmptyMessage(MSG_ON_DISPLAY_STOPPED);
        }
    }
}
