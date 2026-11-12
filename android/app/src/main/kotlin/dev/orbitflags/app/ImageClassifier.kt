package dev.orbitflags.app

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * On-device adult-image classifier: the NSFWJS MobileNetV2 model (MIT),
 * converted to TFLite by tools/model/convert_model.py. Input is a 224x224 RGB
 * image scaled to 0..1; output is five probabilities in the order
 * drawing, hentai, neutral, porn, sexy.
 */
class ImageClassifier(context: Context) {
    private val interpreter: Interpreter
    private val input: ByteBuffer = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val pixels = IntArray(SIZE * SIZE)
    private val output = Array(1) { FloatArray(5) }

    init {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(2))
    }

    /** True if the picture is likely to show adult content. */
    fun isAdult(picture: Bitmap): Boolean {
        val scaled = Bitmap.createScaledBitmap(picture, SIZE, SIZE, true)
        scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        if (scaled !== picture) scaled.recycle()
        input.rewind()
        for (p in pixels) {
            input.putFloat(((p shr 16) and 0xFF) / 255f)
            input.putFloat(((p shr 8) and 0xFF) / 255f)
            input.putFloat((p and 0xFF) / 255f)
        }
        input.rewind()
        interpreter.run(input, output)
        val scores = output[0]
        return scores[HENTAI] + scores[PORN] >= EXPLICIT_THRESHOLD || scores[SEXY] >= SUGGESTIVE_THRESHOLD
    }

    fun close() = interpreter.close()

    private companion object {
        const val MODEL = "nsfw_mobilenet_v2.tflite"
        const val SIZE = 224
        const val HENTAI = 1
        const val PORN = 3
        const val SEXY = 4
        const val EXPLICIT_THRESHOLD = 0.7f
        const val SUGGESTIVE_THRESHOLD = 0.85f
    }
}
