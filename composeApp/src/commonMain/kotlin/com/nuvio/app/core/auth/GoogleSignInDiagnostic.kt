package com.nuvio.app.core.auth

/** Bounded launch diagnostics. Never log OAuth URLs, tokens or credentials. */
internal fun googleSignInDiagnostic(error: Throwable): String =
    generateSequence(error) { it.cause }.take(4).joinToString(" <- ") { cause ->
        val message = cause.message.orEmpty()
            .replace(Regex("""(?i)(?:https?|wmedia)://\S+"""), "<URL>")
            .replace(Regex("""(?i)(password|access_token|refresh_token|id_token|provider_token|apikey|code_verifier|client_secret|bearer|code|state)\s*["']?\s*[:=]?\s*["']?[^\s,"']+"""), "$1=<redacted>")
            .replace(Regex("""[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}"""), "<token>")
            .replace(Regex("""[\w.+-]+@[\w.-]+"""), "<email>")
            .take(220)
        "${cause::class.simpleName}: $message"
    }
