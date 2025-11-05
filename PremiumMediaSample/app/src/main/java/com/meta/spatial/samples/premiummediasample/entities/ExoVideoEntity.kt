/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.spatial.samples.premiummediasample.entities

import android.graphics.Color
import android.util.Log
import android.view.Surface

import com.ybvr.android.exoplr2avp.Player
import com.ybvr.android.exoplr2avp.SimpleExoPlayer

import com.meta.spatial.core.Entity
import com.meta.spatial.core.Vector2
import com.meta.spatial.isdk.IsdkGrabbable
import com.meta.spatial.isdk.IsdkPanelDimensions
import com.meta.spatial.isdk.IsdkPanelGrabHandle
import com.meta.spatial.isdk.updateIsdkComponentProperties
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.ButtonBits
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelSceneObject
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneObject
import com.meta.spatial.runtime.SceneTexture
import com.meta.spatial.runtime.StereoMode
import com.meta.spatial.runtime.TriangleMesh
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
import com.meta.spatial.toolkit.Equirect360ShapeOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.Hittable
import com.meta.spatial.toolkit.MediaPanelRenderOptions
import com.meta.spatial.toolkit.MediaPanelSettings
import com.meta.spatial.toolkit.MediaPanelShapeOptions
import com.meta.spatial.toolkit.MeshCollision
import com.meta.spatial.toolkit.Panel
import com.meta.spatial.toolkit.PanelCreator
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelInputOptions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.PixelDisplayOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.ReadableMediaPanelRenderOptions
import com.meta.spatial.toolkit.ReadableMediaPanelSettings
import com.meta.spatial.toolkit.ReadableVideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.SceneObjectSystem
import com.meta.spatial.toolkit.SpatialActivityManager
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.VideoSurfacePanelRegistration
import com.meta.spatial.toolkit.Visible
import com.ybvr.ybvrlib.GeometryID
import dorkbox.tweenEngine.TweenEngine
import dorkbox.tweenEngine.TweenEquations
import java.util.concurrent.CompletableFuture
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

            val exoVideo =
                ExoVideoEntity(
                    exoPlayer = exoPlayer,
                    mediaSource = mediaSource,
                    tweenEngine = tweenEngine,
                    ipcServiceConnection = ipcServiceConnection,
                    spatialAudioFeature = spatialAudioFeature,
                )

            // WallLighting is only supported for Rectangular panels.
            if (mediaSource.videoShape == MediaSource.VideoShape.Rectilinear) {
                // Shader effects will not work on non-readable panels.
                if (mediaSource.renderingStyle != MediaSource.PanelRenderingStyle.DIRECT_TO_SURFACE) {
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
        when (mediaSource.renderingStyle) {
            MediaSource.PanelRenderingStyle.READABLE ->
                createReadableSurfacePanel(mediaSource)
            MediaSource.PanelRenderingStyle.DIRECT_TO_SURFACE ->
                createDirectToSurfacePanel(mediaSource)
            MediaSource.PanelRenderingStyle.CUSTOM_PANEL ->
                createCustomPanel(mediaSource)
            MediaSource.PanelRenderingStyle.CUSTOM_DIRECT_TO_SURFACE ->
                createCustomPanelDirectToSurface(mediaSource)
        }
    }

    private fun getCompositorShape(mediaSource: MediaSource): MediaPanelShapeOptions {
        val panelSize = Vector2(mediaSource.aspectRatio * BASE_PANEL_SIZE, BASE_PANEL_SIZE)
        val radius = 50.0f
        return when (mediaSource.videoShape) {
            MediaSource.VideoShape.Rectilinear ->
                QuadShapeOptions(panelSize.x, panelSize.y)
            MediaSource.VideoShape.Equirect180 ->
                Equirect180ShapeOptions(radius)
            MediaSource.VideoShape.Equirect360 ->
                Equirect360ShapeOptions(radius)
            MediaSource.VideoShape.YBVR ->
                TODO()
        }
    }

    private fun setupExoPlayer(
        surface: Surface,
        mediaSource: MediaSource,
        immersiveActivity: AppSystemActivity,
        panelEnt: Entity
    ) {
        // Init surface
        SurfaceUtil.paintBlack(surface)

        // Set up ExoPlayer
        exoPlayer.setMediaSource(mediaSource, immersiveActivity)
        exoPlayer.prepare()
        exoPlayer.setHighQuality()

        // Connect ExoPlayer directly to panel surface
        exoPlayer.setVideoSurface(surface)

        // Setup Spatial Audio
        addLinkSpatialAudioListener(exoPlayer, panelEnt)
    }

    /**
     * The Readable surface panel supports fetching the panel image for use in custom shaders. Less
     * performant than regular media panel
     */
    private fun createReadableSurfacePanel(
        mediaSource: MediaSource,
    ) {
        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
            immersiveActivity.registerPanel(
                ReadableVideoSurfacePanelRegistration(
                    id,
                    surfaceConsumer = { panelEnt, surface ->
                        setupExoPlayer(surface, mediaSource, immersiveActivity, panelEnt)
                    },
                    settingsCreator = {
                        ReadableMediaPanelSettings(
                        shape = getCompositorShape(mediaSource),
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
                                //renderMode = PanelRenderMode.Mesh()
                            ),
                        input =
                            PanelInputOptions(
                                ButtonBits.ButtonA or
                                        ButtonBits.ButtonTriggerL or
                                        ButtonBits.ButtonTriggerR
                            ),
                    )},
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
        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
            immersiveActivity.registerPanel(
                VideoSurfacePanelRegistration(
                    id,
                    surfaceConsumer = { panelEnt, surface ->
                        setupExoPlayer(surface, mediaSource, immersiveActivity, panelEnt)
                    },
                    settingsCreator = {
                        MediaPanelSettings(
                            shape = getCompositorShape(mediaSource),
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

    private fun createCustomPanel(
        mediaSource: MediaSource,
    ){
        val screenWidth: Float = 16.0f / 10.0f
        val screenHeight: Float = 9.0f / 10.0f

        val panelSettings =
            MediaPanelSettings(
                shape = QuadShapeOptions(width = screenWidth, height = screenHeight),
                display = PixelDisplayOptions(width = 3840, height = 1080),
                rendering = MediaPanelRenderOptions(stereoMode = StereoMode.LeftRight),
            )

        val panelConfig = panelSettings.toPanelConfigOptions().apply {
            sceneMeshCreator = {texture: SceneTexture ->
                getYBVRGeometry(texture, mediaSource)
            }
        }

        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
            // Use the PanelRegistration constructor that accepts a creator lambda.
            immersiveActivity.registerPanel(
                PanelCreator(id) { panelEntity ->
                    // Initialize the class property with the entity provided by the system.
                    entity = panelEntity

                    // Add the components that were previously added at creation time.
                    // The entity already has Transform and Panel(id) components.
                    panelEntity.setComponents(
                        Visible(false),
                        PanelLayerAlpha(0f),
                        Transform(),
                        Hittable(hittable = MeshCollision.LineTest),
                    )

                    // Create PanelSceneObject with custom configs
                    val panelSceneObject =
                        PanelSceneObject(immersiveActivity.scene, entity, panelConfig)

                    // Assign PanelSceneObject to entity
                    immersiveActivity.systemManager
                        .findSystem<SceneObjectSystem>()
                        .addSceneObject(
                            entity,
                            CompletableFuture<SceneObject>().apply {
                                complete(panelSceneObject)
                            },
                        )

                    setupExoPlayer(
                        panelSceneObject.getSurface(),
                        mediaSource,
                        immersiveActivity,
                        entity
                    )


                    //Brought from SpatialVideoSample sample project, might not be necessary
                    setupIsdk(panelSceneObject)

                    panelSceneObject
                }
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
    private fun createCustomPanelDirectToSurface(
        mediaSource: MediaSource,
    ){
        val screenWidth: Float = 16.0f / 10.0f
        val screenHeight: Float = 9.0f / 10.0f

        val panelSettings =
            MediaPanelSettings(
                shape = QuadShapeOptions(width = screenWidth, height = screenHeight),
                display = PixelDisplayOptions(width = 3840, height = 1080),
                rendering = MediaPanelRenderOptions(stereoMode = StereoMode.LeftRight),
            )

        val panelConfig = panelSettings.toPanelConfigOptions().apply {
        //val panelConfig = PanelConfigOptions().apply {    //Default Panel Options
            /* TODO Recover this
            sceneMeshCreator = {texture: SceneTexture ->
                getYBVRGeometry(texture, mediaSource)
            }
             */

            // Enable Direct-To-Compositor prerequisites
            mips = 1
            forceSceneTexture = false
            enableTransparent = false
        }

        entity =
            Entity.create(
                Transform(),
                Panel(id),
                Visible(false),
                PanelLayerAlpha(0f),
            )

        SpatialActivityManager.executeOnVrActivity<AppSystemActivity> { immersiveActivity ->
            // Use the PanelRegistration constructor that accepts a creator lambda.
            immersiveActivity.registerPanel(
                PanelRegistration(id) { panelEntity ->
                    // Initialize the class property with the entity provided by the system.
                    entity = panelEntity

                    // Add the components that were previously added at creation time.
                    // The entity already has Transform and Panel(id) components.
                    panelEntity.setComponents(
                        Visible(false),
                        PanelLayerAlpha(0f),
                        Transform(),
                        Hittable(hittable = MeshCollision.LineTest),
                    )

                    // Create PanelSceneObject with custom configs
                    val panelSceneObject =
                        PanelSceneObject(immersiveActivity.scene, entity, panelConfig)

                    // Assign PanelSceneObject to entity
                    immersiveActivity.systemManager
                        .findSystem<SceneObjectSystem>()
                        .addSceneObject(
                            entity,
                            CompletableFuture<SceneObject>().apply {
                                complete(panelSceneObject)
                            },
                        )

                    setupExoPlayer(
                        panelSceneObject.getSurface(),
                        mediaSource,
                        immersiveActivity,
                        entity
                    )

                    //Brought from SpatialVideoSample sample project, might not be necessary
                    setupIsdk(panelSceneObject)
                }
            )
        }
    }

    private fun setupIsdk(panelSceneObject: PanelSceneObject) {
        // mark the mesh as explicitly able to catch input
        entity.setComponent(Hittable())

        // Usually, ISDK is able to create panel dimensions from a Panel component. Since the video
        // player manually constructs the PanelSceneObject, we need to manually set the panel
        // dimensions & keep them up to date when switching MR modes.
        entity.setComponent(IsdkPanelDimensions())
        entity.setComponent(IsdkPanelGrabHandle())
        entity.setComponent(IsdkGrabbable())
        panelSceneObject.updateIsdkComponentProperties(entity)
    }

    private fun getYBVRGeometry(
        texture: SceneTexture,
        mediaSource: MediaSource
    ): SceneMesh {
        //createSpatialBiQuad(width, height, texture, stereoMode)

        val unlitMaterial = SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)

        return when (mediaSource.geometry) {
            GeometryID.Equirectangular ->
                createSphere(1.0f, 32, 32, unlitMaterial)
            GeometryID.CM32 ->
                createCubemap(1.0f, unlitMaterial)
            GeometryID.ACM -> TODO()
            GeometryID.ACME -> TODO()
            GeometryID.CM180 -> TODO()
            GeometryID.Equidome -> TODO()
            GeometryID.Plane ->
                createQuad(16.0f, 9.0f, unlitMaterial)
            GeometryID.APP -> TODO()
            GeometryID.APPE -> TODO()
            GeometryID.AP3 -> TODO()
            GeometryID.ControlRoom ->
                createQuad(16.0f, 9.0f, unlitMaterial)
            GeometryID.ControlRoomv2 ->
                createQuad(16.0f, 9.0f, unlitMaterial)
            GeometryID.Unknown -> TODO()
        }
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

const val EXO_VIDEO_ENTITY_TAG = "ExoVideoEntity"

private fun createSpatialBiQuad (
    width: Float, height: Float,
    texture: SceneTexture,
    stereoMode: StereoMode) : SceneMesh{

    val halfHeight = height / 2f
    val halfWidth = width / 2f
    val halfDepth = 0.1f
    val rounding = 0.075f
    val triMesh =
        TriangleMesh(
            8,
            18,
            intArrayOf(6, 6, 12, 6, 0, 6),
            arrayOf(
                SceneMaterial(
                    texture,
                    AlphaMode.TRANSLUCENT,
                    "data/shaders/spatial/reflect",
                )
                    .apply {
                        setStereoMode(stereoMode)
                        setUnlit(true)
                    },
                SceneMaterial(
                    texture,
                    AlphaMode.TRANSLUCENT,
                    "data/shaders/spatial/shadow",
                )
                    .apply { setUnlit(true) },
                SceneMaterial(
                    texture,
                    AlphaMode.HOLE_PUNCH,
                    SceneMaterial.HOLE_PUNCH_SHADER,
                )
                    .apply {
                        setStereoMode(stereoMode)
                        setUnlit(true)
                    },
            ),
        )
    triMesh.updateGeometry(
        0,
        floatArrayOf(
            -halfWidth,
            -halfHeight,
            0f,
            halfWidth,
            -halfHeight,
            0f,
            halfWidth,
            halfHeight,
            0f,
            -halfWidth,
            halfHeight,
            0f,
            // shadow
            -halfWidth,
            -halfHeight,
            halfDepth,
            halfWidth,
            -halfHeight,
            halfDepth,
            halfWidth,
            -halfHeight,
            -halfDepth,
            -halfWidth,
            -halfHeight,
            -halfDepth,
        ),
        floatArrayOf(
            0f,
            0f,
            1f,
            0f,
            0f,
            1f,
            0f,
            0f,
            1f,
            0f,
            0f,
            1f,
            0f,
            0f,
            1f,
            0f,
            0f,
            1f,
            0f,
            0f,
            1f,
            0f,
            0f,
            1f,
        ),
        floatArrayOf(
            // front
            0f,
            1f,
            1f,
            1f,
            1f,
            0f,
            0f,
            0f,
            // shadow
            halfWidth - rounding,
            halfDepth - rounding,
            halfWidth - rounding,
            halfDepth - rounding,
            halfWidth - rounding,
            halfDepth - rounding,
            halfWidth - rounding,
            halfDepth - rounding,
        ),
        intArrayOf(
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
            Color.WHITE,
        ),
    )
    triMesh.updatePrimitives(
        0,
        intArrayOf(0, 1, 2, 0, 2, 3, 0, 2, 1, 0, 3, 2, 4, 6, 5, 4, 7, 6),
    )
    return SceneMesh.fromTriangleMesh(triMesh, false)
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
