package webapp

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.http.ContentType
import webapp.routes.webAppRoutes

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        get("/") {
            call.respondText(
                """<!DOCTYPE html><html><head><meta charset="utf-8"><title>Web App</title></head><body><h1>Web App</h1><p>Archetype: web-app. <a href="/health">Health</a></p></body></html>""",
                ContentType.Text.Html,
            )
        }
        webAppRoutes()
    }
}
