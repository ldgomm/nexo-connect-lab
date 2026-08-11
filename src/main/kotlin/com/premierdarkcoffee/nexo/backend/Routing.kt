package com.premierdarkcoffee.nexo.backend

import com.premierdarkcoffee.nexo.backend.health.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureRouting() {
    routing {
        livenessRoutes()
        readinessRoutes(this@configureRouting)
        get("/") {
            call.respondText("Hello, World!")
        }
    }
}