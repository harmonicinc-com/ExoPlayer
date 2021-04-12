package com.google.android.exoplayer2.harmoniclowlatency;

import android.util.Log;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector.MappedTrackInfo;
import com.google.common.primitives.Ints;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicInteger;

public final class LowLatencyABRController {
    private int currentSelectedBitrate;
    private final int videoRendererIndex;
    private final AtomicInteger stallCount;
    private final Timer resetStallCountTimer;
    private final long resetStallCountDelayMs;
    private final Timer increaseVideoBitrateTimer;
    private final long initialIncreaseVideoBitrateDelayMs;
    private final AtomicInteger consecutiveFailedIncreaseVideoBitrateCount;
    private final long switchingDelayMs;
    private boolean isPreviousSwitchIncrease;
    private Long previousSwitchTime;
    private TimerTask currentResetStallCountTimerTask;
    private TimerTask currentIncreaseVideoBitrateTimerTask;
    private final Player.EventListener listener;
    private final ExoPlayer player;
    private final LowLatencyTrackSelector trackSelector;

    public LowLatencyABRController(ExoPlayer player, LowLatencyTrackSelector trackSelector) {
        this.player = player;
        this.videoRendererIndex = 0;
        this.trackSelector = trackSelector;
        this.stallCount = new AtomicInteger(0);
        this.resetStallCountTimer = new Timer();
        this.resetStallCountDelayMs = 30000L;
        this.increaseVideoBitrateTimer = new Timer();
        this.initialIncreaseVideoBitrateDelayMs = 30000L;
        this.consecutiveFailedIncreaseVideoBitrateCount = new AtomicInteger(0);
        this.switchingDelayMs = 5000L;
        this.previousSwitchTime = 0L;
        this.listener = new EventListener() {
            public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
                if (playbackState == Player.STATE_BUFFERING && (System.currentTimeMillis() - previousSwitchTime < switchingDelayMs)) {
                    return;
                }
                stallCount.incrementAndGet();
                scheduleResetStallCountTimerTask();
                scheduleIncreaseVideoBitrateTimerTask(initialIncreaseVideoBitrateDelayMs << consecutiveFailedIncreaseVideoBitrateCount.get());
                if (stallCount.get() >= 3) {
                    decreaseVideoBitrate();
                }
            }
        };
    }

    public final int getCurrentSelectedBitrate() {
        return currentSelectedBitrate;
    }

    private void scheduleResetStallCountTimerTask() {
        TimerTask timerTask = currentResetStallCountTimerTask;
        if (timerTask != null) {
            timerTask.cancel();
        }

        resetStallCountTimer.purge();
        currentResetStallCountTimerTask = new TimerTask() {
            public void run() {
                Log.d("VosPlayerSdk", "resetStallCount");
                stallCount.set(0);
            }
        };
        resetStallCountTimer.schedule(currentResetStallCountTimerTask, resetStallCountDelayMs);
    }

    private void scheduleIncreaseVideoBitrateTimerTask(long delayMs) {
        Log.d("VosPlayerSdk", "Reschedule next up-switching timer task in " + delayMs / (long) 1000 + 's');
        TimerTask timerTask = currentResetStallCountTimerTask;
        if (timerTask != null) {
            timerTask.cancel();
        }

        increaseVideoBitrateTimer.purge();
        currentIncreaseVideoBitrateTimerTask = new TimerTask() {
            public void run() {
                increaseVideoBitrate();
            }
        };
        increaseVideoBitrateTimer.schedule(currentIncreaseVideoBitrateTimerTask, delayMs);
    }

    private void decreaseVideoBitrate() {
        adjustVideoBitrate(false);
        stallCount.set(0);
    }

    private void increaseVideoBitrate() {
        adjustVideoBitrate(true);
    }

    private void adjustVideoBitrate(boolean increaseBitrate) {
        MappedTrackInfo curInfo = this.trackSelector.getCurrentMappedTrackInfo();
        if (curInfo == null) {
            return;
        }
        TrackGroupArray videoTrackGroups = curInfo.getTrackGroups(this.videoRendererIndex);
        TrackGroup videoTrackGroup = videoTrackGroups.get(0);
        ArrayList<Format> videoTrackList = new ArrayList<>();
        for (int x=0; x <  videoTrackGroup.length; x++) {
            Format videoTrack = videoTrackGroup.getFormat(x);
            videoTrackList.add(videoTrack);
        }

        ArrayList<Integer> bitrateList = new ArrayList<>();
        for (int x=0; x < videoTrackList.size(); x++) {
            bitrateList.add(videoTrackList.get(x).bitrate);
        }
        Collections.sort(bitrateList);

        if (currentSelectedBitrate == 0) {
            this.currentSelectedBitrate = Collections.max(bitrateList);
        }

        if (this.trackSelector.isAuto()) {
            int targetBitrate = this.getTargetedBitrate(increaseBitrate, bitrateList);
            Log.i("VosPlayerSdk", "Target Video Bitrate: " + targetBitrate);
            if (targetBitrate != this.currentSelectedBitrate) {
                ArrayList<Integer> targetIndices = new ArrayList<>();
                for (int x = 0; x < videoTrackGroup.length; x++) {
                    if (videoTrackGroup.getFormat(x).bitrate == targetBitrate) {
                        targetIndices.add(x);
                    }
                }

                trackSelector.setAutoBitrateSwitchingParameters(
                    trackSelector.getParameters().buildUpon()
                            .setSelectionOverride(
                                    videoRendererIndex,
                                    videoTrackGroups,
                                    new DefaultTrackSelector.SelectionOverride(0, Ints.toArray(targetIndices))
                            )
                            .build()
                );

                if (isPreviousSwitchIncrease) {
                    if (increaseBitrate) {
                        consecutiveFailedIncreaseVideoBitrateCount.set(0);
                    } else {
                        consecutiveFailedIncreaseVideoBitrateCount.addAndGet(1);
                        Log.d("VosPlayerSdk", "increase bitrate failed, consecutiveFailedIncreaseVideoBitrateCount " + consecutiveFailedIncreaseVideoBitrateCount);
                    }
                } else if (!increaseBitrate) {
                    consecutiveFailedIncreaseVideoBitrateCount.set(0);
                }

                scheduleIncreaseVideoBitrateTimerTask(initialIncreaseVideoBitrateDelayMs << consecutiveFailedIncreaseVideoBitrateCount.get());
                previousSwitchTime = System.currentTimeMillis();
                isPreviousSwitchIncrease = increaseBitrate;
                currentSelectedBitrate = targetBitrate;
            }
        }

    }

    private int getTargetedBitrate(boolean increaseBitrate, List<Integer> bitrateList) {
        int rv;
        int curIdx;
        if (increaseBitrate) {
            Log.i("VosPlayerSdk", "Increasing Video Bitrate due to playback stable for " + (initialIncreaseVideoBitrateDelayMs << consecutiveFailedIncreaseVideoBitrateCount.get()) + " seconds");
            curIdx = bitrateList.lastIndexOf(currentSelectedBitrate);
            rv = curIdx == bitrateList.size() - 1 ? currentSelectedBitrate : ((Number) bitrateList.get(curIdx + 1)).intValue();
        } else {
            Log.i("VosPlayerSdk", "Decreasing Video Bitrate due to multiple stalls");
            curIdx = bitrateList.indexOf(currentSelectedBitrate);
            rv = curIdx == 0 ? currentSelectedBitrate : ((Number) bitrateList.get(curIdx - 1)).intValue();
        }

        return rv;
    }

    public final void start() {
        player.addListener(listener);
    }

    public final void stop() {
        player.removeListener(listener);
        resetStallCountTimer.cancel();
        increaseVideoBitrateTimer.cancel();
    }
}
