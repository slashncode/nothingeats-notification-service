package io.github.slashncode.nothingeats.notification.app

import io.github.slashncode.nothingeats.platform.core.config.loadConfig

/** `serve` (default), `migrate`, or `cron purge-messaging`. */
fun main(args: Array<String>) {
    ServiceComponent::class.create(loadConfig<ServiceConfig>()).runner.run(args)
}
