package catalog.api

import catalog.service.CatalogService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String)

fun Route.catalogRoutes(catalogService: CatalogService = CatalogService()) {
    get("/health") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "ok"))
    }

    get("/health/ready") {
        call.respond(HttpStatusCode.OK, HealthResponse(status = "ready"))
    }

    route("/api/catalog") {
        get {
            call.respond(HttpStatusCode.OK, catalogService.listItems())
        }
    }
}
