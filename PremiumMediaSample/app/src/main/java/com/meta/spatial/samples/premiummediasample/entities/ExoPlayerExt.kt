/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.premiummediasample.entities

import android.content.Context
import android.net.Uri
import android.util.Log
import com.meta.spatial.samples.premiummediasample.data.VideoSource
import java.lang.Exception

// ExoPlayer 2 Imports
import com.ybvr.android.exoplr2avp.C
import com.ybvr.android.exoplr2avp.MediaItem
import com.ybvr.android.exoplr2avp.video.VideoSize // Package changed
import com.ybvr.android.exoplr2avp.upstream.DefaultHttpDataSource // Package changed
import com.ybvr.android.exoplr2avp.DefaultLoadControl
import com.ybvr.android.exoplr2avp.DefaultRenderersFactory
import com.ybvr.android.exoplr2avp.ExoPlaybackException
import com.ybvr.android.exoplr2avp.SimpleExoPlayer // Changed from ExoPlayer
import com.ybvr.android.exoplr2avp.analytics.AnalyticsListener
import com.ybvr.android.exoplr2avp.source.dash.DashChunkSource // Package changed
import com.ybvr.android.exoplr2avp.source.dash.DashMediaSource // Package changed
import com.ybvr.android.exoplr2avp.source.dash.DefaultDashChunkSource // Package changed
import com.ybvr.android.exoplr2avp.source.MediaLoadData
import com.ybvr.android.exoplr2avp.source.TrackGroupArray
import com.ybvr.android.exoplr2avp.trackselection.DefaultTrackSelector
import com.ybvr.android.exoplr2avp.trackselection.MappingTrackSelector
import com.ybvr.android.exoplr2avp.trackselection.TrackSelectionArray
import com.ybvr.android.exoplr2avp.upstream.DefaultBandwidthMeter // Package changed

fun buildCustomExoPlayer(context: Context, isRemote: Boolean): SimpleExoPlayer { // Changed to SimpleExoPlayer
    val renderersFactory =
        DefaultRenderersFactory(context)
            .experimentalSetForceAsyncQueueingSynchronizationWorkaround(true)
            .setEnableDecoderFallback(true)

    val exoBuilder = SimpleExoPlayer.Builder(context, renderersFactory) // Changed to SimpleExoPlayer.Builder
    if (isRemote) {
        val loadControl =
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    10_000, // Minimum buffer before playback can start or resume
                    30_000, // Maximum buffer size to hold
                    1_000, // Buffer required to start playback after user action
                    2_000, // Buffer required to resume after a rebuffer
                )
                .build()
        exoBuilder.setLoadControl(loadControl)
    }

    val exoPlayer = exoBuilder.build()
    exoPlayer.addAnalyticLogs()
    return exoPlayer
}

fun SimpleExoPlayer.setMediaSource( // Changed receiver to SimpleExoPlayer
    mediaItem: com.meta.spatial.samples.premiummediasample.data.MediaSource,
    context: Context,
) {
    when (mediaItem.videoSource) {
        is VideoSource.Raw -> {
            val mediaUrl =
                "android.resource://" + context.packageName + "/" + mediaItem.videoSource.videoRaw
            setMediaSource(Uri.parse(mediaUrl), context, null, mediaItem.position)
        }
        is VideoSource.Url -> {
            setMediaSource(
                Uri.parse(mediaItem.videoSource.videoUrl),
                context,
                mediaItem.videoSource.drmLicenseUrl,
                mediaItem.position,
            )
        }
    }
}

fun SimpleExoPlayer.setMediaSource( // Changed receiver to SimpleExoPlayer
    uri: Uri,
    context: Context,
    licenseServer: String? = null,
    position: Long = 0L,
) {
    if (uri.toString().endsWith(".mpd")) {
        // Setup Dash Sources
        val userAgent = "ExoPlayer-Drm"
        val defaultHttpDataSourceFactory =
            DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setTransferListener(
                    DefaultBandwidthMeter.Builder(context).setResetOnNetworkTypeChange(false).build()
                )
        val dashChunkSourceFactory: DashChunkSource.Factory =
            DefaultDashChunkSource.Factory(defaultHttpDataSourceFactory)
        val manifestDataSourceFactory = DefaultHttpDataSource.Factory().setUserAgent(userAgent)

        // Media item
        val mediaItemBuilder = MediaItem.Builder().setUri(uri)

        Log.d(EXO_PLAYER_TAG, "Loading uri ${uri.toString()}")
        if (licenseServer != null) {
            Log.d(EXO_PLAYER_TAG, "Setting global drm license server ${licenseServer}")
            // Use the 2.14 API methods directly on the builder
            mediaItemBuilder
                .setDrmUuid(C.WIDEVINE_UUID)
                .setDrmLicenseUri(Uri.parse(licenseServer))
        }

        // Set dash factory with settings for DRM
        val mediaSource =
            DashMediaSource.Factory(dashChunkSourceFactory, manifestDataSourceFactory)
                .createMediaSource(mediaItemBuilder.build())
        this.setMediaSource(mediaSource)
    } else {
        // Load basic media item
        val mediaItem = MediaItem.fromUri(uri)
        this.setMediaItem(mediaItem)
    }
    if (position > 0L) seekTo(position)
}

