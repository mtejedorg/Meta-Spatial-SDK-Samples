/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.premiummediasample.entities

import android.util.Log

import com.ybvr.android.exoplr2avp.Player
import com.ybvr.android.exoplr2avp.SimpleExoPlayer

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector2
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.samples.premiummediasample.AnchorOnLoad
import com.meta.spatial.samples.premiummediasample.Anchorable
import com.meta.spatial.samples.premiummediasample.HeroLighting
import com.meta.spatial.samples.premiummediasample.MAX_SPAWN_DISTANCE
import com.meta.spatial.samples.premiummediasample.PanelLayerAlpha
import com.meta.spatial.samples.premiummediasample.R
import com.meta.spatial.samples.premiummediasample.Scalable
import com.meta.spatial.samples.premiummediasample.ScaledParent
import com.meta.spatial.samples.premiummediasample.SurfaceUtil
import com.meta.spatial.samples.premiummediasample.TIMINGS
import com.meta.spatial.samples.premiummediasample.data.MediaSource
import com.meta.spatial.samples.premiummediasample.data.VideoSource
import com.meta.spatial.samples.premiummediasample.getDisposableID
import com.meta.spatial.samples.premiummediasample.immersive.ControlPanelPollHandler
import com.meta.spatial.samples.premiummediasample.millisToFloat
import com.meta.spatial.samples.premiummediasample.service.IPCServiceConnection
import com.meta.spatial.samples.premiummediasample.unregisterPanel
import com.meta.spatial.spatialaudio.AudioSessionId
import com.meta.spatial.spatialaudio.AudioType
import com.meta.spatial.spatialaudio.SpatialAudioFeature
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.Equirect180ShapeOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.ReadableMediaPanelRenderOptions
import com.meta.spatial.toolkit.ReadableMediaPanelSettings
import com.meta.spatial.toolkit.ReadableVideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SpatialActivityManager
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import dorkbox.tweenEngine.TweenEngine
import dorkbox.tweenEngine.TweenEquations
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Class responsible for creating a streaming panel. Options include Mono or Stereo, DRM or
 * WallLighting, Rectangular vs 180
 */
