package io.github.slashncode.nothingeats.notification.app

import io.github.slashncode.nothingeats.notification.adapters.cron.ReplayIdentityTask
import io.github.slashncode.nothingeats.notification.adapters.live.LiveStreamRoutes
import io.github.slashncode.nothingeats.notification.adapters.live.SseExtension
import io.github.slashncode.nothingeats.notification.adapters.live.ValkeyLiveUpdates
import io.github.slashncode.nothingeats.notification.adapters.mail.SmtpMailSender
import io.github.slashncode.nothingeats.notification.adapters.messaging.CourierEventsHandler
import io.github.slashncode.nothingeats.notification.adapters.messaging.DeliveryEventsHandler
import io.github.slashncode.nothingeats.notification.adapters.messaging.IdentityEventsHandler
import io.github.slashncode.nothingeats.notification.adapters.messaging.OrderEventsHandler
import io.github.slashncode.nothingeats.notification.adapters.messaging.RecipientProjection
import io.github.slashncode.nothingeats.notification.adapters.persistence.ExposedDeliveries
import io.github.slashncode.nothingeats.notification.adapters.persistence.ExposedRecipients
import io.github.slashncode.nothingeats.notification.application.Deliveries
import io.github.slashncode.nothingeats.notification.application.LiveUpdates
import io.github.slashncode.nothingeats.notification.application.MailComposer
import io.github.slashncode.nothingeats.notification.application.MailSender
import io.github.slashncode.nothingeats.notification.application.OrderMails
import io.github.slashncode.nothingeats.notification.application.Recipients
import io.github.slashncode.nothingeats.notification.application.UserMails
import io.github.slashncode.nothingeats.platform.core.analytics.ProductAnalytics
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthCheck
import io.github.slashncode.nothingeats.platform.core.lifecycle.HealthChecks
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleComponent
import io.github.slashncode.nothingeats.platform.core.lifecycle.LifecycleManager
import io.github.slashncode.nothingeats.platform.core.lifecycle.Readiness
import io.github.slashncode.nothingeats.platform.core.run.CronTask
import io.github.slashncode.nothingeats.platform.core.run.ServiceRunner
import io.github.slashncode.nothingeats.platform.http.ExceptionReporter
import io.github.slashncode.nothingeats.platform.http.HttpExtension
import io.github.slashncode.nothingeats.platform.http.HttpRoutes
import io.github.slashncode.nothingeats.platform.http.HttpServer
import io.github.slashncode.nothingeats.platform.http.auth.JwtAuth
import io.github.slashncode.nothingeats.platform.kafka.EventConsumer
import io.github.slashncode.nothingeats.platform.kafka.EventConsumerConfig
import io.github.slashncode.nothingeats.platform.kafka.EventHandler
import io.github.slashncode.nothingeats.platform.kafka.KafkaClients
import io.github.slashncode.nothingeats.platform.kafka.KafkaMigrations
import io.github.slashncode.nothingeats.platform.kafka.PurgeMessagingTask
import io.github.slashncode.nothingeats.platform.persistence.FlywayMigrations
import io.github.slashncode.nothingeats.platform.persistence.MigrationSet
import io.github.slashncode.nothingeats.platform.persistence.PostgresDatabase
import io.github.slashncode.nothingeats.platform.posthog.PostHogAnalytics
import io.github.slashncode.nothingeats.platform.posthog.PostHogExceptionReporter
import io.github.slashncode.nothingeats.platform.posthog.PostHogHttpExtension
import io.github.slashncode.nothingeats.platform.valkey.ValkeyPubSub
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.IntoSet
import me.tatarka.inject.annotations.Provides
import me.tatarka.inject.annotations.Scope
import java.time.Clock

@Scope
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
annotation class ServiceScope

/**
 * The composition root: the only place that wires ports to adapters.
 * Lifecycle components, health checks, event handlers and cron tasks are collected with `@IntoSet`.
 */
