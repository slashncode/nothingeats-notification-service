// HTTP routes, persistence and other technology live here, behind the application's ports.
plugins {
    id("nothingeats.kotlin-base")
    id("nothingeats.kotlinx-serialization")
    `java-test-fixtures`
}

dependencies {
    implementation(platform(libs.nothingeats.platform.bom))
    api(project(":application"))
    api(libs.nothingeats.platform.http)
    api(libs.nothingeats.platform.persistence)
    api(libs.nothingeats.ktor.posthog)
    api(libs.nothingeats.platform.kafka)
    api(libs.nothingeats.platform.valkey)
    implementation(libs.ktor.server.sse)
    implementation(libs.angus.mail)
    implementation(libs.exposed.java.time)

    testFixturesImplementation(libs.nothingeats.platform.testing)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testImplementation(testFixtures(project(":application")))
    testImplementation(libs.nothingeats.platform.testing)
    testImplementation(libs.ktor.server.test.host)
    // The live stream is tested over a real socket: the test host only answers once a handler ends.
    testImplementation(libs.ktor.server.netty)
}
