package io.sentry.android.core

import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.eq
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.never
import com.nhaarman.mockitokotlin2.verify
import io.sentry.Hint
import io.sentry.ILogger
import io.sentry.Sentry
import io.sentry.SentryEnvelope
import io.sentry.SentryLevel
import io.sentry.SentryLevel.DEBUG
import io.sentry.SentryLevel.FATAL
import io.sentry.android.fragment.FragmentLifecycleIntegration
import io.sentry.android.timber.SentryTimberIntegration
import io.sentry.cache.IEnvelopeCache
import io.sentry.transport.NoOpEnvelopeCache
import org.junit.runner.RunWith
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SentryAndroidTest {

    class Fixture {

        fun initSut(
            autoInit: Boolean = false,
            logger: ILogger? = null,
            options: Sentry.OptionsConfiguration<SentryAndroidOptions>? = null
        ) {
            val metadata = Bundle().apply {
                putString(ManifestMetadataReader.DSN, "https://key@sentry.io/123")
                putBoolean(ManifestMetadataReader.AUTO_INIT, autoInit)
            }
            val mockContext = ContextUtilsTest.mockMetaData(metaData = metadata)
            when {
                logger != null -> SentryAndroid.init(mockContext, logger)
                options != null -> SentryAndroid.init(mockContext, options)
                else -> SentryAndroid.init(mockContext)
            }
        }
    }

    private val fixture = Fixture()

    @BeforeTest
    fun `set up`() {
        Sentry.close()
        AppStartState.getInstance().resetInstance()
    }

    @Test
    fun `when auto-init is disabled and user calls init manually, SDK initializes`() {
        assertFalse(Sentry.isEnabled())

        fixture.initSut()

        assertTrue(Sentry.isEnabled())
    }

    @Test
    fun `when auto-init is disabled and user calls init manually with a logger, SDK initializes`() {
        assertFalse(Sentry.isEnabled())

        fixture.initSut(logger = mock())

        assertTrue(Sentry.isEnabled())
    }

    @Test
    fun `when auto-init is disabled and user calls init manually with configuration handler, options should be set`() {
        assertFalse(Sentry.isEnabled())

        var refOptions: SentryAndroidOptions? = null
        fixture.initSut {
            it.anrTimeoutIntervalMillis = 3000
            refOptions = it
        }

        assertEquals(3000, refOptions!!.anrTimeoutIntervalMillis)
        assertTrue(Sentry.isEnabled())
    }

    @Test
    fun `init won't throw exception`() {
        val logger = mock<ILogger>()

        fixture.initSut(autoInit = true, logger = logger)

        verify(logger, never()).log(eq(SentryLevel.FATAL), any<String>(), any())
    }

    @Test
    fun `set app start if provider is disabled`() {
        fixture.initSut(autoInit = true)

        // done by ActivityLifecycleIntegration so forcing it here
        AppStartState.getInstance().setAppStartEnd()
        AppStartState.getInstance().setColdStart(true)

        assertNotNull(AppStartState.getInstance().appStartInterval)
    }

    @Test
    fun `deduplicates fragment and timber integrations`() {
        var refOptions: SentryAndroidOptions? = null

        fixture.initSut(autoInit = true) {
            it.addIntegration(
                FragmentLifecycleIntegration(ApplicationProvider.getApplicationContext())
            )

            it.addIntegration(
                SentryTimberIntegration(minEventLevel = FATAL, minBreadcrumbLevel = DEBUG)
            )
            refOptions = it
        }

        assertEquals(refOptions!!.integrations.filterIsInstance<SentryTimberIntegration>().size, 1)
        val timberIntegration =
            refOptions!!.integrations.find { it is SentryTimberIntegration } as SentryTimberIntegration
        assertEquals(timberIntegration.minEventLevel, FATAL)
        assertEquals(timberIntegration.minBreadcrumbLevel, DEBUG)

        // fragment integration is not auto-installed in the test, since the context is not Application
        // but we just verify here that the single integration is preserved
        assertEquals(refOptions!!.integrations.filterIsInstance<FragmentLifecycleIntegration>().size, 1)
    }

    @Test
    fun `AndroidEnvelopeCache is reset if the user disabled caching via cacheDirPath`() {
        var refOptions: SentryAndroidOptions? = null

        fixture.initSut {
            it.cacheDirPath = null

            refOptions = it
        }

        assertTrue { refOptions!!.envelopeDiskCache is NoOpEnvelopeCache }
    }

    @Test
    fun `envelopeCache remains unchanged if the user set their own IEnvelopCache impl`() {
        var refOptions: SentryAndroidOptions? = null

        fixture.initSut {
            it.cacheDirPath = null
            it.setEnvelopeDiskCache(CustomEnvelopCache())

            refOptions = it
        }

        assertTrue { refOptions!!.envelopeDiskCache is CustomEnvelopCache }
    }

    private class CustomEnvelopCache : IEnvelopeCache {
        override fun iterator(): MutableIterator<SentryEnvelope> = TODO()
        override fun store(envelope: SentryEnvelope, hint: Hint) = Unit
        override fun discard(envelope: SentryEnvelope) = Unit
    }
}
