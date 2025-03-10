/*
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

package com.android.server;

import static com.android.server.Watchdog.HandlerCheckerAndTimeout.withCustomTimeout;
import static com.android.server.Watchdog.HandlerCheckerAndTimeout.withDefaultTimeout;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.IActivityController;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.hidl.manager.V1_0.IServiceManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Debug;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IPowerManager;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceDebugInfo;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.sysprop.WatchdogProperties;
import android.util.Dumpable;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.os.BackgroundThread;
import com.android.internal.os.ProcessCpuTracker;
import com.android.internal.os.ZygoteConnectionConstants;
import com.android.internal.util.FrameworkStatsLog;
import com.android.server.am.ActivityManagerService;
import com.android.server.am.TraceErrorLogger;
import com.android.server.criticalevents.CriticalEventLog;
import com.android.server.wm.SurfaceAnimationThread;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * This class calls its monitor every minute. Killing this process if they don't return
 **/
public class Watchdog implements Dumpable {
    static final String TAG = "Watchdog";

    /** Debug flag. */
    public static final boolean DEBUG = false;

    // Set this to true to use debug default values.
    private static final boolean DB = false;

    // Note 1: Do not lower this value below thirty seconds without tightening the invoke-with
    //         timeout in com.android.internal.os.ZygoteConnection, or wrapped applications
    //         can trigger the watchdog.
    // Note 2: The debug value is already below the wait time in ZygoteConnection. Wrapped
    //         applications may not work with a debug build. CTS will fail.
    private static final long DEFAULT_TIMEOUT = DB ? 10 * 1000 : 60 * 1000;

    // These are temporally ordered: larger values as lateness increases
    private static final int COMPLETED = 0;
    private static final int WAITING = 1;
    private static final int WAITED_HALF = 2;
    private static final int OVERDUE = 3;

    // Track watchdog timeout history and break the crash loop if there is.
    private static final String TIMEOUT_HISTORY_FILE = "/data/system/watchdog-timeout-history.txt";
    private static final String PROP_FATAL_LOOP_COUNT = "framework_watchdog.fatal_count";
    private static final String PROP_FATAL_LOOP_WINDOWS_SECS =
            "framework_watchdog.fatal_window.second";

    // Which native processes to dump into dropbox's stack traces
    public static final String[] NATIVE_STACKS_OF_INTEREST = new String[] {
        "/system/bin/audioserver",
        "/system/bin/cameraserver",
        "/system/bin/drmserver",
        "/system/bin/keystore2",
        "/system/bin/mediadrmserver",
        "/system/bin/mediaserver",
        "/system/bin/netd",
        "/system/bin/sdcard",
        "/system/bin/surfaceflinger",
        "/system/bin/vold",
        "media.extractor", // system/bin/mediaextractor
        "media.metrics", // system/bin/mediametrics
        "media.codec", // vendor/bin/hw/android.hardware.media.omx@1.0-service
        "media.swcodec", // /apex/com.android.media.swcodec/bin/mediaswcodec
        "media.transcoding", // Media transcoding service
        "com.android.bluetooth",  // Bluetooth service
        "/apex/com.android.os.statsd/bin/statsd",  // Stats daemon
    };

    public static final List<String> HAL_INTERFACES_OF_INTEREST = Arrays.asList(
            "android.hardware.audio@4.0::IDevicesFactory",
            "android.hardware.audio@5.0::IDevicesFactory",
            "android.hardware.audio@6.0::IDevicesFactory",
            "android.hardware.audio@7.0::IDevicesFactory",
            "android.hardware.biometrics.face@1.0::IBiometricsFace",
            "android.hardware.biometrics.fingerprint@2.1::IBiometricsFingerprint",
            "android.hardware.bluetooth@1.0::IBluetoothHci",
            "android.hardware.camera.provider@2.4::ICameraProvider",
            "android.hardware.gnss@1.0::IGnss",
            "android.hardware.graphics.allocator@2.0::IAllocator",
            "android.hardware.graphics.composer@2.1::IComposer",
            "android.hardware.health@2.0::IHealth",
            "android.hardware.light@2.0::ILight",
            "android.hardware.media.c2@1.0::IComponentStore",
            "android.hardware.media.omx@1.0::IOmx",
            "android.hardware.media.omx@1.0::IOmxStore",
            "android.hardware.neuralnetworks@1.0::IDevice",
            "android.hardware.power@1.0::IPower",
            "android.hardware.power.stats@1.0::IPowerStats",
            "android.hardware.sensors@1.0::ISensors",
            "android.hardware.sensors@2.0::ISensors",
            "android.hardware.sensors@2.1::ISensors",
            "android.hardware.vibrator@1.0::IVibrator",
            "android.hardware.vr@1.0::IVr",
            "android.system.suspend@1.0::ISystemSuspend"
    );

