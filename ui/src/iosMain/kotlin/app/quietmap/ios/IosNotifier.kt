package app.quietmap.ios

import app.quietmap.core.Engine
import app.quietmap.core.Notice
import app.quietmap.core.model.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import platform.Foundation.NSUUID
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * The iOS side of Android's "Circle changes" notifications: someone joined or left a circle
 * ([Engine.notices]), and the monthly "You're still sharing your location" reminder. These are
 * local notifications; nothing goes through Apple's push service.
 */
class IosNotifier(private val engine: Engine, scope: CoroutineScope) {
    private val center = UNUserNotificationCenter.currentNotificationCenter()
    private val delegate = Delegate()
    private var asked = false

    init {
        center.delegate = delegate
        scope.launch(Dispatchers.Main) { engine.notices.collect { post(it) } }
    }

    /** Asks once for permission to show notifications. Call while the app is on screen. */
    fun requestPermission() {
        if (asked) return
        asked = true
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ -> }
    }

    /** "You're still sharing your location", with how many people see it. */
    fun sharingReminder() {
        val people = sharingWithCount(engine.state.value)
        post(Notice("You're still sharing your location", "With $people ${if (people == 1) "person" else "people"}. Tap to check who."))
    }

    /** People who see this phone's location: members of circles the user confirmed ("Start sharing"). */
    private fun sharingWithCount(s: AppState): Int =
        s.circles.filter { it.confirmed }.flatMap { c -> c.members.map { it.pubkey } }.toSet().count { it != engine.myPubkey }

    private fun post(n: Notice) {
        val content = UNMutableNotificationContent().apply {
            setTitle(n.title)
            setBody(n.text)
            setSound(UNNotificationSound.defaultSound)
        }
        // iOS drops it quietly if notifications aren't allowed.
        center.addNotificationRequest(UNNotificationRequest.requestWithIdentifier(NSUUID().UUIDString, content, null), null)
    }

    /** Shows them while Opaq is open too, as Android does. */
    private class Delegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
        override fun userNotificationCenter(
            center: UNUserNotificationCenter,
            willPresentNotification: UNNotification,
            withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
        ) {
            withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList)
        }
    }
}