class ExoVideoEntity(
    // Changed ExoPlayer to SimpleExoPlayer
    private val exoPlayer: SimpleExoPlayer,
    mediaSource: MediaSource,
    panelRenderingStyle: PanelRenderingStyle,
    tweenEngine: TweenEngine,
    ipcServiceConnection: IPCServiceConnection,
    private val spatialAudioFeature: SpatialAudioFeature,
) : FadingPanel(tweenEngine) {
    companion object {
        val TAG = "ExoPlayerEntity"

        const val BASE_PANEL_SIZE = 0.7f // 0.7 meters
        const val TIME_REMAINING_BEFORE_RESTART = 1_000L // 1 second

        fun create(
            // Changed ExoPlayer to SimpleExoPlayer
            exoPlayer: SimpleExoPlayer,
            mediaSource: MediaSource,
            tweenEngine: TweenEngine,
            ipcServiceConnection: IPCServiceConnection,
            spatialAudioFeature: SpatialAudioFeature,
        ): ExoVideoEntity {
            val panelSize = Vector2(mediaSource.aspectRatio * BASE_PANEL_SIZE, BASE_PANEL_SIZE)
            val drmEnabled =
                mediaSource.videoSource is VideoSource.Url &&
                        mediaSource.videoSource.drmLicenseUrl != null

            // DRM can be enabled two ways:
            // 1. Activity panel (ActivityPanelRegistration, IntentPanelRegistration) + MediaPanelSettings
            // 2. VideoSurfacePanelRegistration panel
            val panelRenderingStyle =
                if (drmEnabled || mediaSource.videoShape != MediaSource.VideoShape.Rectilinear)
                    PanelRenderingStyle.DIRECT_TO_SURFACE
                else PanelRenderingStyle.READABLE

            val exoVideo =
                ExoVideoEntity(
                    exoPlayer = exoPlayer,
                    mediaSource = mediaSource,
                    panelRenderingStyle = panelRenderingStyle,
                    tweenEngine = tweenEngine,
                    ipcServiceConnection = ipcServiceConnection,
                    spatialAudioFeature = spatialAudioFeature,
                )

            // WallLighting is only supported for Rectangular panels.
            if (mediaSource.videoShape == MediaSource.VideoShape.Rectilinear) {
                // Shader effects will not work on non-readable panels.
                if (panelRenderingStyle != PanelRenderingStyle.DIRECT_TO_SURFACE) {
                    exoVideo.entity.setComponent(HeroLighting())
                }
                exoVideo.entity.setComponents(
                    PanelDimensions(Vector2(panelSize.x, panelSize.y)),
                    Anchorable(0.02f),
                    AnchorOnLoad(scaleProportional = true, distanceCheck = MAX_SPAWN_DISTANCE + 0.5f),
                    Grabbable(true, GrabbableType.PIVOT_Y),
                    ScaledParent(),
                    Scale(),
                    Scalable(),
                )
            }
            return exoVideo
        }
    }

    val id: Int = getDisposableID()

    override lateinit var entity: Entity

    // This class (ControlPanelPollHandler) must also be updated to accept SimpleExoPlayer
    val controlPanelPollHandler: ControlPanelPollHandler =
        ControlPanelPollHandler(exoPlayer, ipcServiceConnection)

    init {
        if (panelRenderingStyle == PanelRenderingStyle.READABLE) {
            createReadableSurfacePanel(mediaSource)
        } else if (panelRenderingStyle == PanelRenderingStyle.DIRECT_TO_SURFACE) {
            createDirectToSurfacePanel(mediaSource)
        }
    }

    private fun createSphere(
        radius: Float,
        longitudes: Int,
        latitudes: Int,
        material: SceneMaterial
    ): SceneMesh {
        val positions = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val uvs = mutableListOf<Float>()
        val indices = mutableListOf<Int>()

        for (lat in 0..latitudes) {
            val theta = lat * PI / latitudes
            val sinTheta = sin(theta)
            val cosTheta = cos(theta)

            for (lon in 0..longitudes) {
                val phi = lon * 2 * PI / longitudes
                val sinPhi = sin(phi)
                val cosPhi = cos(phi)

                val x = cosPhi * sinTheta
                val y = cosTheta
                val z = sinPhi * sinTheta
                val u = 1 - (lon.toFloat() / longitudes)
                val v = 1 - (lat.toFloat() / latitudes)

                normals.add(-x.toFloat())
                normals.add(-y.toFloat())
                normals.add(-z.toFloat())
                uvs.add(u)
                uvs.add(v)
                positions.add(-x.toFloat() * radius)
                positions.add(-y.toFloat() * radius)
                positions.add(-z.toFloat() * radius)
            }
        }

        for (lat in 0 until latitudes) {
            for (lon in 0 until longitudes) {
                val first = (lat * (longitudes + 1)) + lon
                val second = first + longitudes + 1
                indices.add(first)
                indices.add(second)
                indices.add(first + 1)
                indices.add(second)
                indices.add(second + 1)
                indices.add(first + 1)
            }
        }

        val vertexCount = positions.size / 3
        val colors = IntArray(vertexCount) { -1 } // Use white as the default color

        return SceneMesh.meshWithMaterials(
            positions = positions.toFloatArray(),
            normals = normals.toFloatArray(),
            uvs = uvs.toFloatArray(),
            colors = colors,
            indices = indices.toIntArray(),
            materialRanges = intArrayOf(0, indices.size),
            materials = arrayOf(material),
            createBVH = true
        )
    }

    private fun createCubemap(size: Float, material: SceneMaterial): SceneMesh {
        val s = size / 2f
        val positions =
            floatArrayOf(
                // Front face
                -s, -s, s, s, -s, s, s, s, s, -s, s, s,
                // Back face
                s, -s, -s, -s, -s, -s, -s, s, -s, s, s, -s,
                // Left face
                -s, -s, -s, -s, -s, s, -s, s, s, -s, s, -s,
                // Right face
                s, -s, s, s, -s, -s, s, s, -s, s, s, s,
                // Top face
                -s, s, s, s, s, s, s, s, -s, -s, s, -s,
                // Bottom face
                -s, -s, -s, s, -s, -s, s, -s, s, -s, -s, s,
            )
        val normals =
            floatArrayOf(
                // Front face
                0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f,
                // Back face
                0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f,
                // Left face
                1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f,
                // Right face
                -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f,
                // Top face
                0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f, 0f, -1f, 0f,
                // Bottom face
                0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f,
            )
        val uvs =
            floatArrayOf(
                // Front face
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
                // Back face
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
                // Left face
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
                // Right face
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
                // Top face
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
                // Bottom face
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
            )
        val indices =
            intArrayOf(
                0, 1, 2, 0, 2, 3, // Front
                4, 5, 6, 4, 6, 7, // Back
                8, 9, 10, 8, 10, 11, // Left
                12, 13, 14, 12, 14, 15, // Right
                16, 17, 18, 16, 18, 19, // Top
                20, 21, 22, 20, 22, 23 // Bottom
            )

        val vertexCount = positions.size / 3
        val colors = IntArray(vertexCount) { -1 }

        return SceneMesh.meshWithMaterials(
            positions = positions,
            normals = normals,
            uvs = uvs,
            colors = colors,
            indices = indices,
            materialRanges = intArrayOf(0, indices.size),
            materials = arrayOf(material),
            createBVH = true)
    }

    private fun createQuad(width: Float, height: Float, material: SceneMaterial): SceneMesh {
        val w = width / 2f
        val h = height / 2f
        val positions =
            floatArrayOf(
                -w, -h, 0f, w, -h, 0f, w, h, 0f, -w, h, 0f,
            )
        val normals =
            floatArrayOf(
                0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 0f, 1f,
            )
        val uvs =
            floatArrayOf(
                0f, 1f, 1f, 1f, 1f, 0f, 0f, 0f,
            )
        val indices =
            intArrayOf(
                0, 1, 2, 0, 2, 3,
            )

        val vertexCount = positions.size / 3
        val colors = IntArray(vertexCount) { -1 }

        return SceneMesh.meshWithMaterials(
            positions = positions,
            normals = normals,
            uvs = uvs,
            colors = colors,
            indices = indices,
            materialRanges = intArrayOf(0, indices.size),
            materials = arrayOf(material),
            createBVH = true)
    }

    /**
     * The Readable surface panel supports fetching the panel image for use in custom shaders. Less
     * performant than regular media panel
     */
    private fun createReadableSurfacePanel(
        mediaSource: MediaSource,
    ) {
        val panelSize = Vector2(mediaSource.aspectRatio * BASE_PANEL_SIZE, BASE_PANEL_SIZE)
        val readableSettings = ReadableMediaPanelSettings(
            shape = QuadShapeOptions(width = panelSize.x, height = panelSize.y),
            style = PanelStyleOptions(R.style.PanelAppThemeTransparent),
            display =
                PixelDisplayOptions(
                    width = mediaSource.videoDimensionsPx.x,
                    height = mediaSource.videoDimensionsPx.y,
                ),
            rendering =
                ReadableMediaPanelRenderOptions(
                    mips = mediaSource.mips,
                    stereoMode = mediaSource.stereoMode,
                    renderMode = PanelRenderMode.Mesh()
                ),
            input =
                PanelInputOptions(
                    ButtonBits.ButtonA or
                            ButtonBits.ButtonTriggerL or
                            ButtonBits.ButtonTriggerR
                ),
        )
        readableSettings.toPanelConfigOptions().apply {
            sceneMeshCreator = {texture: SceneTexture ->
                val unlitMaterial = SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
                //createSphere(1.0f,32,32, unlitMaterial)
                //createCubemap(1.0f, unlitMaterial)
                createQuad(16.0f, 9.0f, unlitMaterial)
            }
        }
        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
            immersiveActivity.registerPanel(
                ReadableVideoSurfacePanelRegistration(
                    id,
                    surfaceConsumer = { panelEnt, surface ->
                        SurfaceUtil.paintBlack(surface)
                        // Assuming setMediaSource extension function is updated for SimpleExoPlayer
                        exoPlayer.setMediaSource(mediaSource, immersiveActivity)
                        exoPlayer.prepare()
                        // Assuming setHighQuality extension function is updated for SimpleExoPlayer 2.14 API
                        exoPlayer.setHighQuality()
                        exoPlayer.setVideoSurface(surface)
                        addLinkSpatialAudioListener(exoPlayer, panelEnt)
                    },
                    settingsCreator = {readableSettings},
                )
            )
        }
        entity = Entity.create(Panel(id), Transform(), Visible(false), PanelLayerAlpha(0f))
    }

    /**
     * The VideoSurfacePanelRegistration renders the exoplayer directly to the Panel Surface. This
     * approach enables DRM and is the most performant option for rendering high resolution video.
     */
    private fun createDirectToSurfacePanel(
        mediaSource: MediaSource,
    ) {
        val panelSize = Vector2(mediaSource.aspectRatio * BASE_PANEL_SIZE, BASE_PANEL_SIZE)

        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
            immersiveActivity.registerPanel(
                VideoSurfacePanelRegistration(
                    id,
                    surfaceConsumer = { panelEnt, surface ->
                        SurfaceUtil.paintBlack(surface)
                        // Assuming setMediaSource extension function is updated for SimpleExoPlayer
                        exoPlayer.setMediaSource(mediaSource, immersiveActivity)
                        exoPlayer.prepare()
                        // Assuming setHighQuality extension function is updated for SimpleExoPlayer 2.14 API
                        exoPlayer.setHighQuality()
                        exoPlayer.setVideoSurface(surface)
                        addLinkSpatialAudioListener(exoPlayer, panelEnt)
                    },
                    settingsCreator = {
                        MediaPanelSettings(
                            shape =
                                when (mediaSource.videoShape) {
                                    MediaSource.VideoShape.Rectilinear ->
                                        QuadShapeOptions(panelSize.x, panelSize.y)
                                    MediaSource.VideoShape.Equirect180 ->
                                        Equirect180ShapeOptions(radius = 50f)
                                },
                            display =
                                PixelDisplayOptions(
                                    width = mediaSource.videoDimensionsPx.x,
                                    height = mediaSource.videoDimensionsPx.y,
                                ),
                            rendering =
                                MediaPanelRenderOptions(
                                    isDRM =
                                        mediaSource.videoSource is VideoSource.Url &&
                                                mediaSource.videoSource.drmLicenseUrl != null,
                                    stereoMode = mediaSource.stereoMode,
                                    zIndex =
                                        if (mediaSource.videoShape == MediaSource.VideoShape.Equirect180) -1
                                        else 0,
                                ),
                            style = PanelStyleOptions(R.style.PanelAppThemeTransparent),
                        )
                    },
                )
            )
        }

        entity =
            Entity.create(
                Transform(),
                Panel(id),
                Visible(false),
                PanelLayerAlpha(0f),
            )
    }

    // Changed ExoPlayer to SimpleExoPlayer
    private fun addLinkSpatialAudioListener(player: SimpleExoPlayer, panelEnt: Entity) {
        player.addListener(
            // Changed to 2.14 Player.EventListener
            object : Player.EventListener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        // Use a single registered audio session id (just choosing 1 as a default)
                        // as we only have one audio source playing at a time.
                        val registeredAudioSessionId = 1
                        spatialAudioFeature.registerAudioSessionId(
                            registeredAudioSessionId,
                            // Changed to .getAudioSessionId()
                            player.getAudioSessionId(),
                        )

                        // Determine the appropriate AudioType based on channel count
                        // Changed to .getAudioFormat()
                        val audioFormat = player.getAudioFormat()
                        val audioType =
                            if (audioFormat != null) {
                                when (audioFormat.channelCount) {
                                    1 -> AudioType.MONO
                                    2 -> AudioType.STEREO
                                    else ->
                                        AudioType
                                            .SOUNDFIELD // Default to soundfield for multichannel (>3 channels)
                                }
                            } else {
                                AudioType.STEREO // Default to stereo if format is unknown
                            }

                        panelEnt.setComponent(AudioSessionId(registeredAudioSessionId, audioType))
                        Log.i(
                            EXO_VIDEO_ENTITY_TAG,
                            "Set AudioSessionId component for entity ${panelEnt.id} with type: $audioType",
                        )
                    }
                }
            })
    }

    fun togglePlay(isPlaying: Boolean) {
        if (isPlaying) {
            // Changed to .getCurrentPosition() and .getDuration()
            if ((exoPlayer.getCurrentPosition() + TIME_REMAINING_BEFORE_RESTART) > exoPlayer.getDuration()) {
                exoPlayer.seekTo(0L)
            }
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    fun showPlayer(onShowComplete: (() -> Unit)? = null) {
        controlPanelPollHandler.start()
        super.fadeVisibility(
            true,
            TIMINGS.EXOPLAYER_FADE_BOTH.millisToFloat(),
            TweenEquations.Circle_In,
        ) {
            // Changed to .setPlayWhenReady(true)
            exoPlayer.setPlayWhenReady(true)
            onShowComplete?.invoke()
        }
    }

    fun hidePlayer(onHideComplete: (() -> Unit)? = null) {
        exoPlayer.pause()
        controlPanelPollHandler.stop()
        super.fadeVisibility(
            false,
            TIMINGS.EXOPLAYER_FADE_BOTH.millisToFloat(),
            TweenEquations.Circle_Out,
        ) {
            onHideComplete?.invoke()
        }
    }

    fun destroy() {
        entity.destroy()
        exoPlayer.setVideoSurface(null)
        // Changed to .stop(true) to clear playlist and reset
        exoPlayer.stop(true)
        controlPanelPollHandler.stop()
        currTween?.cancel()
        SpatialActivityManager.getVrActivity<AppSystemActivity>().unregisterPanel(id)
    }
}

enum class PanelRenderingStyle {
    READABLE,
    DIRECT_TO_SURFACE,
}

const val EXO_VIDEO_ENTITY_TAG = "ExoVideoEntity"