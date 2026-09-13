package productfactory.workflow.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets

/**
 * Минимальный клиент GitHub API: создание репозитория.
 * Токен из env GITHUB_TOKEN; без токена методы возвращают null.
 */
object GitHubApiClient {
    private val client = HttpClient.newBuilder().build()

    /**
     * Создаёт репозиторий под текущим пользователем (POST /user/repos).
     * @return html_url репо или null при ошибке/отсутствии токена
     */
    fun createRepo(repoName: String, privateRepo: Boolean = false): String? {
        val token = System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val body = """{"name":"${repoName.replace("\"", "\\\"")}","private":$privateRepo}"""
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/user/repos"))
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) return null
            parseRepoHtmlUrl(response.body())
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Создаёт репозиторий: при пустом owner — под текущим пользователем (POST /user/repos),
     * иначе в организации (POST /orgs/{owner}/repos).
     * @param token если задан, используется вместо env GITHUB_TOKEN (для policy allowlist).
     */
    fun createRepoForOwner(owner: String, repoName: String, privateRepo: Boolean = false, token: String? = null): String? {
        val t = token?.trim()?.takeIf { it.isNotBlank() } ?: System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val body = """{"name":"${repoName.replace("\"", "\\\"")}","private":$privateRepo}"""
        val path = if (owner.isBlank()) "user/repos" else "orgs/${owner.trim()}/repos"
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/$path"))
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) return null
            parseRepoHtmlUrl(response.body())
        } catch (_: Exception) {
            null
        }
    }

    /** Берёт html_url из корня JSON (репо), а не из вложенного owner. */
    private fun parseRepoHtmlUrl(jsonBody: String): String? {
        return try {
            val obj = Json.parseToJsonElement(jsonBody).jsonObject
            obj["html_url"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Возвращает логин текущего пользователя (GET /user).
     * Используется для push, когда owner не указан.
     * @param token если задан, используется вместо env GITHUB_TOKEN (для policy allowlist).
     */
    fun getCurrentUserLogin(token: String? = null): String? {
        val t = token?.trim()?.takeIf { it.isNotBlank() } ?: System.getenv("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.github.com/user"))
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github.v3+json")
            .GET()
            .build()
        return try {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            if (response.statusCode() !in 200..299) return null
            val json = response.body()
            val key = "\"login\":"
            val start = json.indexOf(key)
            if (start < 0) return null
            val from = json.indexOf('"', start + key.length) + 1
            val to = json.indexOf('"', from)
            if (from > 0 && to > from) json.substring(from, to) else null
        } catch (_: Exception) {
            null
        }
    }
}
