package com.danemadsen.curie.assistant

import java.lang.ref.WeakReference

/**
 * The running [CurieVoiceInteractionService], when the system has bound it and it is ready. Weak, so a
 * service the system has let go of is never kept alive by this holder.
 */
object ActiveAssistant {
    @Volatile
    private var serviceReference: WeakReference<CurieVoiceInteractionService>? = null

    fun current(): CurieVoiceInteractionService? {
        return serviceReference?.get()
    }

    internal fun attach(service: CurieVoiceInteractionService) {
        serviceReference = WeakReference(service)
    }

    /** Only detaches [service] itself: a newer instance may already have attached. */
    internal fun detach(service: CurieVoiceInteractionService) {
        val currentService = serviceReference?.get()
        if (currentService === service) {
            serviceReference = null
        }
    }
}
