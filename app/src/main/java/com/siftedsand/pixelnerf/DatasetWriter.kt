package com.siftedsand.pixelnerf

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class DatasetWriter(private val sessionDir: File) : Closeable {
    private val imagesDir = File(sessionDir, "images")
    private val depthDir = File(sessionDir, "depth_raw")
    private val confidenceDir = File(sessionDir, "confidence")
    private val posesFile = File(sessionDir, "poses.jsonl")

    init {
        imagesDir.mkdirs()
        depthDir.mkdirs()
        confidenceDir.mkdirs()
        posesFile.parentFile?.mkdirs()
        posesFile.writeText("")
    }

    fun writeSessionMetadata(deviceModel: String) {
        val meta = JSONObject()
            .put("format", "pixelnerf-arcore-v0")
            .put("device_model", deviceModel)
            .put("created_unix_ms", System.currentTimeMillis())
            .put("notes", "ARCore camera poses are OpenGL-style camera-to-world matrices. Coordinate conversion may be required for your trainer.")
        File(sessionDir, "session.json").writeText(meta.toString(2))
    }

    fun writeFrame(
        index: Int,
        timestampNs: Long,
        cameraImage: Image,
        poseMatrix: FloatArray,
        fx: Float,
        fy: Float,
        cx: Float,
        cy: Float,
        width: Int,
        height: Int,
        depthPath: String?,
        confidencePath: String?
    ) {
        val imageRelPath = "images/frame_%06d.jpg".format(index)
        val imageFile = File(sessionDir, imageRelPath)
        writeCameraImageJpeg(cameraImage, imageFile)

        val poseJson = JSONArray()
        poseMatrix.forEach { poseJson.put(it.toDouble()) }

        val record = JSONObject()
            .put("index", index)
            .put("timestamp_ns", timestampNs)
            .put("file_path", imageRelPath)
            .put("transform_matrix_row_major_4x4", poseJson)
            .put("intrinsics", JSONObject()
                .put("fx", fx.toDouble())
                .put("fy", fy.toDouble())
                .put("cx", cx.toDouble())
                .put("cy", cy.toDouble())
                .put("width", width)
                .put("height", height)
            )
        if (depthPath != null) record.put("raw_depth_path", depthPath)
        if (confidencePath != null) record.put("raw_depth_confidence_path", confidencePath)

        posesFile.appendText(record.toString() + "\n")
    }

    fun writeRawPlane(relativePath: String, image: Image): String {
        val outFile = File(sessionDir, relativePath)
        outFile.parentFile?.mkdirs()
        val plane = image.planes[0]
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        FileOutputStream(outFile).use { it.write(bytes) }
        return relativePath
    }

    private fun writeCameraImageJpeg(image: Image, outFile: File) {
        require(image.format == ImageFormat.YUV_420_888) {
            "Expected YUV_420_888, got ${image.format}"
        }
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val jpegStream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, jpegStream)
        outFile.outputStream().use { it.write(jpegStream.toByteArray()) }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 2
        val out = ByteArray(ySize + uvSize)

        copyPlaneToArray(image.planes[0].buffer, image.planes[0].rowStride, image.planes[0].pixelStride, width, height, out, 0, 1)

        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var outputOffset = ySize
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()

        for (row in 0 until chromaHeight) {
            for (col in 0 until chromaWidth) {
                val vuIndex = row * vPlane.rowStride + col * vPlane.pixelStride
                val uuIndex = row * uPlane.rowStride + col * uPlane.pixelStride
                out[outputOffset++] = vBuffer.get(vuIndex)
                out[outputOffset++] = uBuffer.get(uuIndex)
            }
        }
        return out
    }

    private fun copyPlaneToArray(
        buffer: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        offset: Int,
        outputPixelStride: Int
    ) {
        val dup = buffer.duplicate()
        var outputOffset = offset
        for (row in 0 until height) {
            for (col in 0 until width) {
                out[outputOffset] = dup.get(row * rowStride + col * pixelStride)
                outputOffset += outputPixelStride
            }
        }
    }

    override fun close() {
        // Files are written per frame; no long-lived stream currently.
    }
}
