package productfactory.workflow.temporal

import io.temporal.client.WorkflowClient
import io.temporal.client.WorkflowClientOptions
import io.temporal.client.WorkflowOptions
import io.temporal.serviceclient.WorkflowServiceStubs
import io.temporal.serviceclient.WorkflowServiceStubsOptions
import io.temporal.worker.Worker
import io.temporal.worker.WorkerFactory
import io.temporal.worker.WorkerOptions
import productfactory.api.FactoryRunRequest
import productfactory.workflow.FactoryWorkflowExecution
import java.time.Duration

/**
 * Запуск Temporal Worker и клиент для старта workflow.
 * Используется при заданном TEMPORAL_ADDRESS.
 */
object TemporalFactoryWorker {

    const val TASK_QUEUE = "product-factory"
    const val NAMESPACE = "default"

    fun createServiceStubs(address: String): WorkflowServiceStubs {
        return WorkflowServiceStubs.newServiceStubs(
            WorkflowServiceStubsOptions.newBuilder()
                .setTarget(address)
                .build(),
        )
    }

    fun createClient(serviceStubs: WorkflowServiceStubs, namespace: String = NAMESPACE): WorkflowClient {
        return WorkflowClient.newInstance(
            serviceStubs,
            WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build(),
        )
    }

    /**
     * Создаёт и запускает Worker: регистрирует workflow и activities.
     * Возвращает WorkerFactory (держать ссылку и вызвать shutdown() при остановке).
     */
    fun startWorker(
        client: WorkflowClient,
        execution: FactoryWorkflowExecution,
        taskQueue: String = TASK_QUEUE,
    ): WorkerFactory {
        val factory = WorkerFactory.newInstance(client)
        val worker = factory.newWorker(
            taskQueue,
            WorkerOptions.newBuilder()
                .setMaxConcurrentActivityExecutionSize(4)
                .build(),
        )
        worker.registerWorkflowImplementationTypes(FactoryTemporalWorkflowImpl::class.java)
        worker.registerActivitiesImplementations(FactoryTemporalActivitiesImpl(execution))
        factory.start()
        return factory
    }

    /**
     * Запускает workflow асинхронно (не ждёт завершения).
     * WorkflowId = runId для последующего describe/query.
     */
    fun startWorkflow(
        client: WorkflowClient,
        runId: String,
        request: FactoryRunRequest,
        taskQueue: String = TASK_QUEUE,
    ) {
        val options = WorkflowOptions.newBuilder()
            .setWorkflowId(runId)
            .setTaskQueue(taskQueue)
            .setWorkflowExecutionTimeout(Duration.ofHours(2))
            .build()
        val workflow = client.newWorkflowStub(FactoryTemporalWorkflow::class.java, options)
        WorkflowClient.start(workflow::run, runId, request)
    }
}
