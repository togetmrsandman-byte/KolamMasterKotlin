package com.kolammaster.app

import com.kolammaster.app.auth.SupabaseGuestAuth
import com.kolammaster.app.auth.SupabaseAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal class FolderSignInRequiredException :
    IllegalStateException("Sign in with Google to use My Folders.")

internal interface FolderDataSource {
    suspend fun loadFolders(): List<Folder>
    suspend fun createFolder(name: String): Folder
    suspend fun renameFolder(folderId: String, name: String): Folder
    suspend fun deleteFolder(folderId: String)
    suspend fun addLesson(folderId: String, lessonId: String): Folder
    suspend fun removeLesson(folderId: String, lessonId: String): Folder
}

internal class FolderRepository : FolderDataSource {
    override suspend fun loadFolders(): List<Folder> = withContext(Dispatchers.IO) {
        val account = requireGoogleAccount()
        loadFolders(account, SupabaseGuestAuth.accessTokenFor(account.id))
    }

    override suspend fun createFolder(name: String): Folder = mutateFolders<Folder> { folders ->
        FolderCollection.create(folders, name)
    }

    override suspend fun renameFolder(folderId: String, name: String): Folder =
        mutateFolders<Folder> { folders ->
            val updated = FolderCollection.rename(folders, folderId, name)
            updated to updated.single { it.id == folderId }
        }

    override suspend fun deleteFolder(folderId: String) {
        require(folderId.isNotBlank()) { "Folder ID must not be empty." }
        mutateFolders<Unit> { folders ->
            val updated = FolderCollection.delete(folders, folderId)
            updated to Unit
        }
    }

    override suspend fun addLesson(folderId: String, lessonId: String): Folder =
        mutateFolders<Folder> { folders ->
            val updated = FolderCollection.addLesson(folders, folderId, lessonId)
            updated to updated.single { it.id == folderId }
        }

    override suspend fun removeLesson(folderId: String, lessonId: String): Folder =
        mutateFolders<Folder> { folders ->
            val updated = FolderCollection.removeLesson(folders, folderId, lessonId)
            updated to updated.single { it.id == folderId }
        }

    private suspend fun <T> mutateFolders(
        transform: (List<Folder>) -> Pair<List<Folder>, T>
    ): T = withContext(Dispatchers.IO) {
        folderMutationMutex.lock()
        try {
            val account = requireGoogleAccount()
            val accessToken = SupabaseGuestAuth.accessTokenFor(account.id)
            val current = loadFolders(account, accessToken)
            val (updated, result) = transform(current)
            if (updated != current) {
                saveFolders(account, accessToken, updated)
            }
            result
        } finally {
            folderMutationMutex.unlock()
        }
    }

    private suspend fun requireGoogleAccount(): SupabaseAccount {
        val account = SupabaseGuestAuth.currentAccount() ?: throw FolderSignInRequiredException()
        if (account.isGuest) throw FolderSignInRequiredException()
        if (account.id.isBlank()) {
            throw IllegalStateException("The authenticated Supabase account has no user ID.")
        }
        return account
    }

    private fun loadFolders(account: SupabaseAccount, accessToken: String): List<Folder> {
        val query = "user_id=eq.${encode(account.id)}&select=folders&limit=1"
        val response = request(
            method = "GET",
            path = "/rest/v1/user_data?$query",
            accessToken = accessToken
        )
        val rows = try {
            JSONArray(response)
        } catch (exception: JSONException) {
            throw FolderDataException("Supabase returned malformed user_data rows.", exception)
        }
        if (rows.length() == 0) return emptyList()
        val row = rows.optJSONObject(0)
            ?: throw FolderDataException("Supabase returned a non-object user_data row.")
        return FolderJson.decode(row.opt("folders"))
    }

    private fun saveFolders(
        account: SupabaseAccount,
        accessToken: String,
        folders: List<Folder>
    ) {
        val encodedFolders = FolderJson.encode(folders)
        val query = "user_id=eq.${encode(account.id)}"
        val updateResponse = request(
            method = "PATCH",
            path = "/rest/v1/user_data?$query",
            accessToken = accessToken,
            body = JSONObject().put("folders", JSONArray(encodedFolders)).toString(),
            prefer = "return=representation"
        )
        val updatedRows = try {
            JSONArray(updateResponse)
        } catch (exception: JSONException) {
            throw FolderDataException("Supabase returned malformed update rows.", exception)
        }
        if (updatedRows.length() == 0) {
            val insertBody = JSONObject()
                .put("user_id", account.id)
                .put("folders", JSONArray(encodedFolders))
                .toString()
            request(
                method = "POST",
                path = "/rest/v1/user_data?on_conflict=user_id",
                accessToken = accessToken,
                body = insertBody,
                prefer = "resolution=merge-duplicates,return=minimal"
            )
        }
    }

    private fun request(
        method: String,
        path: String,
        accessToken: String,
        body: String? = null,
        prefer: String? = null
    ): String {
        val connection = URL("${BuildConfig.SUPABASE_URL}$path")
            .openConnection() as? HttpURLConnection
            ?: throw IOException("Supabase folder endpoint is not an HTTP connection.")
        try {
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
            connection.readTimeout = READ_TIMEOUT_MILLIS
            connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("Accept", "application/json")
            prefer?.let { connection.setRequestProperty("Prefer", it) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            }

            val status = connection.responseCode
            val response = (if (status in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw HttpStatusFailureException(
                    statusCode = status,
                    message = "Supabase folder request failed with HTTP $status" +
                        response.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()
                )
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000
        const val READ_TIMEOUT_MILLIS = 20_000
        val folderMutationMutex = Mutex()
    }
}
