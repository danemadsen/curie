package com.danemadsen.curie.tools

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.danemadsen.curie.R
import com.danemadsen.curie.engine.chat.ToolDefinition
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A person to call or write to, or the outcome explaining why they could not be found. */
private sealed interface Recipient {
    data class Resolved(val address: String, val contactName: String?) : Recipient {
        /** How the UI and the model refer to the recipient. */
        val displayName: String
            get() = contactName ?: address
    }

    data class Unresolved(val outcome: ToolOutcome) : Recipient
}

/** How a tool describes the recipient it could not find. */
private class RecipientFailure(
    val icon: String,
    val summary: String,

    /** What the user could say instead of a name: "phone number" or "email address". */
    val valueKind: String,
)

/**
 * Turns "Mom" or "+1 555 0100" into a phone number: numbers are used as they are, names are looked
 * up in the contacts (READ_CONTACTS, requested by the UI at first use).
 */
private suspend fun ToolServices.resolvePhoneRecipient(spoken: String, failure: RecipientFailure): Recipient {
    val number = PhoneNumbers.dialableOrNull(context, spoken)
    if (number != null) {
        return Recipient.Resolved(number, contactName = null)
    }

    val lookup = withContext(ioDispatcher) { contacts.findPhoneNumber(spoken) }
    return when (lookup) {
        is ContactLookup.Found -> {
            val contactNumber = PhoneNumbers.dialableOrNull(context, lookup.value) ?: lookup.value
            Recipient.Resolved(contactNumber, lookup.name)
        }

        is ContactLookup.Failure -> {
            val outcome = contactFailure(spoken, lookup, failure)
            Recipient.Unresolved(outcome)
        }
    }
}

/** Turns "Mom" or "mom@example.com" into an email address. */
private suspend fun ToolServices.resolveEmailRecipient(spoken: String, failure: RecipientFailure): Recipient {
    if (EmailAddresses.isAddress(spoken)) {
        return Recipient.Resolved(spoken, contactName = null)
    }

    val lookup = withContext(ioDispatcher) { contacts.findEmailAddress(spoken) }
    return when (lookup) {
        is ContactLookup.Found -> Recipient.Resolved(lookup.value, lookup.name)
        is ContactLookup.Failure -> {
            val outcome = contactFailure(spoken, lookup, failure)
            Recipient.Unresolved(outcome)
        }
    }
}

private fun contactFailure(spoken: String, lookup: ContactLookup.Failure, failure: RecipientFailure): ToolOutcome {
    return when (lookup) {
        ContactLookup.NoPermission -> toolFailure(
            icon = failure.icon,
            summary = failure.summary,
            error = "Curie needs access to the contacts to find \"$spoken\". " +
                "Ask the user to allow it, or to say the ${failure.valueKind} instead.",
            permissionsNeeded = listOf(Manifest.permission.READ_CONTACTS),
        )

        is ContactLookup.Ambiguous -> {
            val choices = lookup.names.map { name -> JsonPrimitive(name) }
            val choiceArray = JsonArray(choices)
            val details = buildJsonObject {
                put("matches", choiceArray)
            }
            toolFailure(
                icon = failure.icon,
                summary = failure.summary,
                error = "Several contacts match \"$spoken\". Ask the user which one they mean.",
                details = details,
            )
        }

        ContactLookup.NotFound -> toolFailure(
            icon = failure.icon,
            summary = failure.summary,
            error = "No contact called \"$spoken\" has a ${failure.valueKind}. " +
                "Ask the user for the full name or the ${failure.valueKind}.",
        )
    }
}

/**
 * call_contact: places the call when Curie may (CALL_PHONE, a plain number that is not an emergency
 * number), otherwise opens the dialer for the user to confirm.
 */
internal class CallContactTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "phone-call"
    override val failureSummary = R.string.tool_failure_call

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val spoken = arguments.requiredString("name_or_number")
        val failureText = services.text(failureSummary)
        val failure = RecipientFailure(icon, failureText, valueKind = "phone number")

        val recipient = services.resolvePhoneRecipient(spoken, failure)
        val resolved = when (recipient) {
            is Recipient.Resolved -> recipient
            is Recipient.Unresolved -> return recipient.outcome
        }

        // Always the dialer, for the user to confirm, for emergency numbers (ACTION_CALL cannot reach
        // them) and for codes with * or #, which change phone settings such as call forwarding.
        val isEmergency = PhoneNumbers.isEmergencyNumber(services.context, resolved.address)
        val isPlainNumber = PhoneNumbers.isPlainNumber(resolved.address)
        val mayCallDirectly = isPlainNumber && !isEmergency
        val hasCallPermission = hasCallPermission()
        val callsDirectly = hasCallPermission && mayCallDirectly

        val dialIntent = AssistantIntents.dial(resolved.address)
        val callIntent = AssistantIntents.call(resolved.address)
        val candidates = if (callsDirectly) listOf(callIntent, dialIntent) else listOf(dialIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val isCalling = launch.chosenIntent === callIntent
        val details = buildJsonObject {
            putIfPresent("name", resolved.contactName)
            put("number", resolved.address)
            if (!isCalling) {
                put("note", "The dialer shows the number; the user taps Call to start the call.")
            }
        }

        val status: String
        val summary: String
        if (isCalling) {
            status = "calling"
            summary = services.text(R.string.tool_summary_calling, resolved.displayName)
        } else {
            status = "dialer_opened"
            summary = services.text(R.string.tool_summary_dialer, resolved.displayName)
        }

        val report = LaunchReport(
            icon = icon,
            summary = summary,
            failureSummary = failureText,
            status = status,
            details = details,
            noHandlerError = "This phone cannot make calls.",
        )
        val outcome = launchOutcome(launch, report)

        // Offer direct calling for next time, unless this number stays in the dialer either way.
        val offerCallPermission = outcome.success && !hasCallPermission && mayCallDirectly
        if (offerCallPermission) {
            val permissions = listOf(Manifest.permission.CALL_PHONE)
            return outcome.copy(permissionsNeeded = permissions)
        }
        return outcome
    }

    private fun hasCallPermission(): Boolean {
        val state = ContextCompat.checkSelfPermission(services.context, Manifest.permission.CALL_PHONE)
        return state == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "call_contact",
            description = "Call a contact or phone number.",
            parameters = toolParameters {
                string("name_or_number", required = true)
            },
        )
    }
}