@ServiceScope
@Component
abstract class ServiceComponent(
    private val config: ServiceConfig,
) {
    abstract val runner: ServiceRunner
    abstract val lifecycle: LifecycleManager
    abstract val migrations: FlywayMigrations
    abstract val recipients: Recipients

    // Platform

    @ServiceScope
    @Provides
    fun readiness(): Readiness = Readiness()

    @Provides
    fun clock(): Clock = Clock.systemUTC()

    @ServiceScope
    @Provides
    fun flywayMigrations(): FlywayMigrations = FlywayMigrations(config.database, listOf(KafkaMigrations, MigrationSet.SERVICE))

    @ServiceScope
    @Provides
    fun postgres(migrations: FlywayMigrations): PostgresDatabase = PostgresDatabase(config.database, migrations)

    @ServiceScope
    @Provides
    fun httpServer(
        readiness: Readiness,
        checks: Set<HealthCheck>,
        routes: Set<HttpRoutes>,
        extensions: Set<HttpExtension>,
        exceptionReporter: ExceptionReporter,
    ): HttpServer = HttpServer(config.http, readiness, HealthChecks(checks), routes, JwtAuth(config.jwt), extensions, exceptionReporter)

    @ServiceScope
    @Provides
    fun lifecycleManager(
        components: Set<LifecycleComponent>,
        readiness: Readiness,
    ): LifecycleManager = LifecycleManager(components, readiness, config.lifecycle.drainDelay)

    @Provides
    fun serviceRunner(
        lifecycle: LifecycleManager,
        migrations: FlywayMigrations,
        cronTasks: Set<CronTask>,
    ): ServiceRunner = ServiceRunner(lifecycle, migrations, cronTasks)

    @IntoSet
    @Provides
    fun postgresLifecycle(postgres: PostgresDatabase): LifecycleComponent = postgres

    @IntoSet
    @Provides
    fun postgresHealth(postgres: PostgresDatabase): HealthCheck = postgres

    @IntoSet
    @Provides
    fun httpLifecycle(server: HttpServer): LifecycleComponent = server

    @ServiceScope
    @Provides
    fun postHog(): PostHogAnalytics = PostHogAnalytics(config.posthog)

    @Provides
    fun analytics(postHog: PostHogAnalytics): ProductAnalytics = postHog

    @Provides
    fun exceptionReporter(analytics: ProductAnalytics): ExceptionReporter = PostHogExceptionReporter(analytics)

    @IntoSet
    @Provides
    fun postHogLifecycle(postHog: PostHogAnalytics): LifecycleComponent = postHog

    @IntoSet
    @Provides
    fun postHogHttp(): HttpExtension = PostHogHttpExtension()

    // Live updates (server-sent events, fanned out between pods through Valkey)

    @ServiceScope
    @Provides
    fun valkeyPubSub(): ValkeyPubSub = ValkeyPubSub(config.valkey, setOf(ValkeyLiveUpdates.PATTERN))

    /** Not a readiness check: without Valkey only live updates stop, mails keep going. */
    @IntoSet
    @Provides
    fun valkeyPubSubLifecycle(pubSub: ValkeyPubSub): LifecycleComponent = pubSub

    @Provides
    fun liveUpdates(pubSub: ValkeyPubSub): LiveUpdates = ValkeyLiveUpdates(pubSub)

    @IntoSet
    @Provides
    fun sse(): HttpExtension = SseExtension()

    @IntoSet
    @Provides
    fun liveStream(pubSub: ValkeyPubSub): HttpRoutes = LiveStreamRoutes(pubSub)

    // Messaging

    @ServiceScope
    @Provides
    fun eventConsumer(
        handlers: Set<EventHandler>,
        postgres: PostgresDatabase,
    ): EventConsumer =
        EventConsumer(
            config = EventConsumerConfig(groupId = "notification-service"),
            handlers = handlers,
            db = postgres,
            consumerFactory = { KafkaClients.consumer(config.kafka, "notification-service") },
            deadLetters = KafkaClients.producer(config.kafka, "notification-service-dlq"),
        )

    @IntoSet
    @Provides
    fun eventConsumerLifecycle(consumer: EventConsumer): LifecycleComponent = consumer

    @IntoSet
    @Provides
    fun eventConsumerHealth(consumer: EventConsumer): HealthCheck = consumer

    @IntoSet
    @Provides
    fun purgeMessaging(postgres: PostgresDatabase): CronTask = PurgeMessagingTask(postgres)

    // Mail

    @Provides
    fun mailSender(): MailSender = SmtpMailSender(config.smtp)

    @Provides
    fun deliveries(
        postgres: PostgresDatabase,
        clock: Clock,
    ): Deliveries = ExposedDeliveries(postgres, clock)

    @Provides
    fun userMails(
        sender: MailSender,
        deliveries: Deliveries,
        analytics: ProductAnalytics,
    ): UserMails = UserMails(MailComposer(config.links), sender, deliveries, analytics)

    @Provides
    fun recipients(
        postgres: PostgresDatabase,
        clock: Clock,
    ): Recipients = ExposedRecipients(postgres, clock)

    @Provides
    fun recipientProjection(recipients: Recipients): RecipientProjection = RecipientProjection(recipients)

    @Provides
    fun orderMails(
        recipients: Recipients,
        sender: MailSender,
        deliveries: Deliveries,
        analytics: ProductAnalytics,
    ): OrderMails = OrderMails(recipients, MailComposer(config.links), sender, deliveries, analytics)

    @IntoSet
    @Provides
    fun identityEvents(
        mails: UserMails,
        projection: RecipientProjection,
    ): EventHandler = IdentityEventsHandler(mails, projection)

    @IntoSet
    @Provides
    fun orderEvents(
        mails: OrderMails,
        live: LiveUpdates,
    ): EventHandler = OrderEventsHandler(mails, live)

    @IntoSet
    @Provides
    fun deliveryEvents(
        mails: OrderMails,
        live: LiveUpdates,
    ): EventHandler = DeliveryEventsHandler(mails, live)

    @IntoSet
    @Provides
    fun replayIdentity(projection: RecipientProjection): CronTask = ReplayIdentityTask(config.kafka, projection)

    /**
     * Courier positions in a consumer group of their own, without an inbox: ephemeral, frequent,
     * harmless when repeated. Not a readiness check either; mails must not wait for map dots.
     */
    @IntoSet
    @Provides
    fun courierConsumer(live: LiveUpdates): LifecycleComponent =
        EventConsumer(
            config = EventConsumerConfig(groupId = "notification-service-live"),
            handlers = setOf(CourierEventsHandler(live)),
            db = null,
            consumerFactory = { KafkaClients.consumer(config.kafka, "notification-service-live") },
            deadLetters = KafkaClients.producer(config.kafka, "notification-service-live-dlq"),
        )
}
