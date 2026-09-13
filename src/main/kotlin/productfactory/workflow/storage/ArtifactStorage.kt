package productfactory.workflow.storage

import java.nio.file.Path

/**
 * Опциональное хранилище артефактов (S3/MinIO). После создания репо в workspace
 * фабрика может сразу загрузить архив в хранилище и вернуть URI для доступа.
 */
interface ArtifactStorage {
    /**
     * Загружает директорию в хранилище (ZIP). Идемпотентно по ключу.
     * @return Список URI артефактов (например s3://bucket/artifacts/runId/repoName.zip); пустой при ошибке
     */
    fun upload(localDir: Path, runId: String, repoName: String): List<String>
}
