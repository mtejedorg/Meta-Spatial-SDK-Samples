// (c) Meta Platforms, Inc. and affiliates. Confidential and proprietary.

package com.meta.levinriegner.mediaview.app.shared.model

import com.meta.levinriegner.mediaview.app.shared.theme.Dimens
import com.meta.levinriegner.mediaview.data.gallery.model.MediaModel
import com.meta.levinriegner.mediaview.data.gallery.model.MediaType.IMAGE_2D
import com.meta.levinriegner.mediaview.data.gallery.model.MediaType.IMAGE_360
import com.meta.levinriegner.mediaview.data.gallery.model.MediaType.IMAGE_PANORAMA
import com.meta.levinriegner.mediaview.data.gallery.model.MediaType.VIDEO_2D
import com.meta.levinriegner.mediaview.data.gallery.model.MediaType.VIDEO_360
import com.meta.levinriegner.mediaview.data.gallery.model.MediaType.VIDEO_SPATIAL
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.AlphaMode
import com.meta.spatial.runtime.EquirectLayerConfig
import com.meta.spatial.runtime.PanelConfigOptions
import com.meta.spatial.runtime.PanelConfigOptions.Companion.DEFAULT_DPI
import com.meta.spatial.runtime.SceneMaterial
import com.meta.spatial.runtime.SceneMesh
import com.meta.spatial.runtime.SceneTexture
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val PIXELS_TO_METERS = 0.0254f / 100f
private const val defaultTextureDp = 1280
private const val maxResolutionInPx = 20 * 1000 * 1000 // 20M, exploratory value

private const val mediaPanelSpacingOffsetDp = 0

private fun normalizeForMaxResolution(width: Int, height: Int): Pair<Int, Int> {
  val currentResolution = width * height
  if (currentResolution > maxResolutionInPx) {
    // Normalize the dimensions to the max resolution
    val reductionRatio = maxResolutionInPx.toFloat() / currentResolution
    val newWidth = width * reductionRatio
    val newHeight = height * reductionRatio
    return Pair(newWidth.toInt(), newHeight.toInt())
  }
  return Pair(width, height)
}

fun MediaModel.calculateDimensionsInMeters(): Pair<Float, Float>? {
  if (width == null || height == null) return null
  val minMetersSize = 1f
  val (width, height) = normalizeForMaxResolution(width, height)
  val widthInMeters = width * PIXELS_TO_METERS
  val heightInMeters = height * PIXELS_TO_METERS
  val offsetInMeters = dpToPx(mediaPanelSpacingOffsetDp) * PIXELS_TO_METERS
  if (widthInMeters >= minMetersSize && heightInMeters >= minMetersSize) {
    return Pair(widthInMeters + offsetInMeters, heightInMeters + offsetInMeters)
  }
  // Normalize the dimensions to the min meters size
  val ratio = widthInMeters / heightInMeters
  val newWidth = if (widthInMeters < minMetersSize) minMetersSize else widthInMeters
  val newHeight = newWidth / ratio
  return Pair(newWidth + offsetInMeters, newHeight + offsetInMeters)
}

fun MediaModel.panelWidthAndHeight(): Pair<Float, Float> {
  return calculateDimensionsInMeters()
      ?: Pair(
          1.2f,
          0.9f,
      )
}

fun MediaModel.textureWidthAndHeight(): Pair<Int, Int> {
  if (width != null && height != null) {
    return normalizeForMaxResolution(width, height)
  }
  val (panelWidth, panelHeight) = panelWidthAndHeight()
  return Pair(
      dpToPx(defaultTextureDp) + dpToPx(mediaPanelSpacingOffsetDp),
      dpToPx(
          (defaultTextureDp * (panelHeight / panelWidth)).toInt() +
              dpToPx(mediaPanelSpacingOffsetDp)
      ),
  )
}

