package productfactory.workflow.storage

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipOutputStream
import kotlin.io.path.createTempFile
import kotlin.io.path.deleteIfExists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Загрузка артефакта (директории) в S3-совместимое хранилище (AWS S3 или MinIO).
 * Включается при заданных env: ARTIFACT_STORAGE_BUCKET, при необходимости ENDPOINT и ключи доступа.
 */
class S3ArtifactStorage(
    private val bucket: String,
    private val prefix: String,
    private val endpoint: String?,
    private val region: String,
    private val accessKey: String?,
    private val secretKey: String?,
) : ArtifactStorage {

    constructor(bucket: String) : this(
        bucket = bucket,
        prefix = System.getenv("ARTIFACT_STORAGE_PREFIX")?.trim()?.takeIf { it.isNotBlank() } ?: "artifacts",
        endpoint = System.getenv("ARTIFACT_STORAGE_ENDPOINT")?.trim()?.takeIf { it.isNotBlank() },
        region = System.getenv("ARTIFACT_STORAGE_REGION")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv("AWS_REGION")?.trim()?.takeIf { it.isNotBlank() } ?: "us-east-1",
        accessKey = System.getenv("ARTIFACT_STORAGE_ACCESS_KEY")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv("AWS_ACCESS_KEY_ID")?.trim()?.takeIf { it.isNotBlank() },
        secretKey = System.getenv("ARTIFACT_STORAGE_SECRET_KEY")?.trim()?.takeIf { it.isNotBlank() }
            ?: System.getenv("AWS_SECRET_ACCESS_KEY")?.trim()?.takeIf { it.isNotBlank() },
    )

    private val s3: S3Client = buildClient()

    private fun buildClient(): S3Client {
        val builder = S3Client.builder().region(Region.of(region))
        if (accessKey != null && secretKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        }
        endpoint?.let { ep ->
            builder.endpointOverride(java.net.URI.create(ep))
            builder.forcePathStyle(true)
        }
        return builder.build()
    }

    override fun upload(localDir: Path, runId: String, repoName: String): List<String> {
        if (!Files.isDirectory(localDir)) return emptyList()
        val key = "$prefix/$runId/$repoName.zip"
        val zipPath = createTempFile("artifact-", ".zip")
        try {
            zipDirectory(localDir, zipPath)
            val size = Files.size(zipPath)
            val request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength(size)
                .build()
            s3.putObject(request, RequestBody.fromFile(zipPath.toFile()))
            return listOf("s3://$bucket/$key")
        } catch (e: Exception) {
            return emptyList()
        } finally {
            zipPath.deleteIfExists()
        }
    }

    private fun zipDirectory(sourceDir: Path, zipPath: Path) {
        ZipOutputStream(zipPath.outputStream()).use { zos ->
            Files.walk(sourceDir).use { paths ->
                paths.forEach { path ->
                    if (path == sourceDir) return@forEach
                    val relative = sourceDir.relativize(path).toString().replace("\\", "/")
                    if (Files.isDirectory(path)) {
                        zos.putNextEntry(java.util.zip.ZipEntry("$relative/"))
                        zos.closeEntry()
                    } else {
                        zos.putNextEntry(java.util.zip.ZipEntry(relative))
                        path.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
        }
    }

    fun close() {
        s3.close()
    }
}
