package sh.zeron.android

import android.app.Application
import sh.zeron.android.app.AppModel
import sh.zeron.android.core.TextEngine
import sh.zeron.android.design.Icons

class ZeronApplication : Application() {
    lateinit var model: AppModel
        private set

    override fun onCreate() {
        super.onCreate()
        TextEngine.init(this)
        Icons.init(this)
        sh.zeron.android.design.WallpaperStore.init(this)
        sh.zeron.android.ui.Favorites.init(this)
        sh.zeron.android.notify.Notifications.ensureChannels(this)
        model = AppModel(this)
    }
}