private var globalAnalyticsId = 0

fun SimpleExoPlayer.addAnalyticLogs(logId: Int? = null) { // Changed receiver to SimpleExoPlayer
    this.addAnalyticsListener(
        object : AnalyticsListener {
            val id: Int

            init {
                if (logId != null) {
                    id = logId
                } else {
                    id = globalAnalyticsId
                    globalAnalyticsId++
                }
            }

            override fun onVideoSizeChanged(
                eventTime: AnalyticsListener.EventTime,
                width: Int,
                height: Int,
                unappliedRotationDegrees: Int,
                pixelWidthHeightRatio: Float
            ) {
                Log.d(
                    EXO_PLAYER_TAG,
                    "[Video${id}] Video size changed: ${width}x${height}",
                )
            }

            override fun onVideoSizeChanged(
                eventTime: AnalyticsListener.EventTime,
                videoSize: VideoSize,
            ) {
                Log.d(
                    EXO_PLAYER_TAG,
                    "[Video${id}] Video size changed: ${videoSize.width}x${videoSize.height}",
                )
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long,
            ) {
                Log.d(EXO_PLAYER_TAG, "[Video${id}] On Dropped Video Frames: $droppedFrames")
            }

            override fun onIsPlayingChanged(
                eventTime: AnalyticsListener.EventTime,
                isPlaying: Boolean,
            ) {
                Log.d(EXO_PLAYER_TAG, "[Video${id}] IsPlayingChanged: $isPlaying")
            }

            override fun onDownstreamFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                mediaLoadData: MediaLoadData,
            ) {
                if (mediaLoadData.trackType == C.TRACK_TYPE_VIDEO) {
                    val bitrate = mediaLoadData.trackFormat?.bitrate ?: 0
                    Log.d(
                        EXO_PLAYER_TAG,
                        "[Video${id}] Quality changed: New video bitrate = ${bitrate / 1000} kbps",
                    )
                }
            }

            override fun onTracksChanged(
                eventTime: AnalyticsListener.EventTime,
                trackGroups: TrackGroupArray,
                trackSelections: TrackSelectionArray
            ) {
                Log.d(EXO_PLAYER_TAG, "[Video${id}] onTracksChanged: $trackGroups")
                for (i in 0 until trackGroups.length) {
                    val trackGroup = trackGroups[i]
                    for (j in 0 until trackGroup.length) {
                        val format = trackGroup.getFormat(j)
                        Log.d(
                            EXO_PLAYER_TAG,
                            "[Video${id}] Track ${j + 1} in group ${i + 1}: Bitrate = ${format.bitrate}, Resolution = ${format.width}x${format.height}, Language = ${format.language}",
                        )
                    }
                }
                Log.d(
                    EXO_PLAYER_TAG,
                    "[Video${id}] track selection parameters changed $trackSelections",
                )
            }

            // CORRECT (non-deprecated) for 2.14
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                super.onAudioDecoderInitialized(
                    eventTime,
                    decoderName,
                    initializedTimestampMs,
                    initializationDurationMs
                )
                Log.d(EXO_PLAYER_TAG, "Using audio codec: $decoderName")
            }

            // CORRECT (non-deprecated) for 2.14
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                super.onVideoDecoderInitialized(
                    eventTime,
                    decoderName,
                    initializedTimestampMs,
                    initializationDurationMs
                )
                Log.d(EXO_PLAYER_TAG, "Using video codec: $decoderName")
            }

            // UPDATED SIGNATURE: removed initializedTimestampMs
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializationDurationMs: Long,
            ) {
                super.onAudioDecoderInitialized(
                    eventTime,
                    decoderName,
                    initializationDurationMs,
                )
                Log.d(EXO_PLAYER_TAG, "Using audio codec: $decoderName")
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception,
            ) {
                Log.d(EXO_PLAYER_TAG, "onVideoCodecError: $videoCodecError")
                super.onVideoCodecError(eventTime, videoCodecError)
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: ExoPlaybackException
            ) {
                Log.d(EXO_PLAYER_TAG, "onPlayerError: $error")
                super.onPlayerError(eventTime, error)
            }

            // UPDATED SIGNATURE: removed initializedTimestampMs
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializationDurationMs: Long,
            ) {
                super.onVideoDecoderInitialized(
                    eventTime,
                    decoderName,
                    initializationDurationMs,
                )
                Log.d(EXO_PLAYER_TAG, "Using video codec: $decoderName")
            }

            override fun onDrmSessionManagerError(
                eventTime: AnalyticsListener.EventTime,
                error: Exception,
            ) {
                super.onDrmSessionManagerError(eventTime, error)
                Log.d(EXO_PLAYER_TAG, "onDrmSessionManagerError: $error")
            }

            // Note: The 4-argument version of onAudioDecoderInitialized and
            // onVideoDecoderInitialized still exists in ExoPlayer 2 but is
            // deprecated. We override the new 3-argument version instead.
        }
    )
}

