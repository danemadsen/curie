package com.danemadsen.curie.tools

import android.content.Context
import android.telephony.TelephonyManager
import android.util.Log

/** Recognises phone numbers in tool arguments and decides which of them must only ever be dialled. */
internal object PhoneNumbers {
    private const val TAG = "PhoneNumbers"

    /** Fewer digits make a number only when it is an emergency number (17 is the police in France). */
    private const val MIN_DIGITS = 3

    /** Formatting people and models put in numbers; none of it is dialled. */
    private val FORMATTING = Regex("[\\s().\\-/]")

    /** What may remain after formatting is removed: an optional leading +, digits, * and #. */
    private val DIALABLE = Regex("\\+?[0-9*#]+")

    /** Digits with an optional leading +: the only numbers Curie may call without the dialer. */
    private val PLAIN_NUMBER = Regex("\\+?[0-9]+")

    /**
     * Widely used emergency numbers, checked before (and independently of) the SIM and network data.
     * ACTION_CALL cannot reach them, and treating another short number as one only costs the user a
     * tap on Call in the dialer, so the list leans towards more numbers.
     */
    private val EMERGENCY_NUMBERS = setOf(
        "000", "100", "101", "102", "103", "108", "110", "112", "113", "115", "117", "118", "119", "120",
        "122", "123", "15", "150", "155", "158", "17", "18", "190", "192", "193", "197", "199", "911",
        "995", "999",
    )

    /** The number in dialable form ("+15551234567") when [text] is a phone number, else null. */
    fun dialableOrNull(context: Context, text: String): String? {
        val trimmed = text.trim()
        val withoutFormatting = FORMATTING.replace(trimmed, "")
        if (!DIALABLE.matches(withoutFormatting)) {
            return null
        }

        val digitCount = withoutFormatting.count { character -> character.isDigit() }
        val isShort = digitCount < MIN_DIGITS
        if (isShort && !isEmergencyNumber(context, withoutFormatting)) {
            return null
        }
        return withoutFormatting
    }

    /**
     * Whether [number] (dialable form) is plain digits, which Curie may call without the dialer. With *
     * or # it is an MMI, USSD or vertical service code that changes phone settings (**21*number# forwards
     * every call): Telecom only diverts such codes from other apps to the dialer from Android 14, so
     * Curie always leaves them to the user to confirm there.
     */
    fun isPlainNumber(number: String): Boolean {
        return PLAIN_NUMBER.matches(number)
    }

    /** Whether [number] (dialable form) is an emergency number by the fixed list alone. */
    fun isKnownEmergencyNumber(number: String): Boolean {
        val digits = number.removePrefix("+")
        return digits in EMERGENCY_NUMBERS
    }

    /** Whether [number] is an emergency number by the fixed list or by the phone's own SIM and network data. */
    fun isEmergencyNumber(context: Context, number: String): Boolean {
        if (isKnownEmergencyNumber(number)) {
            return true
        }

        val telephonyManager = context.getSystemService(TelephonyManager::class.java) ?: return false
        return try {
            telephonyManager.isEmergencyNumber(number)
        } catch (exception: RuntimeException) {
            // No telephony on this device (UnsupportedOperationException) or the phone process is
            // unavailable (IllegalStateException): the fixed list already had its say.
            Log.w(TAG, "The emergency number check is unavailable", exception)
            false
        }
    }
}
