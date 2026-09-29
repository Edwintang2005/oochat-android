package ai.openonion.oochat.domain.model

sealed class AppError(override val message: String) : Exception(message) {
    data class NetworkError(override val message: String) : AppError(message)
    data class ValidationError(override val message: String) : AppError(message)
    data class SessionError(override val message: String) : AppError(message)
    data class PersistenceError(override val message: String) : AppError(message)
    data class AuthenticationError(override val message: String) : AppError(message)
    data class PermissionError(override val message: String) : AppError(message)
    data class UnknownError(override val message: String = "Unknown error") : AppError(message)
}

fun createError(message: String): AppError = when {
    message.contains("Connection", ignoreCase = true) -> AppError.NetworkError(message)
    message.contains("Permission", ignoreCase = true) -> AppError.PermissionError(message)
    message.contains("Authentication", ignoreCase = true) || message.contains("Auth", ignoreCase = true) -> AppError.AuthenticationError(message)
    message.contains("Validation", ignoreCase = true) -> AppError.ValidationError(message)
    message.contains("Session", ignoreCase = true) -> AppError.SessionError(message)
    message.contains("Persist", ignoreCase = true) -> AppError.PersistenceError(message)
    else -> AppError.UnknownError(message)
}