    public static final String[] AIDL_INTERFACE_PREFIXES_OF_INTEREST = new String[] {
            "android.hardware.biometrics.face.IFace/",
            "android.hardware.biometrics.fingerprint.IFingerprint/",
            "android.hardware.light.ILights/",
            "android.hardware.power.IPower/",
            "android.hardware.power.stats.IPowerStats/",
            "android.hardware.vibrator.IVibrator/",
            "android.hardware.vibrator.IVibratorManager/"
    };

    private static Watchdog sWatchdog;

    private final Thread mThread;

    private final Object mLock = new Object();

    /* This handler will be used to post message back onto the main thread */
    private final ArrayList<HandlerCheckerAndTimeout> mHandlerCheckers = new ArrayList<>();
    private final HandlerChecker mMonitorChecker;
    private ActivityManagerService mActivity;
    private IActivityController mController;
    private boolean mAllowRestart = true;
    // We start with DEFAULT_TIMEOUT. This will then be update with the timeout values from Settings
    // once the settings provider is initialized.
    private volatile long mWatchdogTimeoutMillis = DEFAULT_TIMEOUT;
    private final List<Integer> mInterestingJavaPids = new ArrayList<>();
    private final TraceErrorLogger mTraceErrorLogger;

    /** Holds a checker and its timeout. */
    static final class HandlerCheckerAndTimeout {
        private final HandlerChecker mHandler;
        private final Optional<Long> mCustomTimeoutMillis;

        private HandlerCheckerAndTimeout(HandlerChecker checker, Optional<Long> timeoutMillis) {
            this.mHandler = checker;
            this.mCustomTimeoutMillis = timeoutMillis;
        }

        HandlerChecker checker() {
            return mHandler;
        }

        /** Returns the timeout. */
        Optional<Long> customTimeoutMillis() {
            return mCustomTimeoutMillis;
        }

        /**
         * Creates a checker with the default timeout. The timeout will use the default value which
         * is configurable server-side.
         */
        static HandlerCheckerAndTimeout withDefaultTimeout(HandlerChecker checker) {
            return new HandlerCheckerAndTimeout(checker, Optional.empty());
        }

        /**
         * Creates a checker with a custom timeout. The timeout overrides the default value and will
         * always be used.
         */
        static HandlerCheckerAndTimeout withCustomTimeout(
                HandlerChecker checker, long timeoutMillis) {
            return new HandlerCheckerAndTimeout(checker, Optional.of(timeoutMillis));
        }
    }

    /**
     * Used for checking status of handle threads and scheduling monitor callbacks.
     */
    public final class  HandlerChecker implements Runnable {
        private final Handler mHandler;
        private final String mName;
        private final ArrayList<Monitor> mMonitors = new ArrayList<Monitor>();
        private final ArrayList<Monitor> mMonitorQueue = new ArrayList<Monitor>();
        private long mWaitMax;
        private boolean mCompleted;
        private Monitor mCurrentMonitor;
        private long mStartTime;
        private int mPauseCount;

        HandlerChecker(Handler handler, String name) {
            mHandler = handler;
            mName = name;
            mCompleted = true;
        }

        void addMonitorLocked(Monitor monitor) {
            // We don't want to update mMonitors when the Handler is in the middle of checking
            // all monitors. We will update mMonitors on the next schedule if it is safe
            mMonitorQueue.add(monitor);
        }

        /**
         * Schedules a run on the handler thread.
         *
         * @param handlerCheckerTimeoutMillis the timeout to use for this run
         */
        public void scheduleCheckLocked(long handlerCheckerTimeoutMillis) {
            mWaitMax = handlerCheckerTimeoutMillis;
            if (mCompleted) {
                // Safe to update monitors in queue, Handler is not in the middle of work
                mMonitors.addAll(mMonitorQueue);
                mMonitorQueue.clear();
            }
            // 如果没有监视器，并且当前线程的消息队列返回looper正在轮询，则直接完成
            if ((mMonitors.size() == 0 && mHandler.getLooper().getQueue().isPolling())
                    || (mPauseCount > 0)) {
                // Don't schedule until after resume OR
                // If the target looper has recently been polling, then
                // there is no reason to enqueue our checker on it since that
                // is as good as it not being deadlocked.  This avoid having
                // to do a context switch to check the thread. Note that we
                // only do this if we have no monitors since those would need to
                // be executed at this point.
                mCompleted = true;
                return;
            }
            // 如果已经在检测当中直接返回
            if (!mCompleted) {
                // we already have a check in flight, so no need
                return;
            }
            // 将完成设置为false，给当前线程的handler发送一个runable，并记录当前的时间戳
            mCompleted = false;
            mCurrentMonitor = null;
            mStartTime = SystemClock.uptimeMillis();
            mHandler.postAtFrontOfQueue(this);
        }

