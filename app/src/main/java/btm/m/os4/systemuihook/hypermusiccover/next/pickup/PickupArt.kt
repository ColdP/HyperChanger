// Imported from HyperMusicCover snapshot 2026-10-30; adapted for HyperChanger.
// Original licenses/notices: see NOTICE and docs/hypermusiccover-source-manifest.json.
package btm.m.os4.systemuihook.hypermusiccover.next.pickup
import btm.m.os4.systemuihook.BuildConfig
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayInputStream

/**
 * The card's artwork: ColorOS's own 103 files (`applogo/`, `stickers/`, `base_bg_*.webp`,
 * `aod_static_*.webp`), shipped in the apk under `assets/coloros/pickupcode/`.
 *
 * They were fetched from a release for a while, and it worked - with a GitHub proxy in front,
 * because the direct URL from this network dies mid-download. That is a lot of machinery between
 * a user and a picture, and the pictures are 6.6MB that every device needs anyway: bundling costs
 * the apk 6.6MB once and costs the user nothing, which is the better trade for a card whose left
 * half is a picture.
 *
 * Every file is optional. A brand with no picture draws the card without it - the code is what
 * the island is for, and it is drawn from the layout, not from the artwork.
 */
internal object PickupArt {

    private const val ROOT = "coloros/pickupcode/"

    /**
     * What to draw for this brand, straight out of the rules XML: ColorOS names each brand's
     * logo, sticker, drink picture and always-on picture there (`app_logo`, `stickers`,
     * `base_bg_style`, `aod_static_image`), and the same file names ColorOS uses are the ones the
     * artwork ships. A null rule - no brand matched, which is the case for a mini program ColorOS
     * does not list either - means no picture, and the code shows alone.
     */
    fun logo(rule: PickupRule?): String? = rule?.logo?.takeIf { it.isNotEmpty() }

    /** This brand's sticker. Five of the brands ship a logo and no sticker; then there is none. */
    fun sticker(rule: PickupRule?): String? = rule?.sticker?.takeIf { it.isNotEmpty() }

    /**
     * The drink picture: the one the brand names, else one picked from what is being ordered - the
     * order's own drink decides its colour, which is what ColorOS does when a brand has no picture
     * of its own (IslandPublisher.modelAsset in the reference implementation).
     *
     * A brand with no rule gets a picture too. The table below is keyed by the drink's own name
     * rather than by the brand, and its config ends in a `common` picture per category
     * (`common_style_cold` / `common_style_hot`), so an order the config does not list is drawn
     * with the cup its drink asked for when the page names one - 2026-10-09, 蜜雪冰城's Alipay page
     * reads 「蓝莓果粒茶」 and comes out purple - and with the plain one when it does not.
     *
     * With no rule there is no category either, and only coffee is worth telling apart: the takeout
     * brands in this config all have rules of their own, and a bag drawn for a milk tea would be
     * worse than a cup.
     */
    fun model(rule: PickupRule?, brand: String, product: String?, temperature: String? = null): String {
        val text = "$brand ${product.orEmpty()}"
        val hot = "hot".equals(temperature?.trim(), ignoreCase = true)
        if (rule != null && rule.baseStyle.isNotEmpty()) return rule.baseStyle
        return when (rule?.category ?: categoryOf(text)) {
            "coffee" -> coffeeStyle(text, hot)
            "catering" -> cateringStyle(text)
            else -> teaStyle(text, hot)
        }
    }

    /** Coffee is recognised by the drink's own name; everything else here is a tea. */
    private fun categoryOf(text: String): String =
        if (containsAny(text, "美式", "拿铁", "摩卡", "生椰", "卡布奇诺", "馥芮白", "焦糖", "星冰乐",
                "咖啡")) "coffee" else "tea"

    /** The same picture for the always-on display, which ColorOS ships as a still of it. */
    fun aod(rule: PickupRule?, model: String?): String? {
        if (rule != null && rule.aodImage.isNotEmpty()) return rule.aodImage
        return model
            ?.replace("base_bg_tea_style_", "aod_static_tea_")
            ?.replace("base_bg_coffee_style_", "aod_static_coffee_")
            ?.replace("base_bg_takeout_style_", "aod_static_takeout_")
    }

