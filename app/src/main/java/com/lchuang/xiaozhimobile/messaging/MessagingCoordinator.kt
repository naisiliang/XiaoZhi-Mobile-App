package com.lchuang.xiaozhimobile.messaging

import com.lchuang.xiaozhimobile.accessibility.UiActionProposal
import com.lchuang.xiaozhimobile.accessibility.UiActionType
import com.lchuang.xiaozhimobile.screen.ContextCandidate
import com.lchuang.xiaozhimobile.screen.GenerationId
import com.lchuang.xiaozhimobile.screen.ScreenContext
import java.util.Locale

/** An ordinary text request resolved against one current messaging app. */
data class MessagingRequest(
    val packageName: String,
    val contactReference: String,
    val body: String,
) {
    init {
        require(packageName.isNotBlank()) { "Messaging request package must not be blank" }
        require(contactReference.isNotBlank()) {
            "Messaging contact reference must not be blank"
        }
    }

    override fun toString(): String =
        "MessagingRequest(packageName=$packageName, contactReference=$contactReference)"
}

/** The UI-facing confirmation card created before any send action exists. */
data class MessageConfirmationCard(
    val tokenId: String,
    val packageName: String,
    val contactId: String,
    val contactDisplayName: String,
    val conversationTitle: String,
    val body: String,
    val generationId: GenerationId,
    val windowFingerprint: String,
) {
    init {
        require(tokenId.isNotBlank()) { "Message confirmation card token must not be blank" }
        require(packageName.isNotBlank()) { "Message card package must not be blank" }
        require(contactId.isNotBlank()) { "Message card contact id must not be blank" }
        require(contactDisplayName.isNotBlank()) {
            "Message card contact name must not be blank"
        }
        require(conversationTitle.isNotBlank()) {
            "Message card conversation title must not be blank"
        }
        require(body.isNotBlank()) { "Message card body must not be blank" }
        require(generationId.value > 0L) { "Message card generation must be positive" }
        require(windowFingerprint.isNotBlank()) {
            "Message card window fingerprint must not be blank"
        }
    }

    override fun toString(): String =
        "MessageConfirmationCard(tokenId=$tokenId, packageName=$packageName, " +
            "contactId=$contactId, generationId=$generationId, " +
            "windowFingerprint=$windowFingerprint)"
}

data class MessagingCoordinatorResult(
    val state: MessagingState,
    val contactOptions: List<ContactCandidate> = emptyList(),
    val openChatProposal: UiActionProposal? = null,
    val confirmationCard: MessageConfirmationCard? = null,
    val pendingMessage: PendingMessage? = null,
    val errorCode: String? = null,
) {
    init {
        require(contactOptions.distinctBy(ContactCandidate::id).size == contactOptions.size) {
            "Messaging contact options must have unique ids"
        }
    }

    override fun toString(): String =
        "MessagingCoordinatorResult(state=$state, contactOptions=${contactOptions.size}, " +
            "hasOpenChatProposal=${openChatProposal != null}, " +
            "hasConfirmationCard=${confirmationCard != null}, " +
            "hasPendingMessage=${pendingMessage != null}, errorCode=$errorCode)"
}

/**
 * Resolves contacts and prepares a user-visible message confirmation.
 *
 * This class deliberately stops at WAITING_CONFIRMATION. It never types a
 * body, clicks send, invokes an Accessibility API, or retries a side effect.
 */