        public int getCompletionStateLocked() {
            // 根据开始监控时的时间戳进行判断，有四种状态，已经完成、未超过超时一半，超过超时一般、已经超时
            if (mCompleted) {
                return COMPLETED;
            } else {
                long latency = SystemClock.uptimeMillis() - mStartTime;
                if (latency < mWaitMax/2) {
                    return WAITING;
                } else if (latency < mWaitMax) {
                    return WAITED_HALF;
                }
            }
            return OVERDUE;
        }

        public Thread getThread() {
            return mHandler.getLooper().getThread();
        }

        public String getName() {
            return mName;
        }

        String describeBlockedStateLocked() {
            if (mCurrentMonitor == null) {
                return "Blocked in handler on " + mName + " (" + getThread().getName() + ")";
            } else {
                return "Blocked in monitor " + mCurrentMonitor.getClass().getName()
                        + " on " + mName + " (" + getThread().getName() + ")";
            }
        }

        @Override
        public void run() {
            // Once we get here, we ensure that mMonitors does not change even if we call
            // #addMonitorLocked because we first add the new monitors to mMonitorQueue and
            // move them to mMonitors on the next schedule when mCompleted is true, at which
            // point we have completed execution of this method.
            // 如果执行到这块证明，是没有问题的
            final int size = mMonitors.size();
            for (int i = 0 ; i < size ; i++) {
                synchronized (mLock) {
                    mCurrentMonitor = mMonitors.get(i);
                }
                mCurrentMonitor.monitor();
            }

            synchronized (mLock) {
                mCompleted = true;
                mCurrentMonitor = null;
            }
        }

        /** Pause the HandlerChecker. */
        public void pauseLocked(String reason) {
            mPauseCount++;
            // Mark as completed, because there's a chance we called this after the watchog
            // thread loop called Object#wait after 'WAITED_HALF'. In that case we want to ensure
            // the next call to #getCompletionStateLocked for this checker returns 'COMPLETED'
            mCompleted = true;
            Slog.i(TAG, "Pausing HandlerChecker: " + mName + " for reason: "
                    + reason + ". Pause count: " + mPauseCount);
        }

        /** Resume the HandlerChecker from the last {@link #pauseLocked}. */
        public void resumeLocked(String reason) {
            if (mPauseCount > 0) {
                mPauseCount--;
                Slog.i(TAG, "Resuming HandlerChecker: " + mName + " for reason: "
                        + reason + ". Pause count: " + mPauseCount);
            } else {
                Slog.wtf(TAG, "Already resumed HandlerChecker: " + mName);
            }
        }
    }