fun MediaModel.minimizedPanelConfigOptions(): PanelConfigOptions {
  val (panelWidth, panelHeight) = panelWidthAndHeight()
  val (layoutWidthInPx, layoutHeightInPx) = textureWidthAndHeight()
  return when (mediaType) {
    IMAGE_2D ->
        PanelConfigOptions(
            width = panelWidth,
            height = panelHeight,
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            enableLayer = true,
            enableTransparent = false,
            includeGlass = false,
        )

    VIDEO_2D ->
        PanelConfigOptions(
            width = panelWidth,
            height = panelHeight,
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            enableLayer = true,
            enableTransparent = false,
            includeGlass = false,
        )

    IMAGE_PANORAMA ->
        PanelConfigOptions(
            width = panelWidth,
            height = panelHeight,
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            panelShader = "data/shaders/punch/punch",
            alphaMode = AlphaMode.HOLE_PUNCH,
            includeGlass = false,
            sceneMeshCreator = { texture: SceneTexture ->
              val unlitMaterial =
                  SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
              SceneMesh.singleSidedQuad(panelWidth / 2, panelHeight / 2, unlitMaterial)
            },
        )

    IMAGE_360 ->
        PanelConfigOptions(
            width = min(panelWidth, panelHeight),
            height = min(panelWidth, panelHeight),
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            panelShader = "data/shaders/punch/punch",
            alphaMode = AlphaMode.HOLE_PUNCH,
            includeGlass = false,
            sceneMeshCreator = { texture: SceneTexture ->
              val unlitMaterial =
                  SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
              createCubemap(1.0f, unlitMaterial)
            },
        )

    VIDEO_360 ->
        PanelConfigOptions(
            width = min(panelWidth, panelHeight),
            height = min(panelWidth, panelHeight),
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            panelShader = "data/shaders/punch/punch",
            alphaMode = AlphaMode.HOLE_PUNCH,
            includeGlass = false,
            sceneMeshCreator = { texture: SceneTexture ->
              val unlitMaterial =
                  SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
              createSphere(1.0f, 32, 32, unlitMaterial)
            },
        )

    VIDEO_SPATIAL ->
        PanelConfigOptions(
            width = panelWidth,
            height = panelHeight,
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            enableLayer = true,
            enableTransparent = false,
            includeGlass = false,
        )

    null ->
        PanelConfigOptions(
            width = panelWidth,
            height = panelHeight,
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            enableLayer = true,
            enableTransparent = false,
            includeGlass = false,
        )
  }
}

fun MediaModel.maximizedPanelConfigOptions(): PanelConfigOptions {
  val (panelWidth, panelHeight) = panelWidthAndHeight()
  val (layoutWidthInPx, layoutHeightInPx) = textureWidthAndHeight()
  return when (mediaType) {
    IMAGE_2D,
    VIDEO_2D,
    VIDEO_SPATIAL,
    null ->
        PanelConfigOptions(
            width = panelWidth * 2,
            height = panelHeight * 2,
            enableLayer = true,
            enableTransparent = false,
            includeGlass = false,
        )

    IMAGE_PANORAMA ->
        PanelConfigOptions(
            fractionOfScreen = 0.1f,
            width = panelWidth * 2,
            height = panelHeight * 2,
            panelShader = "data/shaders/punch/punch",
            alphaMode = AlphaMode.HOLE_PUNCH,
            includeGlass = false,
            sceneMeshCreator = { texture: SceneTexture ->
              val unlitMaterial =
                  SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
              SceneMesh.cylinderSurface(5.0f, 5.0f, 0.7f, unlitMaterial)
            },
        )

    IMAGE_360 ->
        PanelConfigOptions(
            width = panelWidth,
            height = panelHeight,
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            panelShader = "data/shaders/punch/punch",
            alphaMode = AlphaMode.HOLE_PUNCH,
            includeGlass = false,
            sceneMeshCreator = { texture: SceneTexture ->
              val unlitMaterial =
                  SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
              createCubemap(2.0f, unlitMaterial)
            },
        )

    VIDEO_360 ->
        PanelConfigOptions(
            width = panelWidth,
            height = panelHeight,
            layoutWidthInPx = layoutWidthInPx,
            layoutHeightInPx = layoutHeightInPx,
            panelShader = "data/shaders/punch/punch",
            alphaMode = AlphaMode.HOLE_PUNCH,
            includeGlass = false,
            sceneMeshCreator = { texture: SceneTexture ->
              val unlitMaterial =
                  SceneMaterial(texture, AlphaMode.OPAQUE, SceneMaterial.UNLIT_SHADER)
              createSphere(2.0f, 32, 32, unlitMaterial)
            },
        )
  }
}

fun MediaModel.maximizedBottomCenterPanelVector3(): Vector3 {
  val mediumSpacing = dpToPx(Dimens.medium.value.toInt()) * PIXELS_TO_METERS
  val immersiveMenuHeight = 0.1f
  val (_, panelHeight) = panelWidthAndHeight()
  val immersiveMenuYOffset =
      when (mediaType) {
        IMAGE_2D,
        VIDEO_2D,
        VIDEO_SPATIAL -> -panelHeight / 2 - immersiveMenuHeight / 2 - mediumSpacing

        IMAGE_PANORAMA -> -panelHeight - immersiveMenuHeight / 2 - mediumSpacing
        IMAGE_360,
        VIDEO_360,
        null -> -panelHeight
      }
  return Vector3(0.0f, immersiveMenuYOffset, 0.0f)
}

private fun dpToPx(dp: Int): Int {
  return ((dp * DEFAULT_DPI).toFloat() / 160f).toInt()
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
