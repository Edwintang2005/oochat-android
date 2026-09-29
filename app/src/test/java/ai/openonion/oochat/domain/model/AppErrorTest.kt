package ai.openonion.oochat.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {

    @Test
    fun `createError with connection message returns NetworkError`() {
        val error = createError("Connection failed")
        assertTrue(error is AppError.NetworkError)
        assertEquals("Connection failed", error.message)
    }

    @Test
    fun `createError with permission message returns PermissionError`() {
        val error = createError("Permission denied")
        assertTrue(error is AppError.PermissionError)
    }

    @Test
    fun `createError with authentication message returns AuthenticationError`() {
        val error = createError("Authentication failed")
        assertTrue(error is AppError.AuthenticationError)
    }

    @Test
    fun `createError with auth message returns AuthenticationError`() {
        val error = createError("Auth token expired")
        assertTrue(error is AppError.AuthenticationError)
    }

    @Test
    fun `createError with validation message returns ValidationError`() {
        val error = createError("Validation failed")
        assertTrue(error is AppError.ValidationError)
    }

    @Test
    fun `createError with session message returns SessionError`() {
        val error = createError("Session expired")
        assertTrue(error is AppError.SessionError)
    }

    @Test
    fun `createError with persist message returns PersistenceError`() {
        val error = createError("Persist operation failed")
        assertTrue(error is AppError.PersistenceError)
    }

    @Test
    fun `createError with unrecognized message returns UnknownError`() {
        val error = createError("Something happened")
        assertTrue(error is AppError.UnknownError)
    }

    @Test
    fun `createError is case-insensitive`() {
        val error = createError("connection TIMEOUT")
        assertTrue(error is AppError.NetworkError)
    }

    @Test
    fun `createError with empty message returns UnknownError`() {
        val error = createError("")
        assertTrue(error is AppError.UnknownError)
    }

    @Test
    fun `createError keyword priority - connection takes precedence over session`() {
        val error = createError("Connection lost during session")
        assertTrue(error is AppError.NetworkError)
    }

    @Test
    fun `createError keyword priority - permission takes precedence over authentication`() {
        val error = createError("Permission and authentication both failed")
        assertTrue(error is AppError.PermissionError)
    }

    @Test
    fun `AppError subtypes preserve the original message`() {
        val message = "Persist: custom detail message"
        val error = createError(message)
        assertEquals(message, error.message)
    }
}
