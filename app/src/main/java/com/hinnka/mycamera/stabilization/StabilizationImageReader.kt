package com.hinnka.mycamera.stabilization

import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import com.hinnka.mycamera.utils.PLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Camera YUV input for photo preview and EIS. Supports latest-frame display or FIFO stabilization,
 * retaining the reader until every acquired frame, including renderer leases, has been released.
 */
internal class StabilizationImageReader(
    val width: Int,
    val height: Int,
    private val maxImages: Int,
) : AutoCloseable {
    private val lock = Any()
    private val reader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, maxImages)
    val surface = reader.surface
    private var acquiredImageCount = 0
    private var availabilityHandler: Handler? = null
    private var availabilityListener: ((StabilizationImageReader) -> Unit)? = null

    @Volatile
    var isClosed = false
        private set

    fun setOnImageAvailableListener(handler: Handler, listener: (StabilizationImageReader) -> Unit) {
        synchronized(lock) {
            check(!isClosed)
            availabilityHandler = handler
            availabilityListener = listener
            reader.setOnImageAvailableListener({ listener(this) }, handler)
        }
    }

    fun acquireNextImage(): AcquiredImage? = synchronized(lock) {
        // A callback queued before retirement must not acquire another frame.
        if (isClosed || acquiredImageCount >= maxImages) return@synchronized null
        val image = reader.acquireNextImage() ?: return@synchronized null
        acquiredImageCount += 1
        AcquiredImage(this, image)
    }

    /**
     * Discard camera buffers that have not yet been acquired. This is used after a physical
     * sensor handoff so old-lens images cannot become boundaries in the new EIS timeline.
     * Call on the ImageReader listener's handler to serialize with its availability callback.
     */
    fun discardPendingImages(): Int = synchronized(lock) {
        if (isClosed) return@synchronized 0
        var discarded = 0
        while (true) {
            val image = try {
                reader.acquireNextImage()
            } catch (error: IllegalStateException) {
                PLog.w(
                    "StabilizationImageReader",
                    "Could not drain all pending buffers: ${error.message}",
                )
                null
            } ?: break
            image.close()
            discarded += 1
        }
        if (discarded > 0) {
            PLog.d(
                "StabilizationImageReader",
                "Discarded $discarded queued frame(s) from ${width}x$height reader",
            )
        }
        discarded
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            availabilityHandler = null
            availabilityListener = null
            reader.setOnImageAvailableListener(null, null)
            if (acquiredImageCount == 0) {
                reader.close()
            } else {
                PLog.d("StabilizationImageReader", "Retiring ${width}x$height reader with $acquiredImageCount acquired images")
            }
        }
    }

    private fun releaseImage(image: Image) {
        synchronized(lock) {
            val wasAtCapacity = acquiredImageCount == maxImages
            image.close()
            acquiredImageCount -= 1
            if (isClosed && acquiredImageCount == 0) {
                reader.close()
                PLog.d("StabilizationImageReader", "Closed retired ${width}x$height reader after its last image")
            } else if (!isClosed && wasAtCapacity) {
                // FIFO consumers can hold every lease. Once one is returned, drain images
                // already queued in ImageReader even if no new availability callback arrives.
                availabilityHandler?.post {
                    val listener = synchronized(lock) { availabilityListener }
                    listener?.invoke(this)
                }
            }
        }
    }

    private class SharedImage(
        val owner: StabilizationImageReader,
        val image: Image,
    ) {
        val references = AtomicInteger(1)
    }

    class AcquiredImage private constructor(private val shared: SharedImage) : AutoCloseable {
        internal constructor(owner: StabilizationImageReader, image: Image) : this(SharedImage(owner, image))

        val owner get() = shared.owner
        val image get() = shared.image
        val timestampNs = image.timestamp
        val width = image.width
        val height = image.height
        private val closed = AtomicBoolean(false)

        fun retain(): AcquiredImage {
            check(!closed.get()) { "Cannot retain a closed YUV lease" }
            while (true) {
                val count = shared.references.get()
                check(count > 0) { "YUV image has already been released" }
                if (shared.references.compareAndSet(count, count + 1)) return AcquiredImage(shared)
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true) && shared.references.decrementAndGet() == 0) {
                owner.releaseImage(image)
            }
        }
    }
}