/** send_text_message: opens the messaging app with everything filled in; the user taps Send. */
internal class SendTextMessageTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "message"
    override val failureSummary = R.string.tool_failure_text

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val spoken = arguments.requiredString("recipient")
        val message = arguments.requiredString("message")
        val failureText = services.text(failureSummary)
        val failure = RecipientFailure(icon, failureText, valueKind = "phone number")

        val recipient = services.resolvePhoneRecipient(spoken, failure)
        val resolved = when (recipient) {
            is Recipient.Resolved -> recipient
            is Recipient.Unresolved -> return recipient.outcome
        }

        val composeIntent = AssistantIntents.composeSms(resolved.address, message)
        val candidates = listOf(composeIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val details = buildJsonObject {
            putIfPresent("name", resolved.contactName)
            put("number", resolved.address)
            put("note", "The message is written but not sent; the user taps Send.")
        }
        val report = LaunchReport(
            icon = icon,
            summary = services.text(R.string.tool_summary_text, resolved.displayName),
            failureSummary = failureText,
            status = "message_ready_to_send",
            details = details,
            noHandlerError = "No messaging app on this phone can send text messages.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "send_text_message",
            description = "Write a text message.",
            parameters = toolParameters {
                string("recipient", "Name or number", required = true)
                string("message", required = true)
            },
        )
    }
}

/** send_email: opens an email app with the draft filled in; the user taps Send. */
internal class SendEmailTool(private val services: ToolServices) : AssistantTool {
    override val definition = DEFINITION
    override val icon = "mail"
    override val failureSummary = R.string.tool_failure_email

    override suspend fun execute(arguments: ToolArguments, origin: ToolOrigin): ToolOutcome {
        val recipientNames = arguments.optionalStringList("to")
        val subject = arguments.optionalString("subject")
        val body = arguments.requiredString("body")
        val failureText = services.text(failureSummary)
        val failure = RecipientFailure(icon, failureText, valueKind = "email address")

        val recipients = mutableListOf<Recipient.Resolved>()
        for (spoken in recipientNames) {
            val recipient = services.resolveEmailRecipient(spoken, failure)
            when (recipient) {
                is Recipient.Resolved -> recipients.add(recipient)
                is Recipient.Unresolved -> return recipient.outcome
            }
        }

        val addresses = recipients.map { recipient -> recipient.address }
        val composeIntent = AssistantIntents.composeEmail(addresses, subject, body)
        val candidates = listOf(composeIntent)
        val launch = services.launcher.launch(candidates, origin, LaunchTiming.SWITCHES_APP)

        val addressPrimitives = addresses.map { address -> JsonPrimitive(address) }
        val addressArray = JsonArray(addressPrimitives)
        val details = buildJsonObject {
            put("to", addressArray)
            putIfPresent("subject", subject)
            put("note", "The email is written but not sent; the user taps Send.")
        }

        val summary = if (recipients.isEmpty()) {
            services.text(R.string.tool_summary_email)
        } else {
            val recipientText = recipients.joinToString(", ") { recipient -> recipient.displayName }
            services.text(R.string.tool_summary_email_to, recipientText)
        }
        val report = LaunchReport(
            icon = icon,
            summary = summary,
            failureSummary = failureText,
            status = "email_ready_to_send",
            details = details,
            noHandlerError = "No email app on this phone can write emails.",
        )
        return launchOutcome(launch, report)
    }

    companion object {
        val DEFINITION = ToolDefinition(
            name = "send_email",
            description = "Write an email.",
            parameters = toolParameters {
                string("to", "Address or contact name")
                string("subject")
                string("body", required = true)
            },
        )
    }
}

/** Tells email addresses apart from contact names. */
internal object EmailAddresses {
    private val ADDRESS = Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")

    fun isAddress(text: String): Boolean {
        val trimmed = text.trim()
        return ADDRESS.matches(trimmed)
    }
}
