package com.soywiz.korim.bitmap

import com.soywiz.kmem.*
import com.soywiz.korim.color.*
import com.soywiz.korim.vector.*
import com.soywiz.korma.geom.*
import kotlin.js.*
import kotlin.math.*

class Bitmap32(
    width: Int,
    height: Int,
    val data: RgbaArray = RgbaArray(width * height),
    premultiplied: Boolean = false
) : Bitmap(width, height, 32, premultiplied, data), Iterable<RGBA> {
	init {
		if (data.size < width * height) throw RuntimeException("Bitmap data is too short: width=$width, height=$height, data=ByteArray(${data.size}), area=${width * height}")
	}

	private val temp = RgbaArray(max(width, height))
    val bounds: IRectangleInt = RectangleInt(0, 0, width, height)

	constructor(width: Int, height: Int, value: RGBA, premultiplied: Boolean) : this(width, height, premultiplied = premultiplied) { data.fill(value) }
	constructor(width: Int, height: Int, premultiplied: Boolean = false, generator: (x: Int, y: Int) -> RGBA) : this(width, height, premultiplied = premultiplied) { setEach(callback = generator) }

	override fun createWithThisFormat(width: Int, height: Int): Bitmap = Bitmap32(width, height, premultiplied = premultiplied)

	override fun copy(srcX: Int, srcY: Int, dst: Bitmap, dstX: Int, dstY: Int, width: Int, height: Int) {
		val src = this

		val srcArray = src.data
		var srcIndex = src.index(srcX, srcY)
		val srcAdd = src.width

		val dstArray = (dst as Bitmap32).data
		var dstIndex = dst.index(dstX, dstY)
		val dstAdd = dst.width

		for (y in 0 until height) {
			arraycopy(srcArray.ints, srcIndex, dstArray.ints, dstIndex, width)
			srcIndex += srcAdd
			dstIndex += dstAdd
		}
	}

    operator fun set(x: Int, y: Int, color: RGBA) = run { data[index(x, y)] = color }
	operator fun get(x: Int, y: Int): RGBA = data[index(x, y)]

	override fun setInt(x: Int, y: Int, color: Int) = run { data[index(x, y)] = RGBA(color) }
	override fun getInt(x: Int, y: Int): Int = data.ints[index(x, y)]

    override fun getRgba(x: Int, y: Int): RGBA = data[index(x, y)]
	override fun setRgba(x: Int, y: Int, v: RGBA): Unit = run { data[index(x, y)] = v }

	fun setRow(y: Int, row: IntArray) {
		arraycopy(row, 0, data.ints, index(0, y), width)
	}

	fun _draw(src: Bitmap32, dx: Int, dy: Int, sleft: Int, stop: Int, sright: Int, sbottom: Int, mix: Boolean) {
		val dst = this
		val width = sright - sleft
		val height = sbottom - stop
		val dstData = dst.data
		val srcData = src.data
		for (y in 0 until height) {
			val dstOffset = dst.index(dx, dy + y)
			val srcOffset = src.index(sleft, stop + y)
			if (mix) {
				for (x in 0 until width) dstData[dstOffset + x] = dstData[dstOffset + x] mix srcData[srcOffset + x]
			} else {
				arraycopy(srcData, srcOffset, dstData, dstOffset, width)
			}
		}
	}

	fun drawPixelMixed(x: Int, y: Int, c: RGBA) {
		this[x, y] = RGBA.mix(this[x, y], c)
	}

	fun _drawPut(mix: Boolean, other: Bitmap32, _dx: Int = 0, _dy: Int = 0) {
		var dx = _dx
		var dy = _dy
		var sleft = 0
		var stop = 0
		val sright = other.width
		val sbottom = other.height
		if (dx < 0) {
			sleft = -dx
			//sright += dx
			dx = 0
		}
		if (dy < 0) {
			stop = -dy
			//sbottom += dy
			dy = 0
		}

		_draw(other, dx, dy, sleft, stop, sright, sbottom, mix)
	}

    fun historiogram(channel: BitmapChannel, out: IntArray = IntArray(256)): IntArray {
        check(out.size >= 256) { "output array size must be 256" }
        out.fill(0)
        forEach { n, _, _ -> out[channel.extract(data[n])]++ }
        return out
    }

	fun fill(color: RGBA, x: Int = 0, y: Int = 0, width: Int = this.width - x, height: Int = this.height - y) {
		val x1 = clampX(x)
		val x2 = clampX(x + width - 1)
		val y1 = clampY(y)
		val y2 = clampY(y + height - 1)
		for (cy in y1..y2) this.data.fill(color, index(x1, cy), index(x2, cy) + 1)
	}

	fun _draw(src: BitmapSlice<Bitmap32>, dx: Int = 0, dy: Int = 0, mix: Boolean) {
		val b = src.bounds

		val availableWidth = width - dx
		val availableHeight = height - dy

		val awidth = kotlin.math.min(availableWidth, b.width)
		val aheight = kotlin.math.min(availableHeight, b.height)

		_draw(src.bmp, dx, dy, b.x, b.y, b.x + awidth, b.y + aheight, mix = mix)
	}

	fun put(src: Bitmap32, dx: Int = 0, dy: Int = 0) = _drawPut(false, src, dx, dy)
	fun draw(src: Bitmap32, dx: Int = 0, dy: Int = 0) = _drawPut(true, src, dx, dy)

	fun put(src: BitmapSlice<Bitmap32>, dx: Int = 0, dy: Int = 0) = _draw(src, dx, dy, mix = false)
	fun draw(src: BitmapSlice<Bitmap32>, dx: Int = 0, dy: Int = 0) = _draw(src, dx, dy, mix = true)

	fun drawUnoptimized(src: BitmapSlice<Bitmap>, dx: Int = 0, dy: Int = 0, mix: Boolean = true) {
		if (src.bmp is Bitmap32) {
			_draw(src as BitmapSlice<Bitmap32>, dx, dy, mix = mix)
		} else {
			drawUnoptimized(src.bmp, dx, dy, src.left, src.top, src.right, src.bottom, mix = mix)
		}
	}

	fun drawUnoptimized(src: Bitmap, dx: Int, dy: Int, sleft: Int, stop: Int, sright: Int, sbottom: Int, mix: Boolean) {
		val dst = this
		val width = sright - sleft
		val height = sbottom - stop
		val dstData = dst.data
		for (y in 0 until height) {
			val dstOffset = dst.index(dx, dy + y)
			if (mix) {
				for (x in 0 until width) dstData[dstOffset + x] = RGBA.mix(dstData[dstOffset + x], src.getRgba(sleft + x, stop + y))
			} else {
				for (x in 0 until width) dstData[dstOffset + x] = src.getRgba(sleft + x, stop + y)
			}
		}
	}

	fun copySliceWithBounds(left: Int, top: Int, right: Int, bottom: Int): Bitmap32 =
		copySliceWithSize(left, top, right - left, bottom - top)

	fun copySliceWithSize(x: Int, y: Int, width: Int, height: Int): Bitmap32 = Bitmap32(width, height).also { out ->
        for (yy in 0 until height) {
            arraycopy(this.data, this.index(x, y + yy), out.data, out.index(0, yy), width)
        }
    }

	inline fun all(callback: (RGBA) -> Boolean): Boolean = (0 until area).any { callback(data[it]) }

	inline fun setEach(sx: Int = 0, sy: Int = 0, width: Int = this.width - sx, height: Int = this.height - sy, callback: (x: Int, y: Int) -> RGBA) = forEach(sx, sy, width, height) { n, x, y -> this.data[n] = callback(x, y) }
	inline fun updateColors(sx: Int = 0, sy: Int = 0, width: Int = this.width - sx, height: Int = this.height - sy, callback: (rgba: RGBA) -> RGBA) = forEach(sx, sy, width, height) { n, x, y -> this.data[n] = callback(this.data[n]) }
    inline fun updateColorsXY(sx: Int = 0, sy: Int = 0, width: Int = this.width - sx, height: Int = this.height - sy, callback: (x: Int, y: Int, rgba: RGBA) -> RGBA) = forEach(sx, sy, width, height) { n, x, y -> this.data[n] = callback(x, y, this.data[n]) }

	fun writeChannel(destination: BitmapChannel, input: Bitmap32, source: BitmapChannel) = Bitmap32.copyChannel(input, source, this, destination)
	fun writeChannel(destination: BitmapChannel, input: Bitmap8) = Bitmap32.copyChannel(input, this, destination)
	fun extractChannel(channel: BitmapChannel): Bitmap8 = Bitmap8(width, height).also { Bitmap32.copyChannel(this, channel, it) }

	fun invert() = xor(RGBA(255, 255, 255, 0))
	fun xor(value: RGBA) = updateColors { RGBA(it.value xor value.value) }

	override fun toString(): String = "Bitmap32($width, $height)"

	override fun swapRows(y0: Int, y1: Int) {
		val s0 = index(0, y0)
		val s1 = index(0, y1)
		arraycopy(data, s0, temp, 0, width)
		arraycopy(data, s1, data, s0, width)
		arraycopy(temp, 0, data, s1, width)
	}

	fun writeDecoded(color: ColorFormat, data: ByteArray, offset: Int = 0, littleEndian: Boolean = true): Bitmap32 =
		this.apply {
			color.decode(data, offset, this.data, 0, this.area, littleEndian = littleEndian)
		}

    fun clone() = Bitmap32(width, height, RgbaArray(this.data.ints.copyOf()), premultiplied)

	override fun getContext2d(antialiasing: Boolean): Context2d = Context2d(Bitmap32Context2d(this, antialiasing))

	fun premultipliedIfRequired(): Bitmap32 = if (this.premultiplied) this else premultiplied()
	fun depremultipliedIfRequired(): Bitmap32 = if (!this.premultiplied) this else depremultiplied()

    @JsName("copyPremultiplied")
	fun premultiplied(): Bitmap32 = this.clone().apply { premultiplyInplace() }
	fun depremultiplied(): Bitmap32 = this.clone().apply { depremultiplyInplace() }

	fun premultiplyInplace() {
		if (premultiplied) return
		premultiplied = true
        updateColors { it.premultiplied.asNonPremultiplied() }
	}

	fun depremultiplyInplace() {
		if (!premultiplied) return
		premultiplied = false
        updateColors { it.asPremultiplied().depremultiplied }
	}

	fun withColorTransform(ct: ColorTransform, x: Int = 0, y: Int = 0, width: Int = this.width - x, height: Int = this.height - y): Bitmap32
        = extract(x, y, width, height).apply { applyColorTransform(ct) }

	fun applyColorTransform(ct: ColorTransform, x: Int = 0, y: Int = 0, width: Int = this.width - x, height: Int = this.height - y) {
		val R = IntArray(256) { ((it * ct.mR) + ct.aR).toInt().clamp(0x00, 0xFF) }
		val G = IntArray(256) { ((it * ct.mG) + ct.aG).toInt().clamp(0x00, 0xFF) }
		val B = IntArray(256) { ((it * ct.mB) + ct.aB).toInt().clamp(0x00, 0xFF) }
		val A = IntArray(256) { ((it * ct.mA) + ct.aA).toInt().clamp(0x00, 0xFF) }
        updateColors(x, y, width, height) { RGBA(R[it.r], G[it.g], B[it.b], A[it.a]) }
	}

	fun mipmap(levels: Int): Bitmap32 {
		val temp = this.clone()
		temp.premultiplyInplace()
		val dst = temp.data.asPremultiplied()

		var twidth = width
		var theight = height

		for (level in 0 until levels) {
			twidth /= 2
			theight /= 2
			for (y in 0 until theight) {
				var n = temp.index(0, y)
				var m = temp.index(0, y * 2)

				for (x in 0 until twidth) {
					dst[n] = RGBAPremultiplied.blend(dst[m + 0], dst[m + 1], dst[m + width + 0], dst[m + width + 1])
					m += 2
					n++
				}
			}
		}
        return temp.copySliceWithSize(0, 0, twidth, theight)
	}

	override fun iterator(): Iterator<RGBA> = data.iterator()

	fun setRowChunk(x: Int, y: Int, data: RgbaArray, width: Int, increment: Int) {
		if (increment == 1) {
			arraycopy(data, 0, this.data, index(x, y), width)
		} else {
			var m = index(x, y)
			for (n in 0 until width) {
				this.data.ints[m] = data.ints[n]
				m += increment
			}
		}
	}

	fun extractBytes(format: ColorFormat = RGBA): ByteArray = format.encode(data)

    //fun scroll(sx: Int, sy: Int) {
    //    scrollX(sx)
    //    scrollY(sy)
    //}
    //
    //private fun scrollX(sx: Int) {
    //    val displacement = sx umod width
    //    if (displacement == 0) return
    //    for (y in 0 until height) {
    //        arraycopy(this.data.ints, index(0, y), temp.ints, 0, width)
    //        arraycopy(temp.ints, 0, this.data.ints, index(0, y), displacement)
    //        arraycopy(temp.ints, displacement, this.data.ints, index(displacement, y), width - displacement)
    //    }
    //}
    //
    //private fun scrollY(sy: Int) {
    //    arraycopy(this.data.ints, 0, temp.ints, 0, width)
    //    for (y in 0 until height - 1) {
    //
    //    }
    //}

    fun scaleNearest(sx: Int, sy: Int): Bitmap32 = Bitmap32(width * sx, height * sy).apply { setEach { x, y -> this@Bitmap32[x / sx, y / sy] } }
    fun scaleLinear(sx: Double, sy: Double): Bitmap32 = Bitmap32((width * sx).toInt(), (height * sy).toInt()).apply { setEach { x, y -> this@Bitmap32.getRgbaSampled(x / sx, y / sy) } }

	fun rgbaToYCbCr(): Bitmap32 = clone().apply { rgbaToYCbCrInline() }
    fun rgbaToYCbCrInline() = updateColors { RGBA(it.toYCbCr().value) }

	fun yCbCrToRgba(): Bitmap32 = clone().apply { yCbCrToRgbaInline() }
    fun yCbCrToRgbaInline() = updateColors { YCbCr(it.value).toRGBA() }

    override fun equals(other: Any?): Boolean = (other is Bitmap32) && (this.width == other.width) && (this.height == other.height) && data.ints.contentEquals(other.data.ints)
    override fun hashCode(): Int = (width * 31 + height) + data.ints.contentHashCode() + premultiplied.toInt()

    companion object {
        operator fun invoke(width: Int, height: Int, premultiplied: Boolean = false, generator: (x: Int, y: Int) -> RGBA): Bitmap32 {
            return Bitmap32(width, height, RgbaArray(width * height).also {
                var n = 0
                for (y in 0 until height) for (x in 0 until width) it[n++] = generator(x, y)
            }, premultiplied)
        }

        fun copyChannel(
            src: Bitmap32,
            srcChannel: BitmapChannel,
            dst: Bitmap32,
            dstChannel: BitmapChannel
        ) {
            val srcShift = srcChannel.shift
            val dstShift = dstChannel.shift
            val dstClear = dstChannel.clearMask
            val dstData = dst.data
            val srcData = src.data
            for (n in 0 until dst.area) {
                val c = (srcData.ints[n] ushr srcShift) and 0xFF
                dstData[n] = RGBA((dstData.ints[n] and dstClear) or (c shl dstShift))
            }

        }

        fun copyChannel(
            src: Bitmap8,
            dst: Bitmap32,
            dstChannel: BitmapChannel
        ) {
            val destShift = dstChannel.index * 8
            val destClear = (0xFF shl destShift).inv()
            for (n in 0 until dst.area) {
                val c = src.data[n].toInt() and 0xFF
                dst.data[n] = RGBA((dst.data.ints[n] and destClear) or (c shl destShift))
            }
        }

        fun copyChannel(
            src: Bitmap32,
            srcChannel: BitmapChannel,
            dst: Bitmap8
        ) {
            val shift = srcChannel.shift
            for (n in 0 until src.area) {
                dst.data[n] = ((src.data.ints[n] ushr shift) and 0xFF).toByte()
            }
        }

        fun copyRect(
            src: Bitmap32,
            srcX: Int,
            srcY: Int,
            dst: Bitmap32,
            dstX: Int,
            dstY: Int,
            width: Int,
            height: Int
        ) {
            for (y in 0 until height) {
                val srcIndex = src.index(srcX, srcY + y)
                val dstIndex = dst.index(dstX, dstY + y)
                arraycopy(src.data, srcIndex, dst.data, dstIndex, width)
            }
        }

        fun createWithAlpha(
            color: Bitmap32,
            alpha: Bitmap32,
            alphaChannel: BitmapChannel = BitmapChannel.RED
        ): Bitmap32 = Bitmap32(color.width, color.height).also { out ->
            out.put(color)
            Bitmap32.copyChannel(alpha, BitmapChannel.RED, out, BitmapChannel.ALPHA)
        }

        // https://en.wikipedia.org/wiki/Structural_similarity
        suspend fun matchesSSMI(a: Bitmap, b: Bitmap): Boolean = TODO()

        suspend fun matches(a: Bitmap, b: Bitmap, threshold: Int = 32): Boolean {
            val diff = diff(a, b)
            //for (c in diff.data) println("%02X, %02X, %02X".format(RGBA.getR(c), RGBA.getG(c), RGBA.getB(c)))
            return diff.data.all {
                (it.r < threshold) && (it.g < threshold) && (it.b < threshold) && (it.a < threshold)
            }
        }

        fun diff(a: Bitmap, b: Bitmap): Bitmap32 {
            if (a.width != b.width || a.height != b.height) throw IllegalArgumentException("$a not matches $b size")
            val a32 = a.toBMP32()
            val b32 = b.toBMP32()
            val out = Bitmap32(a.width, a.height, premultiplied = true)
            //showImageAndWait(a32)
            //showImageAndWait(b32)
            for (n in 0 until out.area) {
                val c1 = a32.data[n].premultiplied
                val c2 = b32.data[n].premultiplied

                //println("%02X, %02X, %02X".format(RGBA.getR(c1), RGBA.getR(c2), dr))
                out.data[n] = RGBA(abs(c1.r - c2.r), abs(c1.g - c2.g), abs(c1.b - c2.b), abs(c1.a - c2.a))

                //println("$dr, $dg, $db, $da : ${out.data[n]}")
            }
            //showImageAndWait(out)
            return out
        }
    }

    override fun toBMP32(): Bitmap32 = this
}
