package com.kashke.app.data

private val contactNameCharacters = Regex("^[\\p{L}\\p{M} .'-]*$")
private val contactTextCharacters = Regex("^[\\p{L}\\p{M}\\p{N} .,'!?():;/@+_#%&$=\\-]*$")
private val contactMessageCharacters = Regex("^[\\p{L}\\p{M}\\p{N} \\n\\r.,'!?():;/@+_#%&$=\\-]*$")
private val contactEmail = Regex("^[A-Za-z0-9][A-Za-z0-9._%+\\-]{0,63}@[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9\\-]*[A-Za-z0-9])?)+$")

internal fun contactNameInput(previous: String, candidate: String): String =
    candidate.takeIf { it.length <= 80 && contactNameCharacters.matches(it) } ?: previous
internal fun contactSubjectInput(previous: String, candidate: String): String =
    candidate.takeIf { it.length <= 120 && contactTextCharacters.matches(it) } ?: previous
internal fun contactMessageInput(previous: String, candidate: String): String =
    candidate.takeIf { it.length <= 4000 && contactMessageCharacters.matches(it) } ?: previous
internal fun contactEmailInput(previous: String, candidate: String): String =
    candidate.takeIf { it.length <= 254 && it.all { c -> c.isLetterOrDigit() && c.code < 128 || c in ".@_%+-" } } ?: previous

internal fun validContactForm(name: String,email: String,subject: String,message: String): Boolean =
    name.trim().length in 2..80 && contactNameCharacters.matches(name) &&
        email.trim().length <= 254 && contactEmail.matches(email.trim()) &&
        subject.trim().length in 3..120 && contactTextCharacters.matches(subject) &&
        message.trim().length in 10..4000 && contactMessageCharacters.matches(message)
