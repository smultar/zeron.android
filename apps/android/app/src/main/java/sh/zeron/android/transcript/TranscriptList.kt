package sh.zeron.android.transcript

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch
import sh.zeron.android.core.StyleFonts
import sh.zeron.android.core.TextEngine
import uniffi.zeron_core.CoreClient
import uniffi.zeron_core.LayoutFrame
import uniffi.zeron_core.LayoutListener
import uniffi.zeron_core.TranscriptView
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Virtualized transcript host. Rust owns geometry: each [LayoutFrame] gives
 * exact row heights, so this view only (1) binds the rows RecyclerView
 * realizes, (2) paints them from their display lists, and (3) keeps the
 * user's place (anchor, or follow the tail).
 */
@SuppressLint("ViewConstructor")
class TranscriptList(context: Context) : RecyclerView(context), RowHost {
    val engine: TranscriptView
    override val fonts = StyleFonts()
    override var isDark: Boolean = false

    private val main = Handler(Looper.getMainLooper())
    private val scheduled = AtomicBoolean(false)
    private val density = resources.displayMetrics.density
    private var frame: LayoutFrame? = null
    private val models = object : LruCache<String, RowModel>(400) {}
    private val visuals = HashMap<String, VisualView>()
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val loading = HashSet<String>()
    private val layoutManager = LinearLayoutManager(context)
    private var pendingOpen: String? = null
    private var viewportWidth = 0f
    private var textScale = 1f

    /** Following the tail: new content keeps the bottom in view. */
    var following = true
        private set
    var onFollowChange: ((Boolean) -> Unit)? = null
    var onShowDetail: ((String, String) -> Unit)? = null
    var onOpenImage: ((String, Bitmap) -> Unit)? = null
    /** Bytes for an attachment reference (device file path); null if unavailable. */
    var imageLoader: (suspend (String) -> ByteArray?)? = null
    var scope: kotlinx.coroutines.CoroutineScope? = null

    private val relay = object : LayoutListener {
        override fun frameReady(revision: ULong) {
            if (scheduled.compareAndSet(false, true)) main.post {
                scheduled.set(false)
                applyFrame()
            }
        }
    }