    final class RebootRequestReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent.getIntExtra("nowait", 0) != 0) {
                rebootSystem("Received ACTION_REBOOT broadcast");
                return;
            }
            Slog.w(TAG, "Unsupported ACTION_REBOOT broadcast: " + intent);
        }
    }

    /** Monitor for checking the availability of binder threads. The monitor will block until
     * there is a binder thread available to process in coming IPCs to make sure other processes
     * can still communicate with the service.
     */
    private static final class BinderThreadMonitor implements Watchdog.Monitor {
        @Override
        public void monitor() {
            Binder.blockUntilThreadAvailable();
        }
    }

    public interface Monitor {
        void monitor();
    }

    public static Watchdog getInstance() {
        if (sWatchdog == null) {
            sWatchdog = new Watchdog();
        }

        return sWatchdog;
    }

    private Watchdog() {
        // 创建一个线程
        mThread = new Thread(this::run, "watchdog");

        // Initialize handler checkers for each common thread we want to check.  Note
        // that we are not currently checking the background thread, since it can
        // potentially hold longer running operations with no guarantees about the timeliness
        // of operations there.
        //
        // The shared foreground thread is the main checker.  It is where we
        // will also dispatch monitor checks and do other work.
        mMonitorChecker = new HandlerChecker(FgThread.getHandler(),
                "foreground thread");
        mHandlerCheckers.add(withDefaultTimeout(mMonitorChecker));
        // Add checker for main thread.  We only do a quick check since there
        // can be UI running on the thread.
        mHandlerCheckers.add(withDefaultTimeout(
                new HandlerChecker(new Handler(Looper.getMainLooper()), "main thread")));
        // Add checker for shared UI thread.
        mHandlerCheckers.add(withDefaultTimeout(
                new HandlerChecker(UiThread.getHandler(), "ui thread")));
        // And also check IO thread.
        mHandlerCheckers.add(withDefaultTimeout(
                new HandlerChecker(IoThread.getHandler(), "i/o thread")));
        // And the display thread.
        mHandlerCheckers.add(withDefaultTimeout(
                new HandlerChecker(DisplayThread.getHandler(), "display thread")));
        // And the animation thread.
        mHandlerCheckers.add(withDefaultTimeout(
                 new HandlerChecker(AnimationThread.getHandler(), "animation thread")));
        // And the surface animation thread.
        mHandlerCheckers.add(withDefaultTimeout(
                new HandlerChecker(SurfaceAnimationThread.getHandler(),
                    "surface animation thread")));
        // Initialize monitor for Binder threads.
        addMonitor(new BinderThreadMonitor());

        mInterestingJavaPids.add(Process.myPid());

        // See the notes on DEFAULT_TIMEOUT.
        assert DB ||
                DEFAULT_TIMEOUT > ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS;

        mTraceErrorLogger = new TraceErrorLogger();
    }

    /**
     * Called by SystemServer to cause the internal thread to begin execution.
     */
    public void start() {
        mThread.start();
    }

    /**
     * Registers a {@link BroadcastReceiver} to listen to reboot broadcasts and trigger reboot.
     * Should be called during boot after the ActivityManagerService is up and registered
     * as a system service so it can handle registration of a {@link BroadcastReceiver}.
     */
    public void init(Context context, ActivityManagerService activity) {
        mActivity = activity;
        context.registerReceiver(new RebootRequestReceiver(),
                new IntentFilter(Intent.ACTION_REBOOT),
                android.Manifest.permission.REBOOT, null);
    }

    private static class SettingsObserver extends ContentObserver {
        private final Uri mUri = Settings.Global.getUriFor(Settings.Global.WATCHDOG_TIMEOUT_MILLIS);
        private final Context mContext;
        private final Watchdog mWatchdog;

        SettingsObserver(Context context, Watchdog watchdog) {
            super(BackgroundThread.getHandler());
            mContext = context;
            mWatchdog = watchdog;
            // Always kick once to ensure that we match current state
            onChange();
        }

        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (mUri.equals(uri)) {
                onChange();
            }
        }

        public void onChange() {
            try {
                mWatchdog.updateWatchdogTimeout(Settings.Global.getLong(
                        mContext.getContentResolver(),
                        Settings.Global.WATCHDOG_TIMEOUT_MILLIS, DEFAULT_TIMEOUT));
            } catch (RuntimeException e) {
                Slog.e(TAG, "Exception while reading settings " + e.getMessage(), e);
            }
        }
    }

    /**
     * Register an observer to listen to settings.
     *
     * It needs to be called after the settings service is initialized.
     */
    public void registerSettingsObserver(Context context) {
        context.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.WATCHDOG_TIMEOUT_MILLIS),
                false,
                new SettingsObserver(context, this),
                UserHandle.USER_SYSTEM);
    }

    /**
     * Updates watchdog timeout values.
     */
    void updateWatchdogTimeout(long timeoutMillis) {
        // See the notes on DEFAULT_TIMEOUT.
        if (!DB && timeoutMillis <= ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS) {
            timeoutMillis = ZygoteConnectionConstants.WRAPPED_PID_TIMEOUT_MILLIS + 1;
        }
        mWatchdogTimeoutMillis = timeoutMillis;
        Slog.i(TAG, "Watchdog timeout updated to " + mWatchdogTimeoutMillis + " millis");
    }

    private static boolean isInterestingJavaProcess(String processName) {
        return processName.equals(StorageManagerService.sMediaStoreAuthorityProcessName)
                || processName.equals("com.android.phone");
    }

    /**
     * Notifies the watchdog when a Java process with {@code pid} is started.
     * This process may have its stack trace dumped during an ANR.
     */
    public void processStarted(String processName, int pid) {
        if (isInterestingJavaProcess(processName)) {
            Slog.i(TAG, "Interesting Java process " + processName + " started. Pid " + pid);
            synchronized (mLock) {
                mInterestingJavaPids.add(pid);
            }
        }
    }

    /**
     * Notifies the watchdog when a Java process with {@code pid} dies.
     */
    public void processDied(String processName, int pid) {
        if (isInterestingJavaProcess(processName)) {
            Slog.i(TAG, "Interesting Java process " + processName + " died. Pid " + pid);
            synchronized (mLock) {
                mInterestingJavaPids.remove(Integer.valueOf(pid));
            }
        }
    }

    public void setActivityController(IActivityController controller) {
        synchronized (mLock) {
            mController = controller;
        }
    }

    public void setAllowRestart(boolean allowRestart) {
        synchronized (mLock) {
            mAllowRestart = allowRestart;
        }
    }

    public void addMonitor(Monitor monitor) {
        synchronized (mLock) {
            mMonitorChecker.addMonitorLocked(monitor);
        }
    }

    public void addThread(Handler thread) {
        synchronized (mLock) {
            final String name = thread.getLooper().getThread().getName();
            mHandlerCheckers.add(withDefaultTimeout(new HandlerChecker(thread, name)));
        }
    }

    public void addThread(Handler thread, long timeoutMillis) {
        synchronized (mLock) {
            final String name = thread.getLooper().getThread().getName();
            mHandlerCheckers.add(
                    withCustomTimeout(new HandlerChecker(thread, name), timeoutMillis));
        }
    }

    /**
     * Pauses Watchdog action for the currently running thread. Useful before executing long running
     * operations that could falsely trigger the watchdog. Each call to this will require a matching
     * call to {@link #resumeWatchingCurrentThread}.
     *
     * <p>If the current thread has not been added to the Watchdog, this call is a no-op.
     *
     * <p>If the Watchdog is already paused for the current thread, this call adds
     * adds another pause and will require an additional {@link #resumeCurrentThread} to resume.
     *
     * <p>Note: Use with care, as any deadlocks on the current thread will be undetected until all
     * pauses have been resumed.
     */
    public void pauseWatchingCurrentThread(String reason) {
        synchronized (mLock) {
            for (HandlerCheckerAndTimeout hc : mHandlerCheckers) {
                HandlerChecker checker = hc.checker();
                if (Thread.currentThread().equals(checker.getThread())) {
                    checker.pauseLocked(reason);
                }
            }
        }
    }

    /**
     * Resumes the last pause from {@link #pauseWatchingCurrentThread} for the currently running
     * thread.
     *
     * <p>If the current thread has not been added to the Watchdog, this call is a no-op.
     *
     * <p>If the Watchdog action for the current thread is already resumed, this call logs a wtf.
     *
     * <p>If all pauses have been resumed, the Watchdog action is finally resumed, otherwise,
     * the Watchdog action for the current thread remains paused until resume is called at least
     * as many times as the calls to pause.
     */
    public void resumeWatchingCurrentThread(String reason) {
        synchronized (mLock) {
            for (HandlerCheckerAndTimeout hc : mHandlerCheckers) {
                HandlerChecker checker = hc.checker();
                if (Thread.currentThread().equals(checker.getThread())) {
                    checker.resumeLocked(reason);
                }
            }
        }
    }

    /**
     * Perform a full reboot of the system.
     */
    void rebootSystem(String reason) {
        Slog.i(TAG, "Rebooting system because: " + reason);
        IPowerManager pms = (IPowerManager)ServiceManager.getService(Context.POWER_SERVICE);
        try {
            pms.reboot(false, reason, false);
        } catch (RemoteException ex) {
        }
    }

    // 返回最大的状态
    private int evaluateCheckerCompletionLocked() {
        int state = COMPLETED;
        for (int i=0; i<mHandlerCheckers.size(); i++) {
            HandlerChecker hc = mHandlerCheckers.get(i).checker();
            state = Math.max(state, hc.getCompletionStateLocked());
        }
        return state;
    }

    // 获取当前符合状态的handlerChecker对象
    private ArrayList<HandlerChecker> getCheckersWithStateLocked(int completionState) {
        ArrayList<HandlerChecker> checkers = new ArrayList<HandlerChecker>();
        for (int i=0; i<mHandlerCheckers.size(); i++) {
            HandlerChecker hc = mHandlerCheckers.get(i).checker();
            if (hc.getCompletionStateLocked() == completionState) {
                checkers.add(hc);
            }
        }
        return checkers;
    }

    // 将符合状态的HandlerCheck打印出来
    private String describeCheckersLocked(List<HandlerChecker> checkers) {
        StringBuilder builder = new StringBuilder(128);
        for (int i=0; i<checkers.size(); i++) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(checkers.get(i).describeBlockedStateLocked());
        }
        return builder.toString();
    }

    private static void addInterestingHidlPids(HashSet<Integer> pids) {
        try {
            IServiceManager serviceManager = IServiceManager.getService();
            ArrayList<IServiceManager.InstanceDebugInfo> dump =
                    serviceManager.debugDump();
            for (IServiceManager.InstanceDebugInfo info : dump) {
                if (info.pid == IServiceManager.PidConstant.NO_PID) {
                    continue;
                }

                if (!HAL_INTERFACES_OF_INTEREST.contains(info.interfaceName)) {
                    continue;
                }

                pids.add(info.pid);
            }
        } catch (RemoteException e) {
            Log.w(TAG, e);
        }
    }

    private static void addInterestingAidlPids(HashSet<Integer> pids) {
        ServiceDebugInfo[] infos = ServiceManager.getServiceDebugInfo();
        if (infos == null) return;

        for (ServiceDebugInfo info : infos) {
            for (String prefix : AIDL_INTERFACE_PREFIXES_OF_INTEREST) {
                if (info.name.startsWith(prefix)) {
                    pids.add(info.debugPid);
                }
            }
        }
    }

    static ArrayList<Integer> getInterestingNativePids() {
        HashSet<Integer> pids = new HashSet<>();
        addInterestingAidlPids(pids);
        addInterestingHidlPids(pids);

        int[] nativePids = Process.getPidsForCommands(NATIVE_STACKS_OF_INTEREST);
        if (nativePids != null) {
            for (int i : nativePids) {
                pids.add(i);
            }
        }

        return new ArrayList<Integer>(pids);
    }

    private void run() {
        boolean waitedHalf = false;

        while (true) {
            // 创建一个空白的集合，用于存放blocked的HandlerChecker
            List<HandlerChecker> blockedCheckers = Collections.emptyList();
            String subject = "";
            boolean allowRestart = true;
            int debuggerWasConnected = 0;
            boolean doWaitedHalfDump = false;
            // The value of mWatchdogTimeoutMillis might change while we are executing the loop.
            // We store the current value to use a consistent value for all handlers.
            // 默认超时60秒
            final long watchdogTimeoutMillis = mWatchdogTimeoutMillis;
            final long checkIntervalMillis = watchdogTimeoutMillis / 2;
            final ArrayList<Integer> pids;
            synchronized (mLock) {
                long timeout = checkIntervalMillis;
                // Make sure we (re)spin the checkers that have become idle within
                // this wait-and-check interval
                // 遍历之前创建的集合
                for (int i=0; i<mHandlerCheckers.size(); i++) {
                    HandlerCheckerAndTimeout hc = mHandlerCheckers.get(i);
                    // We pick the watchdog to apply every time we reschedule the checkers. The
                    // default timeout might have changed since the last run.
                    //     public static final int HW_TIMEOUT_MULTIPLIER = SystemProperties.getInt("ro.hw_timeout_multiplier", 1);
                    // 分别执行检查
                    hc.checker().scheduleCheckLocked(hc.customTimeoutMillis()
                            .orElse(watchdogTimeoutMillis * Build.HW_TIMEOUT_MULTIPLIER));
                }

                if (debuggerWasConnected > 0) {
                    debuggerWasConnected--;
                }

                // NOTE: We use uptimeMillis() here because we do not want to increment the time we
                // wait while asleep. If the device is asleep then the thing that we are waiting
                // to timeout on is asleep as well and won't have a chance to run, causing a false
                // positive on when to kill things.
                // 等30秒
                long start = SystemClock.uptimeMillis();
                while (timeout > 0) {
                    if (Debug.isDebuggerConnected()) {
                        debuggerWasConnected = 2;
                    }
                    try {
                        mLock.wait(timeout);
                        // Note: mHandlerCheckers and mMonitorChecker may have changed after waiting
                    } catch (InterruptedException e) {
                        Log.wtf(TAG, e);
                    }
                    if (Debug.isDebuggerConnected()) {
                        debuggerWasConnected = 2;
                    }
                    timeout = checkIntervalMillis - (SystemClock.uptimeMillis() - start);
                }

                final int waitState = evaluateCheckerCompletionLocked();
                // 如果时完成那么所有的重置
                if (waitState == COMPLETED) {
                    // The monitors have returned; reset
                    waitedHalf = false;
                    continue;
                } else if (waitState == WAITING) {
                    // still waiting but within their configured intervals; back off and recheck
                    // 如果时等待则继续
                    continue;
                } else if (waitState == WAITED_HALF) {// 如果是等待超过一半
                    if (!waitedHalf) { // 等待超过一般，保存一下trace
                        Slog.i(TAG, "WAITED_HALF");
                        waitedHalf = true;
                        // We've waited half, but we'd need to do the stack trace dump w/o the lock.
                        blockedCheckers = getCheckersWithStateLocked(WAITED_HALF);
                        subject = describeCheckersLocked(blockedCheckers);
                        pids = new ArrayList<>(mInterestingJavaPids);
                        doWaitedHalfDump = true;
                    } else {
                        continue;
                    }
                } else {
                    // something is overdue!
                    // 如果已经超时则要重启systemserver
                    blockedCheckers = getCheckersWithStateLocked(OVERDUE);
                    subject = describeCheckersLocked(blockedCheckers);
                    allowRestart = mAllowRestart;
                    pids = new ArrayList<>(mInterestingJavaPids);
                }
            } // END synchronized (mLock)

            // If we got here, that means that the system is most likely hung.
            //
            // First collect stack traces from all threads of the system process.
            //
            // Then, if we reached the full timeout, kill this process so that the system will
            // restart. If we reached half of the timeout, just log some information and continue.
            // 如果走到这里，意味着系统可能已经挂起
            logWatchog(doWaitedHalfDump, subject, pids);

            // 等了一半时间，则继续
            if (doWaitedHalfDump) {
                // We have waited for only half of the timeout, we continue to wait for the duration
                // of the full timeout before killing the process.
                continue;
            }

            IActivityController controller;
            synchronized (mLock) {
                controller = mController;
            }
            if (controller != null) {
                Slog.i(TAG, "Reporting stuck state to activity controller");
                try {
                    Binder.setDumpDisabled("Service dumps disabled due to hung system process.");
                    // 1 = keep waiting, -1 = kill system
                    // 调用AMS的系统无响应
                    int res = controller.systemNotResponding(subject);
                    if (res >= 0) {
                        Slog.i(TAG, "Activity controller requested to coninue to wait");
                        waitedHalf = false;
                        continue;
                    }
                } catch (RemoteException e) {
                }
            }

            // Only kill the process if the debugger is not attached.
            if (Debug.isDebuggerConnected()) {
                debuggerWasConnected = 2;
            }
            if (debuggerWasConnected >= 2) {
                Slog.w(TAG, "Debugger connected: Watchdog is *not* killing the system process");
            } else if (debuggerWasConnected > 0) {
                Slog.w(TAG, "Debugger was connected: Watchdog is *not* killing the system process");
            } else if (!allowRestart) {
                Slog.w(TAG, "Restart not allowed: Watchdog is *not* killing the system process");
            } else {
                // 打印log并kill掉systemserver进程，使得手机重启
                Slog.w(TAG, "*** WATCHDOG KILLING SYSTEM PROCESS: " + subject);
                WatchdogDiagnostics.diagnoseCheckers(blockedCheckers);
                Slog.w(TAG, "*** GOODBYE!");
                if (!Build.IS_USER && isCrashLoopFound()
                        && !WatchdogProperties.should_ignore_fatal_count().orElse(false)) {
                    breakCrashLoop();
                }
                Process.killProcess(Process.myPid());
                System.exit(10);
            }

            waitedHalf = false;
        }
    }

    private void logWatchog(boolean halfWatchdog, String subject, ArrayList<Integer> pids) {
        // Get critical event log before logging the half watchdog so that it doesn't
        // occur in the log.
        String criticalEvents =
                CriticalEventLog.getInstance().logLinesForSystemServerTraceFile();
        final UUID errorId = mTraceErrorLogger.generateErrorId();
        if (mTraceErrorLogger.isAddErrorIdEnabled()) {
            mTraceErrorLogger.addErrorIdToTrace("system_server", errorId);
            mTraceErrorLogger.addSubjectToTrace(subject, errorId);
        }

        final String dropboxTag;
        if (halfWatchdog) {
            dropboxTag = "pre_watchdog";
            CriticalEventLog.getInstance().logHalfWatchdog(subject);
        } else {
            dropboxTag = "watchdog";
            CriticalEventLog.getInstance().logWatchdog(subject, errorId);
            EventLog.writeEvent(EventLogTags.WATCHDOG, subject);
            // Log the atom as early as possible since it is used as a mechanism to trigger
            // Perfetto. Ideally, the Perfetto trace capture should happen as close to the
            // point in time when the Watchdog happens as possible.
            FrameworkStatsLog.write(FrameworkStatsLog.SYSTEM_SERVER_WATCHDOG_OCCURRED, subject);
        }

        long anrTime = SystemClock.uptimeMillis();
        StringBuilder report = new StringBuilder();
        report.append(MemoryPressureUtil.currentPsiState());
        ProcessCpuTracker processCpuTracker = new ProcessCpuTracker(false);
        StringWriter tracesFileException = new StringWriter();
        final File stack = ActivityManagerService.dumpStackTraces(
                pids, processCpuTracker, new SparseArray<>(), getInterestingNativePids(),
                tracesFileException, subject, criticalEvents);
        // Give some extra time to make sure the stack traces get written.
        // The system's been hanging for a whlie, another second or two won't hurt much.
        SystemClock.sleep(5000);
        processCpuTracker.update();
        report.append(processCpuTracker.printCurrentState(anrTime));
        report.append(tracesFileException.getBuffer());

        if (!halfWatchdog) {
            // Trigger the kernel to dump all blocked threads, and backtraces on all CPUs to the
            // kernel log
            doSysRq('w');
            doSysRq('l');
        }

        // Try to add the error to the dropbox, but assuming that the ActivityManager
        // itself may be deadlocked.  (which has happened, causing this statement to
        // deadlock and the watchdog as a whole to be ineffective)
        Thread dropboxThread = new Thread("watchdogWriteToDropbox") {
                public void run() {
                    // If a watched thread hangs before init() is called, we don't have a
                    // valid mActivity. So we can't log the error to dropbox.
                    if (mActivity != null) {
                        mActivity.addErrorToDropBox(
                                dropboxTag, null, "system_server", null, null, null,
                                null, report.toString(), stack, null, null, null,
                                errorId);
                    }
                }
            };
        dropboxThread.start();
        try {
            dropboxThread.join(2000);  // wait up to 2 seconds for it to return.
        } catch (InterruptedException ignored) { }
    }

    private void doSysRq(char c) {
        try {
            FileWriter sysrq_trigger = new FileWriter("/proc/sysrq-trigger");
            sysrq_trigger.write(c);
            sysrq_trigger.close();
        } catch (IOException e) {
            Slog.w(TAG, "Failed to write to /proc/sysrq-trigger", e);
        }
    }

    private void resetTimeoutHistory() {
        writeTimeoutHistory(new ArrayList<String>());
    }

    private void writeTimeoutHistory(Iterable<String> crashHistory) {
        String data = String.join(",", crashHistory);

        try (FileWriter writer = new FileWriter(TIMEOUT_HISTORY_FILE)) {
            writer.write(SystemProperties.get("ro.boottime.zygote"));
            writer.write(":");
            writer.write(data);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to write file " + TIMEOUT_HISTORY_FILE, e);
        }
    }

    private String[] readTimeoutHistory() {
        final String[] emptyStringArray = {};

        try (BufferedReader reader = new BufferedReader(new FileReader(TIMEOUT_HISTORY_FILE))) {
            String line = reader.readLine();
            if (line == null) {
                return emptyStringArray;
            }

            String[] data = line.trim().split(":");
            String boottime = data.length >= 1 ? data[0] : "";
            String history = data.length >= 2 ? data[1] : "";
            if (SystemProperties.get("ro.boottime.zygote").equals(boottime) && !history.isEmpty()) {
                return history.split(",");
            } else {
                return emptyStringArray;
            }
        } catch (FileNotFoundException e) {
            return emptyStringArray;
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read file " + TIMEOUT_HISTORY_FILE, e);
            return emptyStringArray;
        }
    }

    private boolean hasActiveUsbConnection() {
        try {
            final String state = FileUtils.readTextFile(
                    new File("/sys/class/android_usb/android0/state"),
                    128 /*max*/, null /*ellipsis*/).trim();
            if ("CONFIGURED".equals(state)) {
                return true;
            }
        } catch (IOException e) {
            Slog.w(TAG, "Failed to determine if device was on USB", e);
        }
        return false;
    }

    private boolean isCrashLoopFound() {
        int fatalCount = WatchdogProperties.fatal_count().orElse(0);
        long fatalWindowMs = TimeUnit.SECONDS.toMillis(
                WatchdogProperties.fatal_window_seconds().orElse(0));
        if (fatalCount == 0 || fatalWindowMs == 0) {
            if (fatalCount != fatalWindowMs) {
                Slog.w(TAG, String.format("sysprops '%s' and '%s' should be set or unset together",
                            PROP_FATAL_LOOP_COUNT, PROP_FATAL_LOOP_WINDOWS_SECS));
            }
            return false;
        }

        // new-history = [last (fatalCount - 1) items in old-history] + [nowMs].
        long nowMs = SystemClock.elapsedRealtime(); // Time since boot including deep sleep.
        String[] rawCrashHistory = readTimeoutHistory();
        ArrayList<String> crashHistory = new ArrayList<String>(Arrays.asList(Arrays.copyOfRange(
                        rawCrashHistory,
                        Math.max(0, rawCrashHistory.length - fatalCount - 1),
                        rawCrashHistory.length)));
        // Something wrong here.
        crashHistory.add(String.valueOf(nowMs));
        writeTimeoutHistory(crashHistory);

        // Returns false if the device has an active USB connection.
        if (hasActiveUsbConnection()) {
            return false;
        }

        long firstCrashMs;
        try {
            firstCrashMs = Long.parseLong(crashHistory.get(0));
        } catch (NumberFormatException t) {
            Slog.w(TAG, "Failed to parseLong " + crashHistory.get(0), t);
            resetTimeoutHistory();
            return false;
        }
        return crashHistory.size() >= fatalCount && nowMs - firstCrashMs < fatalWindowMs;
    }

    private void breakCrashLoop() {
        try (FileWriter kmsg = new FileWriter("/dev/kmsg_debug", /* append= */ true)) {
            kmsg.append("Fatal reset to escape the system_server crashing loop\n");
        } catch (IOException e) {
            Slog.w(TAG, "Failed to append to kmsg", e);
        }
        doSysRq('c');
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @Nullable String[] args) {
        pw.print("WatchdogTimeoutMillis=");
        pw.println(mWatchdogTimeoutMillis);
    }
}
