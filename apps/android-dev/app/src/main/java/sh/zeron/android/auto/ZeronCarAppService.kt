package sh.zeron.android.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Zeron in Android Auto: a messaging app. The car shows the recent chats, reads the latest message
 * of each aloud, and takes a spoken reply (see [ConversationsScreen]).
 */
class ZeronCarAppService : CarAppService() {
    // Personal dev build: any car host may bind. A published build would list Android Auto's signatures.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = ConversationsScreen(carContext)
    }
}