fun SimpleExoPlayer.setHighQuality() { // Changed receiver to SimpleExoPlayer
    val groupIndex = 0
    val trackIndex =
        getLastTrackIndex(groupIndex) // In the Sintel video, higher track index is higher resolution
    Log.d(
        EXO_PLAYER_TAG,
        "Setting highQuality, setting group ${groupIndex + 1} to track ${trackIndex + 1}",
    )

    setQualityForTrackGroup(groupIndex, trackIndex)
}

fun SimpleExoPlayer.getLastTrackIndex(groupIndex: Int): Int {
    val trackGroups = currentTrackGroups
    if (trackGroups == null) {
        Log.w("TrackSelection", "currentTrackGroups is null.")
        return 0
    }

    // Ensure the requested group and track indices are within bounds
    if (groupIndex < trackGroups.length) {
        // CORRECTED: Use .get()
        val trackGroupInfo = trackGroups.get(groupIndex)
        return trackGroupInfo.length - 1
    }
    return 0
}

fun SimpleExoPlayer.setQualityForTrackGroup(groupIndex: Int, trackIndex: Int) {
    val trackSelector = this.trackSelector as? DefaultTrackSelector
    if (trackSelector == null) {
        Log.w("TrackSelection", "Track selector is not a DefaultTrackSelector.")
        return
    }

    // 1. Get MappedTrackInfo
    val mappedTrackInfo: MappingTrackSelector.MappedTrackInfo? = trackSelector.currentMappedTrackInfo
    if (mappedTrackInfo == null) {
        Log.w("TrackSelection", "MappedTrackInfo is null. Cannot set quality.")
        return
    }

    // 2. Get all track groups
    val trackGroups: TrackGroupArray? = this.currentTrackGroups
    if (trackGroups == null) {
        Log.w("TrackSelection", "currentTrackGroups is null.")
        return
    }

    // 3. Get the parameters builder
    val parametersBuilder = trackSelector.parameters.buildUpon().clearSelectionOverrides()

    // 4. Check group bounds (using .length for TrackGroupArray)
    if (groupIndex < trackGroups.length) {

        // 5. Get the specific TrackGroup (using .get() for TrackGroupArray)
        val trackGroup = trackGroups.get(groupIndex)

        // 6. Check track bounds
        if (trackIndex < trackGroup.length) {

            // 7. Find the rendererIndex that contains this trackGroup
            var rendererIndex: Int = -1
            for (i in 0 until mappedTrackInfo.rendererCount) {
                val rendererTrackGroups = mappedTrackInfo.getTrackGroups(i)
                for (j in 0 until rendererTrackGroups.length) {
                    if (rendererTrackGroups.get(j) == trackGroup) {
                        rendererIndex = i
                        break
                    }
                }
                if (rendererIndex != -1) break
            }

            if (rendererIndex == -1) {
                Log.w("TrackSelection", "Could not find renderer for group $groupIndex.")
                return
            }

            val rendererTrackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)

            // 8. Create the (old API) SelectionOverride object
            // Note: This API uses the track index within the group (trackIndex)
            // and the group's index *within that renderer* (groupIndex).
            // Your 'groupIndex' is the index in *all* groups, not the renderer.
            // Let's find the group's index *within its renderer*.
            var groupIndexInRenderer: Int = -1
            for (i in 0 until rendererTrackGroups.length) {
                if (rendererTrackGroups.get(i) == trackGroup) {
                    groupIndexInRenderer = i
                    break
                }
            }

            if (groupIndexInRenderer == -1) {
                Log.w("TrackSelection", "Could not find group in renderer's group list.")
                return
            }

            val override = DefaultTrackSelector.SelectionOverride(groupIndexInRenderer, trackIndex)

            // 9. Apply the override using setSelectionOverride
            parametersBuilder.setSelectionOverride(rendererIndex, rendererTrackGroups, override)

            // 10. Set the newly built parameters
            trackSelector.setParameters(parametersBuilder.build())

            Log.d("TrackSelection", "Applied quality settings for renderer $rendererIndex, group $groupIndexInRenderer, track $trackIndex")
        } else {
            Log.w("TrackSelection", "Track index $trackIndex is out of bounds for group $groupIndex.")
        }
    } else {
        Log.w("TrackSelection", "Group index $groupIndex is out of bounds.")
    }
}

const val EXO_PLAYER_TAG = "ExoPlayer"