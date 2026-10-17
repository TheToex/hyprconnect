package app.hyprconnect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/** Forwards incoming SMS to the PC (needs RECEIVE_SMS granted in the app). */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        Hub.init(c)
        val msgs = Telephony.Sms.Intents.getMessagesFromIntent(i)
        val from = msgs.firstOrNull()?.originatingAddress ?: "unknown"
        val body = msgs.joinToString("") { it.messageBody ?: "" }
        if (Hub.conn.value is Hub.Conn.On) Hub.send("sms", "from" to from, "body" to body)
    }
}