class MessagingCoordinator(
    adapters: List<MessagingAppAdapter>,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val sensitiveContentDetector: SensitiveContentDetector = SensitiveContentDetector(),
) {
    private val adapters: List<MessagingAppAdapter> = adapters.toList()

    private var currentState: MessagingState = MessagingState.IDLE
    private var request: MessagingRequest? = null
    private var context: ScreenContext? = null
    private var adapter: MessagingAppAdapter? = null
    private var selectedContact: ContactCandidate? = null
    private var contactOptions: List<ContactCandidate> = emptyList()
    private var pendingMessage: PendingMessage? = null
    private var confirmationToken: MessageConfirmationToken? = null
    private val transitions = mutableListOf(MessagingState.IDLE)

    val state: MessagingState
        get() = currentState

    val stateHistory: List<MessagingState>
        get() = transitions.toList()

    /** Begin contact resolution and return either a selection or an open-chat proposal. */
    fun start(
        request: MessagingRequest,
        context: ScreenContext,
    ): MessagingCoordinatorResult {
        if (currentState != MessagingState.IDLE) {
            return result(errorCode = "MESSAGE_BUSY")
        }
        if (request.packageName != context.packageName) {
            return blocked("MESSAGE_APP_MISMATCH")
        }
        if (request.body.isBlank()) {
            return blocked("MESSAGE_BODY_EMPTY")
        }
        if (sensitiveContentDetector.detect(request.body).blocked) {
            // Do not retain credential-like text in coordinator state.
            return blocked("MESSAGE_SENSITIVE_CONTENT_BLOCKED")
        }

        val selectedAdapter = adapters.singleOrNull { candidate ->
            runCatching { candidate.canHandle(context.packageName) }.getOrDefault(false)
        } ?: return blocked("MESSAGE_APP_NOT_SUPPORTED")

        this.request = request
        this.context = context
        this.adapter = selectedAdapter
        transition(MessagingState.RESOLVING_CONTACT)

        if (isCurrentChatReference(request.contactReference)) {
            val currentChat = selectedAdapter.currentChatCandidate(context)
                ?: return needsSelection("MESSAGE_CONTACT_AMBIGUOUS")
            selectedContact = contactFrom(currentChat)
            transition(MessagingState.CONTACT_RESOLVED)
            return prepareConfirmation(context, selectedContact!!, currentChat)
        }

        val matches = selectedAdapter.resolveContactCandidates(
            context = context,
            requestedName = request.contactReference,
        ).map(::contactFrom)
        return when {
            matches.size > 1 -> {
                contactOptions = matches
                transition(MessagingState.NEEDS_CONTACT_SELECTION)
                result(contactOptions = contactOptions, errorCode = "MESSAGE_CONTACT_AMBIGUOUS")
            }
            matches.size == 1 -> {
                selectedContact = matches.single()
                transition(MessagingState.CONTACT_RESOLVED)
                openOrPrepare(selectedAdapter, context, selectedContact!!)
            }
            else -> resolveCurrentChatOrBlock(selectedAdapter, context)
        }
    }

    /** Continue an ambiguous request only after the user selected one exact id. */
    fun selectContact(contactId: String): MessagingCoordinatorResult {
        if (currentState != MessagingState.NEEDS_CONTACT_SELECTION) {
            return result(errorCode = "MESSAGE_CONTACT_SELECTION_NOT_EXPECTED")
        }
        val chosen = contactOptions.singleOrNull { candidate -> candidate.id == contactId }
            ?: return result(
                contactOptions = contactOptions,
                errorCode = "MESSAGE_CONTACT_SELECTION_INVALID",
            )
        if (request == null) return blocked("MESSAGE_REQUEST_MISSING")
        val selectedContext = context
            ?: return blocked("SCREEN_CONTEXT_STALE")
        if (adapter == null) return blocked("MESSAGE_APP_NOT_SUPPORTED")

        selectedContact = chosen
        transition(MessagingState.CONTACT_RESOLVED)
        transition(MessagingState.OPENING_CHAT)
        val proposal = UiActionProposal(
            action = UiActionType.CLICK,
            context = selectedContext,
            target = chosen.target,
        )
        // Keep the request and adapter alive for onChatOpened; no send is done here.
        return result(openChatProposal = proposal)
    }

    /** Accept a caller-provided post-navigation snapshot and prepare confirmation only. */
    fun onChatOpened(chatContext: ScreenContext): MessagingCoordinatorResult {
        if (currentState != MessagingState.OPENING_CHAT) {
            return result(errorCode = "MESSAGE_CHAT_OPENING_NOT_EXPECTED")
        }
        val selectedRequest = request ?: return blocked("MESSAGE_REQUEST_MISSING")
        val selectedContact = selectedContact ?: return blocked("MESSAGE_CONTACT_MISSING")
        val selectedAdapter = adapter ?: return blocked("MESSAGE_APP_NOT_SUPPORTED")
        if (chatContext.packageName != selectedRequest.packageName ||
            !selectedAdapter.canHandle(chatContext.packageName)
        ) {
            return blocked("MESSAGE_APP_CHANGED")
        }

        val currentChat = selectedAdapter.currentChatCandidate(chatContext)
            ?: return blocked("MESSAGE_CONTACT_AMBIGUOUS")
        if (!sameSemanticName(currentChat.label, selectedContact.displayName)) {
            return blocked("MESSAGE_CONTACT_CHANGED")
        }
        return prepareConfirmation(chatContext, selectedContact, currentChat)
    }

    private fun openOrPrepare(
        selectedAdapter: MessagingAppAdapter,
        selectedContext: ScreenContext,
        selectedContact: ContactCandidate,
    ): MessagingCoordinatorResult {
        val currentChat = selectedAdapter.currentChatCandidate(selectedContext)
        return if (currentChat != null &&
            sameSemanticName(currentChat.label, selectedContact.displayName)
        ) {
            prepareConfirmation(selectedContext, selectedContact, currentChat)
        } else {
            transition(MessagingState.OPENING_CHAT)
            result(
                openChatProposal = UiActionProposal(
                    action = UiActionType.CLICK,
                    context = selectedContext,
                    target = selectedContact.target,
                ),
            )
        }
    }

    private fun resolveCurrentChatOrBlock(
        selectedAdapter: MessagingAppAdapter,
        selectedContext: ScreenContext,
    ): MessagingCoordinatorResult {
        val selectedRequest = request ?: return blocked("MESSAGE_REQUEST_MISSING")
        val currentChat = selectedAdapter.currentChatCandidate(selectedContext)
        return if (currentChat != null &&
            sameSemanticName(currentChat.label, selectedRequest.contactReference)
        ) {
            selectedContact = contactFrom(currentChat)
            transition(MessagingState.CONTACT_RESOLVED)
            prepareConfirmation(selectedContext, selectedContact!!, currentChat)
        } else {
            blocked("MESSAGE_CONTACT_NOT_FOUND")
        }
    }

    private fun prepareConfirmation(
        currentContext: ScreenContext,
        contact: ContactCandidate,
        currentChat: ContextCandidate,
    ): MessagingCoordinatorResult {
        val selectedRequest = request ?: return blocked("MESSAGE_REQUEST_MISSING")
        val selectedAdapter = adapter ?: return blocked("MESSAGE_APP_NOT_SUPPORTED")
        if (selectedAdapter.messageInputCandidate(currentContext) == null ||
            selectedAdapter.sendButtonCandidate(currentContext) == null
        ) {
            return blocked("MESSAGE_CONTROLS_MISSING")
        }

        transition(MessagingState.PREPARING_MESSAGE)
        val now = clockMs()
        val prepared = PendingMessage(
            packageName = selectedRequest.packageName,
            contactId = contact.id,
            contactDisplayName = contact.displayName,
            conversationTitle = currentChat.label,
            body = selectedRequest.body,
            generationId = currentContext.generationId,
            windowFingerprint = currentContext.windowFingerprint,
            createdAtMs = now,
        )
        val token = MessageConfirmationToken.issue(prepared, now)
        pendingMessage = prepared
        confirmationToken = token
        transition(MessagingState.WAITING_CONFIRMATION)
        return result(
            confirmationCard = MessageConfirmationCard(
                tokenId = token.tokenId,
                packageName = prepared.packageName,
                contactId = prepared.contactId,
                contactDisplayName = prepared.contactDisplayName,
                conversationTitle = prepared.conversationTitle,
                body = prepared.body,
                generationId = prepared.generationId,
                windowFingerprint = prepared.windowFingerprint,
            ),
            pendingMessage = prepared,
        )
    }

    private fun contactFrom(candidate: ContextCandidate) =
        ContactCandidate(
            target = candidate,
            displayName = candidate.label,
            conversationTitle = candidate.label,
        )

    private fun isCurrentChatReference(reference: String): Boolean =
        normalize(reference) in CURRENT_CHAT_REFERENCES

    private fun sameSemanticName(first: String, second: String): Boolean =
        normalize(first) == normalize(second)

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(WHITESPACE, " ").trim()

    private fun transition(next: MessagingState) {
        currentState = next
        transitions += next
    }

    private fun needsSelection(errorCode: String): MessagingCoordinatorResult {
        contactOptions = emptyList()
        transition(MessagingState.NEEDS_CONTACT_SELECTION)
        return result(errorCode = errorCode)
    }

    private fun blocked(errorCode: String): MessagingCoordinatorResult {
        request = null
        context = null
        adapter = null
        selectedContact = null
        contactOptions = emptyList()
        pendingMessage = null
        confirmationToken = null
        transition(MessagingState.BLOCKED)
        return result(errorCode = errorCode)
    }

    private fun result(
        contactOptions: List<ContactCandidate> = this.contactOptions,
        openChatProposal: UiActionProposal? = null,
        confirmationCard: MessageConfirmationCard? = null,
        pendingMessage: PendingMessage? = this.pendingMessage,
        errorCode: String? = null,
    ): MessagingCoordinatorResult = MessagingCoordinatorResult(
        state = currentState,
        contactOptions = contactOptions.toList(),
        openChatProposal = openChatProposal,
        confirmationCard = confirmationCard,
        pendingMessage = pendingMessage,
        errorCode = errorCode,
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val CURRENT_CHAT_REFERENCES = setOf("他", "她", "him", "her")
    }
}
