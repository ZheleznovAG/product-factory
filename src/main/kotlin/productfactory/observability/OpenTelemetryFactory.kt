package productfactory.observability

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.exporter.logging.LoggingSpanExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.trace.export.SpanExporter

data class TelemetryRuntime(
    val openTelemetry: OpenTelemetry,
    private val sdk: OpenTelemetrySdk?,
) {
    fun shutdown() {
        sdk?.close()
    }
}

object OpenTelemetryFactory {
    fun initialize(): TelemetryRuntime {
        val serviceName = System.getenv("OTEL_SERVICE_NAME")
            ?.takeIf { it.isNotBlank() }
            ?: "product-factory"
        val exporterType = System.getenv("OTEL_TRACES_EXPORTER")
            ?.trim()
            ?.lowercase()
            ?: "console"
        val otlpEndpoint = System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")
            ?.takeIf { it.isNotBlank() }

        val exporter = createExporter(exporterType, otlpEndpoint)
        val resource = Resource.getDefault().merge(
            Resource.create(
                Attributes.of(AttributeKey.stringKey("service.name"), serviceName),
            ),
        )
        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build()

        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal()

        return TelemetryRuntime(
            openTelemetry = GlobalOpenTelemetry.get(),
            sdk = sdk,
        )
    }

    private fun createExporter(exporterType: String, otlpEndpoint: String?): SpanExporter {
        if (exporterType == "otlp") {
            val builder = OtlpGrpcSpanExporter.builder()
            if (otlpEndpoint != null) {
                builder.setEndpoint(otlpEndpoint)
            }
            return builder.build()
        }
        return LoggingSpanExporter.create()
    }
}