    /**
     * The drink pictures are drawable resources rather than assets, which is the point: ColorOS
     * ships them as **animated** WebP, and a drawable reached by resource id is one the system can
     * play. Handed over as a bitmap instead - which is all [bitmap] can produce, and all a
     * RemoteViews could carry before this - the card showed the animation's first frame, and that
     * frame is half a cup.
     */
    fun drawable(c: Context, file: String): Int {
        if (file.isEmpty()) return 0
        return try {
            // Through this module's own context, not SystemUI's: the Resources SystemUI holds were
            // built from its own apk, and asking them about a drawable in ours answers 0 - the
            // package is simply not in that AssetManager. This is the same turn RemoteViews makes
            // when it inflates a layout of ours, and it is why the card drew a sticker and no
            // drink until it was written this way.
            val own = c.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            own.resources.getIdentifier(file.substringBeforeLast('.'), "drawable",
                BuildConfig.APPLICATION_ID)
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * How long the animation at [id] runs, in milliseconds: the sum of its WebP frame durations, or
     * 0 for a still picture or anything unreadable. ColorOS's card needs this to know when the drink
     * has finished filling - its own `LevelDView` fades the brand's logo in over the cup once the
     * animation has run - and the artwork differs by brand: the 53-frame drinks run 1749ms, the
     * 20-frame ones 600ms, so one fixed delay would be wrong for most of them.
     */
    fun duration(c: Context, id: Int): Int {
        if (id == 0) return 0
        return try {
            val own = c.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            val bytes = own.resources.openRawResource(id).use { it.readBytes() }
            var total = 0
            var i = -1
            while (true) {
                i = indexOf(bytes, ANMF, i + 1)
                if (i < 0) break
                // ANMF: 'ANMF' size(4) frameX(3) frameY(3) width(3) height(3) duration(3) flags(1)
                val at = i + 20
                if (at + 3 > bytes.size) break
                total += (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16)
            }
            total
        } catch (_: Throwable) {
            0
        }
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
        var i = if (from < 0) 0 else from
        outer@ while (i <= hay.size - needle.size) {
            var j = 0
            while (j < needle.size) {
                if (hay[i + j] != needle[j]) { i++; continue@outer }
                j++
            }
            return i
        }
        return -1
    }

    /** The chunk every animation frame of a WebP lives in. */
    private val ANMF = byteArrayOf(0x41, 0x4E, 0x4D, 0x46)

    /**
     * A picture, decoded down to what the slot can show. Two passes: once for the size, once for
     * the pixels at the right sample.
     */
    fun bitmap(name: String, width: Int): Bitmap? {
        if (name.isEmpty() || name.contains("..") || name.startsWith("/")) return null
        val bytes = try {
            PickupOem.asset(ROOT + name).use { it.readBytes() }
        } catch (error: Throwable) {
            return null
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
            if (bounds.outWidth <= 0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= width) sample *= 2
            BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null,
                BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (error: Throwable) {
            null
        }
    }

    private fun containsAny(text: String, vararg words: String) = words.any { text.contains(it) }

    /**
     * [hot] only chooses between the two `common` pictures, which is where ColorOS puts it too: the
     * keyword styles are all `drink_temperature="cold"` in the config, so a named drink keeps its
     * own colour whether it is iced or not, and only an unnamed one has to fall back on the
     * temperature it was ordered at.
     */
    private fun teaStyle(text: String, hot: Boolean): String = when {
        containsAny(text, "杨枝甘露", "芒果", "鲜橙", "胡萝卜", "芒芒") -> "base_bg_tea_style_orange.webp"
        containsAny(text, "草莓", "红豆", "西瓜", "杨梅", "莓莓", "蔓越莓") -> "base_bg_tea_style_red.webp"
        containsAny(text, "青提", "抹茶", "猕猴桃", "牛油果", "青柠", "青芒",
            "甘蓝", "薄荷", "黄瓜", "青苹果", "玉菇", "羽衣") -> "base_bg_tea_style_green.webp"
        containsAny(text, "柠檬", "百香果", "百香", "凤梨", "菠萝") -> "base_bg_tea_style_yellow.webp"
        containsAny(text, "奶茶") -> "base_bg_tea_style_milk.webp"
        containsAny(text, "蜜桃", "桃桃", "鲜桃", "芭乐") -> "base_bg_tea_style_peach.webp"
        containsAny(text, "葡萄", "蓝莓", "黑加仑", "紫薯", "火龙果") -> "base_bg_tea_style_grap.webp"
        containsAny(text, "乌龙", "红袍") -> "base_bg_tea_style_oolong.webp"
        hot -> "base_bg_tea_style_common_hot.webp"
        else -> "base_bg_tea_style_common_cold.webp"
    }

    private fun coffeeStyle(text: String, hot: Boolean): String = when {
        containsAny(text, "星冰乐") -> "base_bg_coffee_style_xingbake_xingbingle.webp"
        containsAny(text, "拿铁", "摩卡", "生椰") -> "base_bg_coffee_style_latte.webp"
        containsAny(text, "馥芮白", "焦糖", "卡布奇诺") -> "base_bg_coffee_style_milk.webp"
        containsAny(text, "星巴克") && !containsAny(text, "冰", "冷") ->
            "base_bg_coffee_style_xingbake_hot.webp"
        hot -> "base_bg_coffee_style_common_hot.webp"
        else -> "base_bg_coffee_style_common_cold.webp"
    }

    private fun cateringStyle(text: String): String = when {
        containsAny(text, "肯德基", "KFC") -> "base_bg_takeout_style_kendeji.webp"
        containsAny(text, "麦当劳") -> "base_bg_takeout_style_maidanglao.webp"
        containsAny(text, "塔斯汀") -> "base_bg_takeout_style_tustin.webp"
        else -> "base_bg_takeout_style_common.webp"
    }
}