    private val adapter = object : Adapter<Holder>() {
        init { setHasStableIds(true) }
        override fun getItemCount() = frame?.rowCount()?.toInt() ?: 0
        override fun getItemId(position: Int): Long = frame?.placement(position.toUInt())?.key?.toLong() ?: RecyclerView.NO_ID
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(RowView(parent.context, this@TranscriptList).also {
            it.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val f = frame ?: return
            val p = f.placement(position.toUInt()) ?: return
            holder.row.configure(model(f, position, p.key, p.version), p.kind)
        }
        override fun onViewAttachedToWindow(holder: Holder) = holder.row.visualViews().forEach { it.setActive(true) }
        override fun onViewDetachedFromWindow(holder: Holder) = holder.row.visualViews().forEach { it.setActive(false) }
    }

    class Holder(val row: RowView) : ViewHolder(row)

    init {
        engine = TranscriptView(TextEngine.shared, relay)
        layoutManager.isItemPrefetchEnabled = true
        setLayoutManager(layoutManager)
        setAdapter(adapter)
        itemAnimator = null
        setItemViewCacheSize(8)
        overScrollMode = OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = true
        clipToPadding = false
        addOnScrollListener(object : OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == SCROLL_STATE_DRAGGING) setFollowing(false)
                if (newState == SCROLL_STATE_IDLE && distanceFromBottom() < 70 * density) setFollowing(true)
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!following && dy > 0 && distanceFromBottom() < 70 * density) setFollowing(true)
            }
        })
    }

    private fun RowView.visualViews(): List<VisualView> {
        val out = ArrayList<VisualView>()
        for (i in 0 until childCount) (getChildAt(i) as? VisualView)?.let(out::add)
        return out
    }

    private fun model(f: LayoutFrame, index: Int, key: ULong, version: ULong): RowModel {
        val cacheKey = "$key:$version:${f.width()}"
        models.get(cacheKey)?.let { return it }
        val display = f.display(index.toUInt())!!
        return RowModel(display).also { models.put(cacheKey, it) }
    }

    fun attach(client: CoreClient, chatId: String, hostDeviceId: String) {
        engine.attach(client, chatId)
        engine.setVisualSource(client, hostDeviceId, maxOf(2f, density))
    }

    fun setHostDevice(client: CoreClient, deviceId: String) {
        if (deviceId.isNotEmpty()) engine.setVisualSource(client, deviceId, maxOf(2f, density))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val width = w / density
        val scale = resources.configuration.fontScale
        if (width > 0 && (width != viewportWidth || scale != textScale)) {
            viewportWidth = width
            textScale = scale
            engine.setViewport(width, scale)
        }
        if (following && h != oldh) post { scrollToTail() }
    }

    private fun applyFrame() {
        val next = engine.frame()
        if (next.width() == 0f && next.rowCount() == 0u) return
        fonts.update(next.styles())
        val first = frame == null || frame!!.rowCount() == 0u
        frame = next
        adapter.notifyDataSetChanged()
        if (following || first) post { scrollToTail() }
    }

    private fun scrollToTail() {
        val n = adapter.itemCount
        if (n == 0) return
        layoutManager.scrollToPositionWithOffset(n - 1, 0)
        post {
            scrollBy(0, Int.MAX_VALUE / 4)
            // Align the last row's bottom to the viewport bottom.
            val last = layoutManager.findViewByPosition(n - 1)
            if (last != null) {
                val over = last.bottom - (height - paddingBottom)
                if (over != 0) scrollBy(0, over)
            }
        }
    }

    fun scrollToBottom() {
        setFollowing(true)
        scrollToTail()
    }

    /** The user is sending: follow the reply as it streams. */
    fun beginOwnTurn() {
        setFollowing(true)
    }

    private fun distanceFromBottom(): Int =
        computeVerticalScrollRange() - computeVerticalScrollExtent() - computeVerticalScrollOffset()

    private fun setFollowing(value: Boolean) {
        if (following == value) return
        following = value
        onFollowChange?.invoke(value)
    }

    fun close() {
        engine.closeTranscript()
        visuals.values.forEach { it.session.detach() }
    }

    fun plainText(): String = frame?.plainText().orEmpty()

    // ---- RowHost ------------------------------------------------------------------

    override fun toggle(key: ULong) = engine.toggle(key)

    override fun toggleDetail(row: ULong, detail: ULong, open: Boolean) = engine.toggleDetail(row, detail, open)

    override fun openUrl(url: String) {
        // An image chip opens its upload full size, as its thumbnail did.
        if (url.startsWith("zeron-preview:")) {
            val ref = Uri.parse(url).getQueryParameter("ref") ?: return
            image(ref) { }?.let { onOpenImage?.invoke(ref, it) } ?: run { pendingOpen = ref }
            return
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    override fun showDetail(title: String, text: String) {
        onShowDetail?.invoke(title, text)
    }

    override fun copy(text: String) {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("code", text))
    }

    override fun visualView(id: String): View? {
        visuals[id]?.let { return it }
        val session = engine.visual(id) ?: return null
        return VisualView(context, session, ::openUrl) { isDark }.also { visuals[id] = it }
    }

    override fun image(reference: String, onReady: () -> Unit): Bitmap? {
        images.get(reference)?.let { return it }
        val load = imageLoader
        val s = scope
        if (load == null || s == null || reference.startsWith("pending://") || !loading.add(reference)) return null
        s.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bytes = try { load(reference) } catch (_: Exception) { null }
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            main.post {
                loading.remove(reference)
                if (bmp != null) {
                    images.put(reference, bmp)
                    onReady()
                    adapter.notifyDataSetChanged()
                    if (pendingOpen == reference) {
                        pendingOpen = null
                        onOpenImage?.invoke(reference, bmp)
                    }
                }
            }
        }
        return null
    }

    override fun openImage(reference: String, bitmap: Bitmap) {
        onOpenImage?.invoke(reference, bitmap)
    }

    fun setDarkMode(dark: Boolean) {
        if (isDark == dark) return
        isDark = dark
        visuals.values.forEach { it.setDark(dark) }
        adapter.notifyDataSetChanged()
    }
}
